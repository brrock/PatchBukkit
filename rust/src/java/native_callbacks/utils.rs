use super::CALLBACK_CONTEXT;
use crate::proto::patchbukkit::common::Uuid as ProtoUuid;
use pumpkin::entity::player::Player;
use std::collections::HashMap;
use std::ffi::{CStr, c_char};
use std::sync::{Arc, RwLock};

static PLAYER_HANDLE_CACHE: RwLock<Option<HashMap<uuid::Uuid, Arc<Player>>>> = RwLock::new(None);

#[must_use]
pub fn get_string(str_ptr: *const c_char) -> String {
    unsafe { CStr::from_ptr(str_ptr).to_string_lossy().into_owned() }
}

pub fn cache_player(player: Arc<Player>) {
    let player_uuid = player.gameprofile.id;
    if let Ok(mut write_guard) = PLAYER_HANDLE_CACHE.write() {
        let cache = write_guard.get_or_insert_with(HashMap::new);
        cache.insert(player_uuid, player);
    }
}

pub fn uncache_player(player: &Arc<Player>) {
    if let Ok(mut write_guard) = PLAYER_HANDLE_CACHE.write()
        && let Some(cache) = write_guard.as_mut()
        && cache
            .get(&player.gameprofile.id)
            .is_some_and(|cached| Arc::ptr_eq(cached, player))
    {
        cache.remove(&player.gameprofile.id);
    }
}

pub fn with_player<F, R>(proto_uuid: Option<&ProtoUuid>, f: F) -> Option<R>
where
    F: FnOnce(Arc<Player>) -> R,
{
    let ctx = CALLBACK_CONTEXT.get()?;
    let uuid_str = &proto_uuid?.value;
    let player_uuid = uuid::Uuid::parse_str(uuid_str).ok()?;

    // Clone out of the cache and release the lock before running `f`: `f` may fire a
    // Pumpkin event whose handler caches the player, which takes the write lock.
    let cached = PLAYER_HANDLE_CACHE
        .read()
        .ok()
        .and_then(|guard| guard.as_ref()?.get(&player_uuid).cloned());
    if let Some(player) = cached {
        return Some(f(player));
    }

    let player = ctx.plugin_context.server.get_player_by_uuid(player_uuid)?;
    if let Ok(mut write_guard) = PLAYER_HANDLE_CACHE.write() {
        let cache = write_guard.get_or_insert_with(HashMap::new);
        cache.insert(player_uuid, player.clone());
    }

    Some(f(player))
}
