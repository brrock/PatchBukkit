//! Moving item stacks between the server's and the plugin's copy of `pumpkin-data`.
//!
//! The plugin links its own build of Pumpkin's crates, so the `TypeId`s of its item
//! components differ from the server's. Code compiled into the plugin (for example
//! the default `ScreenHandler` click logic of a screen the plugin builds) panics
//! when it downcasts a component of a server-built stack, and the server panics on
//! plugin-built components. Screens the plugin builds therefore work on
//! plugin-built stacks only, and [`PluginView`] converts at the inventory boundary.

use std::sync::Arc;

use pumpkin_data::item::Item;
use pumpkin_data::item_stack::ItemStack;
use pumpkin_inventory::{Clearable, Inventory};
use pumpkin_nbt::tag::NbtTag;

use crate::java::native_callbacks::itemstack::parse_server_stack;

fn short_key(stack: &ItemStack) -> &'static str {
    let key = stack.item.registry_key;
    key.strip_prefix("minecraft:").unwrap_or(key)
}

/// True when the stack's item data is the plugin's own static data.
pub fn is_plugin_stack(stack: &ItemStack) -> bool {
    Item::from_registry_key(short_key(stack)).is_some_and(|item| std::ptr::eq(item, stack.item))
}

/// Rebuilds a server stack from the plugin's data (components via their NBT form).
pub fn to_plugin(stack: ItemStack) -> ItemStack {
    if stack.is_empty() {
        return ItemStack::EMPTY.clone();
    }
    if is_plugin_stack(&stack) {
        return stack;
    }
    let Some(item) = Item::from_registry_key(short_key(&stack)) else {
        return ItemStack::EMPTY.clone();
    };
    let mut out = ItemStack::new(stack.item_count, item);
    for (component, value) in &stack.patch {
        match value {
            Some(value) => {
                if let Some(local) =
                    pumpkin_data::data_component_impl::read_data(*component, &value.write_data())
                {
                    out.patch.push((*component, Some(local)));
                }
            }
            None => out.patch.push((*component, None)),
        }
    }
    // Dropping the server stack is fine: its components drop through their own
    // vtables and both sides use the system allocator.
    drop(stack);
    out
}

/// Quotes a string as an SNBT literal.
fn snbt_string(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            _ => out.push(c),
        }
    }
    out.push('"');
    out
}

fn snbt(tag: &NbtTag) -> String {
    match tag {
        NbtTag::String(s) => snbt_string(s),
        NbtTag::List(items) => {
            let parts: Vec<String> = items.iter().map(snbt).collect();
            format!("[{}]", parts.join(","))
        }
        NbtTag::Compound(c) => {
            let parts: Vec<String> = c
                .child_tags
                .iter()
                .map(|(k, v)| format!("{}:{}", snbt_string(k), snbt(v)))
                .collect();
            format!("{{{}}}", parts.join(","))
        }
        other => other.to_string(),
    }
}

/// Rebuilds a plugin stack through the server's item parser.
pub fn to_server(stack: ItemStack) -> ItemStack {
    if stack.is_empty() || !is_plugin_stack(&stack) {
        return stack;
    }
    let key = short_key(&stack);
    let mut components = Vec::new();
    for (component, value) in &stack.patch {
        match value {
            Some(value) => components.push(format!("{}={}", component.to_name(), snbt(&value.write_data()))),
            None => components.push(format!("!{}", component.to_name())),
        }
    }
    let spec = if components.is_empty() {
        format!("minecraft:{key}")
    } else {
        format!("minecraft:{key}[{}]", components.join(","))
    };
    parse_server_stack(&spec, key, stack.item_count)
        .or_else(|| parse_server_stack(&format!("minecraft:{key}"), key, stack.item_count))
        .unwrap_or_else(|| {
            tracing::warn!("PatchBukkit: could not convert item '{spec}' for the server");
            ItemStack::EMPTY.clone()
        })
}

/// An inventory seen through plugin-built stacks; writes go back as server stacks.
pub struct PluginView(pub Arc<dyn Inventory>);

impl Clearable for PluginView {
    fn clear(&self) {
        self.0.clear();
    }
}

impl Inventory for PluginView {
    fn size(&self) -> usize {
        self.0.size()
    }
    fn is_empty(&self) -> bool {
        self.0.is_empty()
    }
    fn get_stack(&self, slot: usize) -> ItemStack {
        to_plugin(self.0.get_stack(slot))
    }
    fn remove_stack(&self, slot: usize) -> ItemStack {
        to_plugin(self.0.remove_stack(slot))
    }
    fn remove_stack_specific(&self, slot: usize, amount: u8) -> ItemStack {
        let current = self.get_stack(slot);
        if current.is_empty() || amount == 0 {
            return ItemStack::EMPTY.clone();
        }
        let take = amount.min(current.item_count);
        let mut taken = current.clone();
        taken.item_count = take;
        let mut rest = current;
        rest.item_count -= take;
        self.set_stack(slot, if rest.item_count == 0 { ItemStack::EMPTY.clone() } else { rest });
        taken
    }
    fn set_stack(&self, slot: usize, stack: ItemStack) {
        self.0.set_stack(slot, to_server(stack));
    }
    fn on_open(&self) {
        self.0.on_open();
    }
    fn on_close(&self) {
        self.0.on_close();
    }
    fn get_max_count_per_stack(&self) -> u8 {
        self.0.get_max_count_per_stack()
    }
    fn mark_dirty(&self) {
        self.0.mark_dirty();
    }
    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
}

/// Returns a plugin-built cursor stack to the player when a plugin-built screen closes.
pub fn return_cursor(player: &dyn pumpkin_inventory::screen_handler::InventoryPlayer, cursor: ItemStack) {
    if cursor.is_empty() {
        return;
    }
    let inventory = PluginView(player.get_inventory());
    let mut rest = cursor;
    for slot in 0..36 {
        let current = inventory.get_stack(slot);
        if current.is_empty() {
            inventory.set_stack(slot, rest);
            return;
        }
        if current.item.registry_key == rest.item.registry_key && current.patch.is_empty() && rest.patch.is_empty() {
            let max = rest.get_max_stack_size();
            let room = max.saturating_sub(current.item_count);
            if room > 0 {
                let moved = room.min(rest.item_count);
                let mut merged = current;
                merged.item_count += moved;
                inventory.set_stack(slot, merged);
                rest.item_count -= moved;
                if rest.item_count == 0 {
                    return;
                }
            }
        }
    }
    player.drop_item(to_server(rest), true);
}
