use std::sync::Arc;

use pumpkin::entity::EntityBase;
use pumpkin_data::sound::{Sound, SoundCategory};
use pumpkin_protocol::{IdOr, java::client::play::CEntitySoundEffect};
use pumpkin_util::math::vector3::Vector3;
use rand::{RngExt, rng};

use crate::{
    java::native_callbacks::CALLBACK_CONTEXT,
    proto::patchbukkit::sound::{PlayerEntityPlaySoundRequest, PlayerPlaySoundRequest},
};

pub fn ffi_native_bridge_player_entity_play_sound_impl(
    request: PlayerEntityPlaySoundRequest,
) -> Option<()> {
    tracing::error!("PlayerEntityPlaySoundRequest sent");
    let ctx = CALLBACK_CONTEXT.get()?;
    let player_uuid = uuid::Uuid::parse_str(&request.player_uuid?.value).ok()?;
    let entity_uuid = uuid::Uuid::parse_str(&request.entity_uuid?.value).ok()?;

    let player = ctx.plugin_context.server.get_player_by_uuid(player_uuid)?;

    let sound = request.sound?;
    let pumpkin_sound = Sound::from_name(&sound.name)?;
    let category = SoundCategory::from_name(&sound.category.to_lowercase())?;

    let seed = rng().random::<i64>();

    ctx.runtime.spawn(async move {
        let entity = ctx
            .plugin_context
            .server
            .worlds
            .load()
            .iter()
            .find_map(|world| {
                // Players live in the world's player list, not its entity list.
                world
                    .get_player_by_uuid(entity_uuid)
                    .map(|player| player as Arc<dyn EntityBase>)
                    .or_else(|| world.get_entity_by_uuid(entity_uuid))
            })?;

        let entity = entity.get_entity();

        player
            .send_client_packet(&CEntitySoundEffect::new(
                IdOr::Id(pumpkin_sound as u16),
                category,
                entity.entity_id.into(),
                request.volume,
                request.pitch,
                seed,
            ))
            .await;

        Some(())
    });

    Some(())
}

pub fn ffi_native_bridge_player_play_sound_impl(request: PlayerPlaySoundRequest) -> Option<()> {
    let ctx = CALLBACK_CONTEXT.get()?;
    let player_uuid = uuid::Uuid::parse_str(&request.player_uuid?.value).ok()?;
    let player = ctx.plugin_context.server.get_player_by_uuid(player_uuid)?;

    let sound = request.sound?;
    let pumpkin_sound = Sound::from_name(&sound.name)?;
    let category = SoundCategory::from_name(&sound.category.to_lowercase())?;

    let seed = match request.seed {
        Some(seed) => seed,
        None => rng().random::<i64>(),
    };

    let position = request.location?.position?;
    let position: Vector3<f64> = Vector3::new(position.x, position.y, position.z);

    player.play_sound(
        pumpkin_sound as u16,
        category,
        &position,
        request.volume,
        request.pitch,
        seed,
    );

    Some(())
}
