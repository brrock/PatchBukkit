use pumpkin_data::data_component_impl::EquipmentSlot;
use pumpkin_command::argument_types::argument_type::{AnyArgumentType, JavaClientArgumentType};
use pumpkin_command::node::attached::AttachedNode;
use pumpkin_command::string_reader::StringReader;
use pumpkin::command::CommandSource;
use pumpkin_data::data_component::DataComponent;
use pumpkin_nbt::tag::NbtTag;
use std::any::TypeId;
use std::sync::{Arc, Mutex};
use pumpkin_data::item_stack::ItemStack as PumpkinItemStack;

use crate::{
    java::native_callbacks::CALLBACK_CONTEXT,
    java::native_callbacks::utils::with_player,
    proto::patchbukkit::{
        common::Uuid,
        itemstack::{
            GetPlayerInventoryResponse, ItemStack as ProtoItemStack, SetPlayerEquipmentRequest,
            SetPlayerInventorySlotRequest, SetPlayerSelectedSlotRequest,
        },
    },
};

/// Reads the enchantments of a stack without downcasting any component.
///
/// Components on server-created stacks carry the *server's* vtables and
/// `TypeId`s. The plugin compiles its own copy of `pumpkin-data`, so a
/// `downcast_ref` from here would fail (and `data_component_impl::get` would
/// panic). `write_data` is a virtual call that runs the owning side's code and
/// returns plain NBT, which is safe to inspect from either side.
fn read_enchantments(stack: &PumpkinItemStack) -> Vec<(String, i32)> {
    let mut out = Vec::new();
    for (component, value) in &stack.patch {
        if *component != DataComponent::Enchantments {
            continue;
        }
        let Some(value) = value else { continue };
        if let NbtTag::Compound(compound) = value.write_data() {
            let levels = match compound.child_tags.get("levels") {
                Some(NbtTag::Compound(levels)) => levels.child_tags.clone(),
                _ => compound.child_tags.clone(),
            };
            for (name, level) in levels {
                if let Some(level) = level.extract_int() {
                    out.push((name.to_string(), level));
                }
            }
        }
    }
    out
}

fn pumpkin_item_to_proto(stack: &PumpkinItemStack) -> ProtoItemStack {
    if stack.is_empty() {
        ProtoItemStack {
            r#type: "minecraft:air".to_string(),
            amount: 0,
            ..Default::default()
        }
    } else {
        let registry_key = stack.item.registry_key;
        let r#type = if registry_key.starts_with("minecraft:") {
            registry_key.to_string()
        } else {
            format!("minecraft:{registry_key}")
        };
        ProtoItemStack {
            r#type,
            amount: u32::from(stack.item_count),
            enchantments: read_enchantments(stack).into_iter().collect(),
        }
    }
}

type ServerArgType = Arc<dyn AnyArgumentType<CommandSource>>;

/// The server's own `ItemStackArgumentType`, taken from its command tree.
static SERVER_ITEM_PARSER: Mutex<Option<ServerArgType>> = Mutex::new(None);

/// Finds an item-stack argument node that the *server* registered (e.g. the
/// `item` argument of vanilla `/give`). Calling its `parse` runs server code,
/// so the resulting `ItemStack` (its `&'static Item` and every component) is
/// built from the server's `pumpkin-data` and can be downcast by the server.
fn server_item_parser() -> Option<ServerArgType> {
    let mut guard = SERVER_ITEM_PARSER
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    if let Some(parser) = guard.as_ref() {
        return Some(parser.clone());
    }
    let ctx = CALLBACK_CONTEXT.get()?;
    let dispatcher = ctx.plugin_context.server.command_dispatcher.load();
    let local_type = TypeId::of::<PumpkinItemStack>();
    let mut fallback = None;
    for node in dispatcher.tree.iter() {
        let AttachedNode::Argument(arg) = node else {
            continue;
        };
        let parser = &arg.meta.argument_type;
        if !matches!(parser.client_side_parser(), JavaClientArgumentType::ItemStack) {
            continue;
        }
        let Ok(probe) = parser.parse(&mut StringReader::new("minecraft:stone")) else {
            continue;
        };
        if (*probe).type_id() != local_type {
            // Foreign TypeId: this parser lives in the server binary.
            *guard = Some(parser.clone());
            return Some(parser.clone());
        }
        // Same TypeId as ours: either the plugin registered this node, or the
        // server and plugin share one pumpkin-data build. Both are safe.
        fallback.get_or_insert_with(|| parser.clone());
    }
    if let Some(parser) = fallback.as_ref() {
        *guard = Some(parser.clone());
    }
    fallback
}

/// Builds the `item[components]` string understood by `ItemStackArgumentType`.
fn item_spec(key: &str, proto: &ProtoItemStack) -> String {
    let mut spec = format!("minecraft:{key}");
    let mut enchants: Vec<_> = proto
        .enchantments
        .iter()
        .filter(|(name, level)| **level > 0 && is_safe_ident(name))
        .collect();
    if !enchants.is_empty() {
        enchants.sort();
        let body = enchants
            .iter()
            .map(|(name, level)| {
                let name = if name.contains(':') {
                    (*name).clone()
                } else {
                    format!("minecraft:{name}")
                };
                format!("\"{name}\":{level}")
            })
            .collect::<Vec<_>>()
            .join(",");
        spec.push_str(&format!("[enchantments={{{body}}}]"));
    }
    spec
}

fn is_safe_ident(s: &str) -> bool {
    !s.is_empty()
        && s.chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '_' | ':' | '.' | '-' | '/'))
}

/// Builds a stack through the server's item parser.
///
/// # Safety rationale
/// `parse` returns a `Box<dyn Any>` whose concrete type is the server's
/// `pumpkin_data::item_stack::ItemStack`. Its `TypeId` differs from ours only
/// because of the crate hash; the plugin is built against the same Pumpkin
/// revision, so the layout is identical (the plugin already relies on this
/// for `Player`, `Server`, ...). We verify the result names the requested
/// item before trusting it.
fn server_built_stack(key: &str, proto: &ProtoItemStack) -> Option<PumpkinItemStack> {
    if !is_safe_ident(key) {
        return None;
    }
    let parser = server_item_parser()?;
    let spec = item_spec(key, proto);
    let parsed = parser.parse(&mut StringReader::new(spec)).ok()?;
    let raw = Box::into_raw(parsed).cast::<PumpkinItemStack>();
    let mut stack = unsafe { *Box::from_raw(raw) };
    let got = stack.item.registry_key;
    if got.strip_prefix("minecraft:").unwrap_or(got) != key {
        // Unexpected result; leak rather than run a possibly mismatched drop.
        std::mem::forget(stack);
        return None;
    }
    stack.item_count = proto.amount.min(u32::from(u8::MAX)) as u8;
    Some(stack)
}

pub(crate) fn proto_item_to_pumpkin(proto: Option<&ProtoItemStack>) -> PumpkinItemStack {
    let Some(proto) = proto else {
        return PumpkinItemStack::EMPTY.clone();
    };
    if proto.amount == 0 || proto.r#type.is_empty() {
        return PumpkinItemStack::EMPTY.clone();
    }
    let key = proto
        .r#type
        .strip_prefix("minecraft:")
        .unwrap_or(&proto.r#type);
    if key == "air" {
        return PumpkinItemStack::EMPTY.clone();
    }
    if let Some(stack) = server_built_stack(key, proto) {
        return stack;
    }
    // Never hand the server a stack built from the plugin's own pumpkin-data:
    // its components carry plugin TypeIds and make server-side downcasts panic.
    tracing::warn!("PatchBukkit: could not build item '{}' via the server; dropping it", proto.r#type);
    PumpkinItemStack::EMPTY.clone()
}

pub fn ffi_native_bridge_get_player_inventory_impl(
    request: Uuid,
) -> Option<GetPlayerInventoryResponse> {
    with_player(Some(&request), |player| {
        let selected_slot = u32::from(player.inventory.get_selected_slot());

        let main_inventory = player
            .inventory
            .main_inventory
            .try_read()
            .map(|main_guard| main_guard.iter().map(pumpkin_item_to_proto).collect())
            .unwrap_or_default();

        let (off_hand, helmet, chestplate, leggings, boots) =
            if let Ok(eq_guard) = player.inventory.entity_equipment.try_lock() {
                (
                    pumpkin_item_to_proto(&eq_guard.get(&EquipmentSlot::OFF_HAND)),
                    pumpkin_item_to_proto(&eq_guard.get(&EquipmentSlot::HEAD)),
                    pumpkin_item_to_proto(&eq_guard.get(&EquipmentSlot::CHEST)),
                    pumpkin_item_to_proto(&eq_guard.get(&EquipmentSlot::LEGS)),
                    pumpkin_item_to_proto(&eq_guard.get(&EquipmentSlot::FEET)),
                )
            } else {
                (
                    pumpkin_item_to_proto(PumpkinItemStack::EMPTY),
                    pumpkin_item_to_proto(PumpkinItemStack::EMPTY),
                    pumpkin_item_to_proto(PumpkinItemStack::EMPTY),
                    pumpkin_item_to_proto(PumpkinItemStack::EMPTY),
                    pumpkin_item_to_proto(PumpkinItemStack::EMPTY),
                )
            };

        GetPlayerInventoryResponse {
            main_inventory,
            selected_slot,
            off_hand: Some(off_hand),
            helmet: Some(helmet),
            chestplate: Some(chestplate),
            leggings: Some(leggings),
            boots: Some(boots),
        }
    })
}

pub fn ffi_native_bridge_set_player_inventory_slot_impl(
    request: SetPlayerInventorySlotRequest,
) -> Option<()> {
    let slot = request.slot as usize;
    let pumpkin_item = proto_item_to_pumpkin(request.item.as_ref());

    with_player(request.uuid.as_ref(), |player| {
        if slot < 36 {
            let mut main_guard = player
                .inventory
                .main_inventory
                .write()
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            main_guard[slot] = pumpkin_item;
        } else {
            let eq_slot = match slot {
                36 => Some(EquipmentSlot::FEET),
                37 => Some(EquipmentSlot::LEGS),
                38 => Some(EquipmentSlot::CHEST),
                39 => Some(EquipmentSlot::HEAD),
                40 => Some(EquipmentSlot::OFF_HAND),
                _ => None,
            };
            if let Some(eq_slot) = eq_slot {
                let mut eq_guard = player
                    .inventory
                    .entity_equipment
                    .lock()
                    .unwrap_or_else(std::sync::PoisonError::into_inner);
                eq_guard.put(&eq_slot, pumpkin_item);
            }
        }
    })
}

pub fn ffi_native_bridge_set_player_selected_slot_impl(
    request: SetPlayerSelectedSlotRequest,
) -> Option<()> {
    with_player(request.uuid.as_ref(), |player| {
        if request.slot < 9 {
            player.inventory.set_selected_slot(request.slot as u8);
        }
    })
}

pub fn ffi_native_bridge_set_player_equipment_impl(
    request: SetPlayerEquipmentRequest,
) -> Option<()> {
    let pumpkin_item = proto_item_to_pumpkin(request.item.as_ref());
    let slot_type = request.slot_type;

    with_player(request.uuid.as_ref(), |player| {
        match slot_type {
            0 => {
                // Main Hand
                player.inventory.set_held_item(pumpkin_item);
            }
            1 => {
                // Off Hand
                let mut eq = player
                    .inventory
                    .entity_equipment
                    .lock()
                    .unwrap_or_else(std::sync::PoisonError::into_inner);
                eq.put(&EquipmentSlot::OFF_HAND, pumpkin_item);
            }
            2 => {
                // Feet
                let mut eq = player
                    .inventory
                    .entity_equipment
                    .lock()
                    .unwrap_or_else(std::sync::PoisonError::into_inner);
                eq.put(&EquipmentSlot::FEET, pumpkin_item);
            }
            3 => {
                // Legs
                let mut eq = player
                    .inventory
                    .entity_equipment
                    .lock()
                    .unwrap_or_else(std::sync::PoisonError::into_inner);
                eq.put(&EquipmentSlot::LEGS, pumpkin_item);
            }
            4 => {
                // Chest
                let mut eq = player
                    .inventory
                    .entity_equipment
                    .lock()
                    .unwrap_or_else(std::sync::PoisonError::into_inner);
                eq.put(&EquipmentSlot::CHEST, pumpkin_item);
            }
            5 => {
                // Head
                let mut eq = player
                    .inventory
                    .entity_equipment
                    .lock()
                    .unwrap_or_else(std::sync::PoisonError::into_inner);
                eq.put(&EquipmentSlot::HEAD, pumpkin_item);
            }
            _ => {}
        }
    })
}

pub fn ffi_native_bridge_clear_player_inventory_impl(request: Uuid) -> Option<()> {
    with_player(Some(&request), |player| {
        let mut main_guard = player
            .inventory
            .main_inventory
            .write()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        main_guard.fill_with(|| PumpkinItemStack::EMPTY.clone());
        let mut eq_guard = player
            .inventory
            .entity_equipment
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        eq_guard.clear();
    })
}
