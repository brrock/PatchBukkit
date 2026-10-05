use crate::proto::patchbukkit::registry::{
    GetRegistryDataRequest, GetRegistryDataResponse, GetTagsRequest, GetTagsResponse,
    RegistryType, SoundEvent, SoundEventRegistryData, TagEntry,
    get_registry_data_response::Registry,
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
}
