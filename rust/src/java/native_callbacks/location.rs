use std::sync::Arc;

use pumpkin::entity::EntityBase;
use crate::{
    java::native_callbacks::CALLBACK_CONTEXT,
    proto::patchbukkit::common::{Location, Uuid, Vec3, World},
};

#[repr(C)]
pub struct Vec3FFI {
    pub x: f64,
    pub y: f64,
    pub z: f64,
}

pub fn ffi_native_bridge_get_location_impl(entity_uuid: Uuid) -> Option<Location> {
    let ctx = CALLBACK_CONTEXT.get()?;
    let uuid = uuid::Uuid::parse_str(&entity_uuid.value).ok()?;

    let entity = ctx
        .plugin_context
        .server
        .worlds
        .load()
        .iter()
        .find_map(|world| {
                // Players live in the world's player list, not its entity list.
                world
                    .get_player_by_uuid(uuid)
                    .map(|player| player as Arc<dyn EntityBase>)
                    .or_else(|| world.get_entity_by_uuid(uuid))
            })?;

    let entity = entity.get_entity();
    let position = entity.pos.load();
    let world = entity.world.load().uuid;
    let yaw = entity.yaw.load();
    let pitch = entity.pitch.load();

    Some(Location {
        world: Some(World {
            uuid: Some(Uuid {
                value: world.to_string(),
            }),
        }),
        position: Some(Vec3 {
            x: position.x,
            y: position.y,
            z: position.z,
        }),
        yaw,
        pitch,
    })
}
