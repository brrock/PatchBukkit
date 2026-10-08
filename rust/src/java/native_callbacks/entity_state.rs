use std::sync::{Arc, atomic::Ordering};

use pumpkin::entity::EntityBase;
use pumpkin_util::text::TextComponent;

use crate::{
    java::native_callbacks::CALLBACK_CONTEXT,
    proto::patchbukkit::{
        common::{Uuid, Vec3},
        entity::{
            EntityActionResponse, EntityPassengerRequest, EntityStateResponse,
            SetEntityStateRequest,
        },
    },
};

/// Looks up any entity, players included, by UUID across all worlds.
pub fn find_entity(uuid: Option<&Uuid>) -> Option<Arc<dyn EntityBase>> {
    let ctx = CALLBACK_CONTEXT.get()?;
    let uuid = uuid::Uuid::parse_str(&uuid?.value).ok()?;
    let server = &ctx.plugin_context.server;
    if let Some(player) = server.get_player_by_uuid(uuid) {
        return Some(player);
    }
    server
        .worlds
        .load()
        .iter()
        .find_map(|world| world.get_entity_by_uuid(uuid))
}

fn to_proto_uuid(entity: &dyn EntityBase) -> Uuid {
    Uuid {
        value: entity.get_entity().entity_uuid.to_string(),
    }
}

pub fn ffi_native_bridge_get_entity_state_impl(request: Uuid) -> Option<EntityStateResponse> {
    let Some(base) = find_entity(Some(&request)) else {
        return Some(EntityStateResponse::default());
    };
    let entity = base.get_entity();
    let bounding_box = entity.bounding_box.load();
    let dimensions = entity.entity_dimension.load();
    let dead = base
        .get_living_entity()
        .is_some_and(|living| living.dead.load(Ordering::Relaxed) || living.health.load() <= 0.0);

    Some(EntityStateResponse {
        found: true,
        fire_ticks: entity.fire_ticks.load(Ordering::Relaxed),
        visual_fire: entity.has_visual_fire.load(Ordering::Relaxed),
        freeze_ticks: entity.get_frozen_ticks(),
        in_water: entity.touching_water.load(Ordering::Relaxed),
        in_lava: entity.touching_lava.load(Ordering::Relaxed),
        in_powder_snow: entity.is_in_powder_snow.load(Ordering::Relaxed),
        invulnerable: entity.invulnerable.load(Ordering::Relaxed),
        silent: entity.is_silent(),
        no_gravity: entity.has_no_gravity(),
        glowing: entity.glowing.load(Ordering::Relaxed),
        invisible: entity.invisible.load(Ordering::Relaxed),
        custom_name_visible: entity.custom_name_visible.load(Ordering::Relaxed),
        custom_name: entity
            .custom_name
            .load()
            .as_ref()
            .as_ref()
            .and_then(|name| serde_json::to_string(name).ok()),
        width: f64::from(dimensions.width),
        height: f64::from(dimensions.height),
        bounding_box_min: Some(Vec3 {
            x: bounding_box.min.x,
            y: bounding_box.min.y,
            z: bounding_box.min.z,
        }),
        bounding_box_max: Some(Vec3 {
            x: bounding_box.max.x,
            y: bounding_box.max.y,
            z: bounding_box.max.z,
        }),
        ticks_lived: entity.age.load(Ordering::Relaxed),
        removed: entity.is_removed(),
        dead,
        portal_cooldown: entity.portal_cooldown.load(Ordering::Relaxed) as i32,
        scoreboard_tags: entity
            .scoreboard_tags
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .iter()
            .cloned()
            .collect(),
        passengers: entity
            .passengers
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .iter()
            .map(|passenger| to_proto_uuid(passenger.as_ref()))
            .collect(),
        vehicle: entity
            .get_vehicle()
            .map(|vehicle| to_proto_uuid(vehicle.as_ref())),
        no_physics: entity.no_physics.load(Ordering::Relaxed),
    })
}

pub fn ffi_native_bridge_set_entity_state_impl(request: SetEntityStateRequest) -> Option<()> {
    let base = find_entity(request.uuid.as_ref())?;
    let entity = base.get_entity();

    // Bukkit's setFireTicks only sets the remaining ticks, like vanilla setRemainingFireTicks.
    if let Some(ticks) = request.fire_ticks {
        entity.fire_ticks.store(ticks, Ordering::Relaxed);
    }
    if let Some(visual_fire) = request.visual_fire {
        entity.set_on_fire(visual_fire);
    }
    if let Some(ticks) = request.freeze_ticks {
        entity.set_frozen_ticks(ticks);
    }
    if let Some(invulnerable) = request.invulnerable {
        entity.set_invulnerable(invulnerable);
    }
    if let Some(silent) = request.silent {
        entity.set_silent(silent);
    }
    if let Some(no_gravity) = request.no_gravity {
        entity.set_has_no_gravity(no_gravity);
    }
    if let Some(glowing) = request.glowing {
        entity.set_glowing(glowing);
    }
    if let Some(invisible) = request.invisible {
        entity.set_invisible(invisible);
    }
    if let Some(name) = request.custom_name {
        if name.is_empty() {
            entity.custom_name.store(Arc::new(None));
            entity.set_synced_data(
                pumpkin_data::tracked_data::entity::DATA_CUSTOM_NAME,
                None::<TextComponent>,
            );
        } else if let Ok(component) = serde_json::from_str::<TextComponent>(&name) {
            entity.set_custom_name(component);
        }
    }
    if let Some(visible) = request.custom_name_visible {
        entity.set_custom_name_visible(visible);
    }
    if let Some(ticks) = request.ticks_lived {
        entity.age.store(ticks, Ordering::Relaxed);
    }
    if let Some(cooldown) = request.portal_cooldown {
        entity
            .portal_cooldown
            .store(cooldown.max(0) as u32, Ordering::Relaxed);
    }
    if let Some(no_physics) = request.no_physics {
        entity.no_physics.store(no_physics, Ordering::Relaxed);
    }
    for tag in &request.add_scoreboard_tags {
        entity.add_scoreboard_tag(tag);
    }
    for tag in &request.remove_scoreboard_tags {
        entity.remove_scoreboard_tag(tag);
    }
    Some(())
}

// Mounting and dismounting fire Pumpkin plugin events, which PatchBukkit forwards to the JVM.
// These callbacks run on the JVM thread, so the change is applied on the runtime instead of
// blocking here.
fn spawn(task: impl FnOnce() + Send + 'static) -> Option<()> {
    let ctx = CALLBACK_CONTEXT.get()?;
    ctx.runtime.spawn(async move { task() });
    Some(())
}

pub fn ffi_native_bridge_add_entity_passenger_impl(
    request: EntityPassengerRequest,
) -> Option<EntityActionResponse> {
    let vehicle = find_entity(request.vehicle.as_ref());
    let passenger = find_entity(request.passenger.as_ref());
    let (Some(vehicle), Some(passenger)) = (vehicle, passenger) else {
        return Some(EntityActionResponse { success: false });
    };
    let same = vehicle.get_entity().entity_id == passenger.get_entity().entity_id;
    if same || passenger.get_entity().has_vehicle() {
        return Some(EntityActionResponse { success: false });
    }
    spawn(move || {
        vehicle
            .get_entity()
            .add_passenger(vehicle.clone(), passenger);
    })?;
    Some(EntityActionResponse { success: true })
}

pub fn ffi_native_bridge_remove_entity_passenger_impl(
    request: EntityPassengerRequest,
) -> Option<EntityActionResponse> {
    let vehicle = find_entity(request.vehicle.as_ref());
    let passenger = find_entity(request.passenger.as_ref());
    let (Some(vehicle), Some(passenger)) = (vehicle, passenger) else {
        return Some(EntityActionResponse { success: false });
    };
    let passenger_id = passenger.get_entity().entity_id;
    if !vehicle.get_entity().has_passenger(passenger_id) {
        return Some(EntityActionResponse { success: false });
    }
    spawn(move || vehicle.get_entity().remove_passenger(passenger_id))?;
    Some(EntityActionResponse { success: true })
}

pub fn ffi_native_bridge_eject_entity_passengers_impl(
    request: Uuid,
) -> Option<EntityActionResponse> {
    let Some(vehicle) = find_entity(Some(&request)) else {
        return Some(EntityActionResponse { success: false });
    };
    let passenger_ids: Vec<i32> = vehicle
        .get_entity()
        .passengers
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .iter()
        .map(|passenger| passenger.get_entity().entity_id)
        .collect();
    if passenger_ids.is_empty() {
        return Some(EntityActionResponse { success: false });
    }
    spawn(move || {
        for id in passenger_ids {
            vehicle.get_entity().remove_passenger(id);
        }
    })?;
    Some(EntityActionResponse { success: true })
}

pub fn ffi_native_bridge_leave_entity_vehicle_impl(
    request: Uuid,
) -> Option<EntityActionResponse> {
    let Some(passenger) = find_entity(Some(&request)) else {
        return Some(EntityActionResponse { success: false });
    };
    let Some(vehicle) = passenger.get_entity().get_vehicle() else {
        return Some(EntityActionResponse { success: false });
    };
    let passenger_id = passenger.get_entity().entity_id;
    spawn(move || vehicle.get_entity().remove_passenger(passenger_id))?;
    Some(EntityActionResponse { success: true })
}

pub fn ffi_native_bridge_remove_entity_impl(request: Uuid) -> Option<()> {
    let entity = find_entity(Some(&request))?;
    // Players are removed by disconnecting them, never by discarding the entity.
    if entity.get_player().is_some() {
        return Some(());
    }
    spawn(move || entity.get_entity().remove())
}
