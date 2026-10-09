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

    // Players are not in World::entities, so look them up separately.
    let worlds = ctx.plugin_context.server.worlds.load();
    let player = worlds.iter().find_map(|world| world.get_player_by_uuid(uuid));
    let other = if player.is_none() {
        Some(worlds.iter().find_map(|world| world.get_entity_by_uuid(uuid))?)
    } else {
        None
    };
    let entity = match (&player, &other) {
        (Some(p), _) => &p.living_entity.entity,
        (None, Some(e)) => e.get_entity(),
        (None, None) => return None,
    };
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
