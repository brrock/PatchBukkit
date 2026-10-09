//! Sign text access for Bukkit's `Sign` block state and `SignChangeEvent`.

use std::sync::Arc;

use pumpkin::block::entities::BlockEntity;
use pumpkin::block::entities::hanging_sign::HangingSignBlockEntity;
use pumpkin::block::entities::sign::{SignBlockEntity, SignText};
use pumpkin::world::World;
use pumpkin_util::math::{position::BlockPos, vector3::Vector3};

use crate::java::native_callbacks::CALLBACK_CONTEXT;
use crate::proto::patchbukkit::world::{SignLinesRequest, SignLinesResponse};

/// Runs `f` on the front text of the sign at `pos`.
///
/// Block entities are created by the server, so `downcast_ref` from the plugin's
/// own copy of `pumpkin` fails (different `TypeId`). We identify the entity by its
/// resource location instead and reinterpret it as our identically laid out
/// type, the same assumption the plugin makes for `Player` and `ItemStack`.
fn with_front_text<R>(
    world: &World,
    pos: &BlockPos,
    f: impl FnOnce(&SignText) -> R,
) -> Option<(R, Arc<dyn BlockEntity>)> {
    let entity = world.get_block_entity(pos)?;
    let any = entity.as_any() as *const dyn std::any::Any;
    let result = match entity.resource_location() {
        SignBlockEntity::ID => {
            let sign = unsafe { &*any.cast::<SignBlockEntity>() };
            f(&sign.front_text)
        }
        HangingSignBlockEntity::ID => {
            let sign = unsafe { &*any.cast::<HangingSignBlockEntity>() };
            f(&sign.front_text)
        }
        _ => return None,
    };
    Some((result, entity))
}

/// Replaces the front lines of a sign and sends the change to clients.
pub fn write_sign_lines(world: &World, pos: &BlockPos, lines: &[String]) -> bool {
    let new: [Box<str>; 4] = std::array::from_fn(|i| {
        Box::<str>::from(lines.get(i).map_or("", String::as_str))
    });
    let Some(((), entity)) = with_front_text(world, pos, |text| {
        *text
            .messages
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner) = new.clone();
        *text
            .filtered_messages
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner) = new.clone();
    }) else {
        return false;
    };
    world.update_block_entity(&entity);
    true
}

/// Stops the player-edit lock a sign holds while its editor is open.
pub fn clear_sign_editor(world: &World, pos: &BlockPos) {
    let Some(entity) = world.get_block_entity(pos) else { return };
    let any = entity.as_any() as *const dyn std::any::Any;
    let editing = match entity.resource_location() {
        SignBlockEntity::ID => unsafe { &*any.cast::<SignBlockEntity>() }
            .currently_editing_player
            .clone(),
        HangingSignBlockEntity::ID => unsafe { &*any.cast::<HangingSignBlockEntity>() }
            .currently_editing_player
            .clone(),
        _ => return,
    };
    *editing.lock().unwrap_or_else(std::sync::PoisonError::into_inner) = None;
}

fn find_world(request: &SignLinesRequest) -> Option<Arc<World>> {
    let ctx = CALLBACK_CONTEXT.get()?;
    let worlds = ctx.plugin_context.server.worlds.load_full();
    let wanted = request
        .world_uuid
        .as_ref()
        .and_then(|u| uuid::Uuid::parse_str(&u.value).ok());
    worlds
        .iter()
        .find(|w| Some(w.uuid) == wanted)
        .or_else(|| worlds.first())
        .cloned()
}

fn pos_of(request: &SignLinesRequest) -> BlockPos {
    BlockPos(Vector3::new(request.x, request.y, request.z))
}

pub fn ffi_native_bridge_get_sign_lines_impl(request: SignLinesRequest) -> Option<SignLinesResponse> {
    let world = find_world(&request)?;
    let lines = with_front_text(&world, &pos_of(&request), |text| {
        text.messages
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .iter()
            .map(ToString::to_string)
            .collect::<Vec<_>>()
    });
    Some(match lines {
        Some((lines, _)) => SignLinesResponse { found: true, lines },
        None => SignLinesResponse::default(),
    })
}

pub fn ffi_native_bridge_set_sign_lines_impl(request: SignLinesRequest) -> Option<SignLinesResponse> {
    let world = find_world(&request)?;
    let found = write_sign_lines(&world, &pos_of(&request), &request.lines);
    Some(SignLinesResponse { found, lines: vec![] })
}
