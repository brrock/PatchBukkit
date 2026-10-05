use pumpkin_data::{
    Block,
    data_component::DataComponent,
    data_component_impl::{
        CompostableImpl, DataComponentImpl, ItemNameImpl, MaxDamageImpl, MaxStackSizeImpl, get,
    },
    item::Item,
};

use crate::proto::patchbukkit::registry::{
    BlockTypeData, GetBlockTypeDataRequest, GetItemTypeDataRequest, GetRegistryDataRequest,
    GetRegistryDataResponse, GetTagsRequest, GetTagsResponse, ItemTypeData, RegistryType,
    SoundEvent, SoundEventRegistryData, TagEntry, get_registry_data_response::Registry,
};
use pumpkin_data::tag::{RegistryKey, get_latest_map};

pub fn ffi_native_bridge_get_registry_data_impl(
    request: GetRegistryDataRequest,
) -> Option<GetRegistryDataResponse> {
    let registry = match request.registry {
        val if val == RegistryType::SoundEvent as i32 => {
            let sounds = pumpkin_data::sound::Sound::slice()
                .iter()
                .map(|s| SoundEvent {
                    id: *s as u32,
                    name: s.to_name().to_string(),
                })
                .collect::<Vec<_>>();
            Registry::SoundEvent(SoundEventRegistryData {
                sound_events: sounds,
            })
        }
        _ => unreachable!(),
    };

    Some(GetRegistryDataResponse {
        registry: Some(registry),
    })
}

fn namespaced(key: &str) -> String {
    if key.contains(':') {
        key.to_string()
    } else {
        format!("minecraft:{key}")
    }
}

/// Returns vanilla tag contents from Pumpkin's generated data. Nested tags are already
/// flattened there, so the values are plain registry keys.
pub fn ffi_native_bridge_get_tags_impl(request: GetTagsRequest) -> Option<GetTagsResponse> {
    let registry = request.registry.trim_start_matches("minecraft:");
    let Some(key) = RegistryKey::from_string(registry) else {
        return Some(GetTagsResponse { tags: Vec::new() });
    };
    let map = get_latest_map(key);
    let entry = |name: &str, tag: &pumpkin_data::tag::Tag| TagEntry {
        key: namespaced(name),
        values: tag.0.iter().map(|v| namespaced(v)).collect(),
    };

    let tags = if request.tag.is_empty() {
        map.entries().map(|(name, tag)| entry(name, tag)).collect()
    } else {
        let wanted = namespaced(&request.tag);
        map.get(wanted.as_str())
            .map(|tag| entry(&wanted, tag))
            .into_iter()
            .collect()
    };
    Some(GetTagsResponse { tags })
fn has_component(item: &Item, component: DataComponent) -> bool {
    item.components.iter().any(|(id, _)| *id == component)
}

fn item_component<T: DataComponentImpl + 'static>(item: &Item) -> Option<&T> {
    item.components
        .iter()
        .find(|(id, _)| *id == T::get_enum())
        .map(|(_, data)| get::<T>(*data))
}

pub fn ffi_native_bridge_get_item_type_data_impl(
    request: GetItemTypeDataRequest,
) -> Option<ItemTypeData> {
    let Some(item) = Item::from_registry_key(&request.key) else {
        return Some(ItemTypeData::default());
    };

    Some(ItemTypeData {
        found: true,
        max_stack_size: item_component::<MaxStackSizeImpl>(item).map_or(64, |c| i32::from(c.size)),
        max_durability: item_component::<MaxDamageImpl>(item).map_or(0, |c| c.max_damage),
        edible: has_component(item, DataComponent::Food),
        record: has_component(item, DataComponent::JukeboxPlayable),
        fuel: has_component(item, DataComponent::CookingFuel),
        compost_chance: item_component::<CompostableImpl>(item).map_or(0.0, |c| c.chance),
        translation_key: item_component::<ItemNameImpl>(item)
            .map(|c| c.name.to_string())
            .unwrap_or_default(),
    })
}

pub fn ffi_native_bridge_get_block_type_data_impl(
    request: GetBlockTypeDataRequest,
) -> Option<BlockTypeData> {
    let Some(block) = Block::from_name(&request.key) else {
        return Some(BlockTypeData::default());
    };
    let state = block.default_state;

    Some(BlockTypeData {
        found: true,
        hardness: block.hardness,
        blast_resistance: block.blast_resistance,
        slipperiness: block.slipperiness,
        air: state.is_air(),
        solid: state.is_solid(),
        // Bukkit "flammable" is vanilla ignitedByLava, "burnable" is having fire ignite odds.
        flammable: state.burnable(),
        burnable: block.flammable.as_ref().is_some_and(|f| f.burn_chance > 0),
        occluding: state.is_solid_block(),
        has_collision: !state.collision_shapes.is_empty(),
        has_item: block.item_id != 0,
        translation_key: format!("block.minecraft.{}", block.name),
    })
}
