//! Plugin-created chest inventories (`Bukkit.createInventory`) shown through Pumpkin.
//!
//! Pumpkin holds the live contents in a `SimpleInventory` per open inventory; the
//! Java `Inventory` reads and writes them by id while it is open.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use pumpkin_data::item_stack::ItemStack;
use pumpkin_data::screen::WindowType;
use pumpkin_inventory::screen_handler::{
    InventoryPlayer, ScreenHandler, ScreenHandlerBehaviour, ScreenHandlerFactory,
    SharedScreenHandler,
};
use pumpkin_inventory::slot::NormalSlot;
use pumpkin_inventory::{Inventory, SimpleInventory};

use crate::java::native_callbacks::itemstack::{proto_item_to_pumpkin, pumpkin_item_to_proto};
use crate::java::native_callbacks::utils::with_player;
use crate::proto::patchbukkit::itemstack::{CustomInventoryRequest, CustomInventoryResponse};

/// Open custom inventories by Java-side id, with their number of open screens.
static OPEN: Mutex<Option<HashMap<u64, (Arc<SimpleInventory>, usize)>>> = Mutex::new(None);

fn with_open<R>(f: impl FnOnce(&mut HashMap<u64, (Arc<SimpleInventory>, usize)>) -> R) -> R {
    let mut guard = OPEN.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    f(guard.get_or_insert_with(HashMap::new))
}

fn window_type(rows: usize) -> WindowType {
    match rows {
        1 => WindowType::Generic9x1,
        2 => WindowType::Generic9x2,
        3 => WindowType::Generic9x3,
        4 => WindowType::Generic9x4,
        5 => WindowType::Generic9x5,
        _ => WindowType::Generic9x6,
    }
}

struct CustomChestScreenHandler {
    behaviour: ScreenHandlerBehaviour,
    /// The custom inventory's id, released when its last screen closes.
    id: Option<u64>,
    slots: i32,
}

impl ScreenHandler for CustomChestScreenHandler {
    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
    fn as_any_mut(&mut self) -> &mut dyn std::any::Any {
        self
    }
    fn get_behaviour(&self) -> &ScreenHandlerBehaviour {
        &self.behaviour
    }
    fn get_behaviour_mut(&mut self) -> &mut ScreenHandlerBehaviour {
        &mut self.behaviour
    }
    fn on_closed(&mut self, player: &dyn InventoryPlayer) {
        // The cursor holds a plugin-built stack; the default close logic would hand it
        // to server code.
        let cursor = std::mem::replace(
            &mut *self
                .behaviour
                .cursor_stack
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner),
            ItemStack::EMPTY.clone(),
        );
        crate::java::native_callbacks::item_domain::return_cursor(player, cursor);
        let Some(id) = self.id else { return };
        with_open(|open| {
            if let Some(entry) = open.get_mut(&id) {
                entry.1 = entry.1.saturating_sub(1);
                if entry.1 == 0 {
                    open.remove(&id);
                }
            }
        });
    }
    fn quick_move(&mut self, _player: &dyn InventoryPlayer, slot_index: i32) -> ItemStack {
        let empty = ItemStack::EMPTY.clone();
        let Some(slot) = self.get_behaviour().slots.get(slot_index as usize).cloned() else {
            return empty;
        };
        if !slot.has_stack() {
            return empty;
        }
        let mut stack = slot.get_stack();
        let before = stack.clone();
        let moved = if slot_index < self.slots {
            let end = self.get_behaviour().slots.len() as i32;
            self.insert_item(&mut stack, self.slots, end, true)
        } else {
            self.insert_item(&mut stack, 0, self.slots, false)
        };
        if !moved {
            return empty;
        }
        slot.set_stack(if stack.is_empty() { empty } else { stack });
        before
    }
}

/// A chest screen over any inventory, built and run by the plugin on plugin-built
/// stacks (see [`crate::java::native_callbacks::item_domain`]).
pub struct PluginChestFactory {
    pub inventory: Arc<dyn Inventory>,
    pub rows: usize,
    pub title: pumpkin_util::text::TextComponent,
    pub id: Option<u64>,
}

impl ScreenHandlerFactory for PluginChestFactory {
    fn create_screen_handler(
        &self,
        sync_id: u8,
        player_inventory: &Arc<pumpkin_inventory::player::player_inventory::PlayerInventory>,
        _player: &dyn InventoryPlayer,
    ) -> Option<SharedScreenHandler> {
        use crate::java::native_callbacks::item_domain::PluginView;
        let slots = (self.rows * 9).min(self.inventory.size());
        let mut handler = CustomChestScreenHandler {
            behaviour: ScreenHandlerBehaviour::new(sync_id, Some(window_type(self.rows))),
            id: self.id,
            slots: (self.rows * 9) as i32,
        };
        let inventory: Arc<dyn Inventory> = Arc::new(PluginView(self.inventory.clone()));
        let filler: Arc<dyn Inventory> = Arc::new(SimpleInventory::new(1));
        for i in 0..self.rows * 9 {
            if i < slots {
                handler.add_slot(Arc::new(NormalSlot::new(inventory.clone(), i)));
            } else {
                handler.add_slot(Arc::new(crate::java::native_callbacks::entity::LockedSlot::new(filler.clone())));
            }
        }
        let player: Arc<dyn Inventory> = Arc::new(PluginView(player_inventory.clone()));
        handler.add_player_slots(&player);
        if let Some(id) = self.id {
            with_open(|open| {
                if let Some(entry) = open.get_mut(&id) {
                    entry.1 += 1;
                }
            });
        }
        Some(Arc::new(Mutex::new(handler)))
    }

    fn get_display_name(&self) -> pumpkin_util::text::TextComponent {
        self.title.clone()
    }
}

fn contents(inv: &SimpleInventory) -> Vec<crate::proto::patchbukkit::itemstack::ItemStack> {
    (0..inv.size()).map(|i| pumpkin_item_to_proto(&inv.get_stack(i))).collect()
}

fn store(inv: &SimpleInventory, items: &[crate::proto::patchbukkit::itemstack::ItemStack]) {
    for i in 0..inv.size() {
        inv.set_stack(i, proto_item_to_pumpkin(items.get(i)));
    }
}

pub fn ffi_native_bridge_open_custom_inventory_impl(
    request: CustomInventoryRequest,
) -> Option<CustomInventoryResponse> {
    let rows = (request.rows as usize).clamp(1, 6);
    let inventory = with_open(|open| {
        open.entry(request.id)
            .or_insert_with(|| (Arc::new(SimpleInventory::new(rows * 9)), 0))
            .0
            .clone()
    });
    store(&inventory, &request.items);
    let factory = PluginChestFactory {
        inventory,
        rows,
        title: pumpkin_util::text::TextComponent::from_legacy_string(&request.title),
        id: Some(request.id),
    };
    let opened = with_player(request.viewer.as_ref(), |viewer| {
        viewer.open_handled_screen(&factory, None).is_some()
    })?;
    Some(CustomInventoryResponse { found: opened, items: vec![] })
}

pub fn ffi_native_bridge_get_custom_inventory_impl(
    request: CustomInventoryRequest,
) -> Option<CustomInventoryResponse> {
    let inventory = with_open(|open| open.get(&request.id).map(|e| e.0.clone()));
    Some(match inventory {
        Some(inv) => CustomInventoryResponse { found: true, items: contents(&inv) },
        None => CustomInventoryResponse::default(),
    })
}

pub fn ffi_native_bridge_set_custom_inventory_impl(
    request: CustomInventoryRequest,
) -> Option<CustomInventoryResponse> {
    let inventory = with_open(|open| open.get(&request.id).map(|e| e.0.clone()));
    Some(match inventory {
        Some(inv) => {
            store(&inv, &request.items);
            CustomInventoryResponse { found: true, items: vec![] }
        }
        None => CustomInventoryResponse::default(),
    })
}
