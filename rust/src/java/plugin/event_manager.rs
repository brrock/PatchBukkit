use std::sync::Arc;

use anyhow::Result;
use jni::Env;
use prost::Message;
use pumpkin::{entity::player::Player, server::Server};

use crate::{
    events::handler::JvmEventPayload,
    proto::patchbukkit::events::{FireEventResponse, event::Data},
};

pub struct EventManager;

impl Default for EventManager {
    fn default() -> Self {
        Self::new()
    }
}

impl EventManager {
    #[must_use]
    pub const fn new() -> Self {
        Self
    }

    pub fn fire_event(
        &self,
        env: &mut Env,
        payload: JvmEventPayload,
        plugin_name: String,
    ) -> Result<FireEventResponse> {
        if let Some(ref event) = payload.event.data
            && matches!(event, Data::PlayerLogin(_) | Data::PlayerJoin(_))
            && let Some(ref player) = payload.context.player
        {
            Self::register_player(env, player, &payload.context.server)?;
        }

        let bytes = payload.event.encode_to_vec();
        let j_bytes = env.byte_array_from_slice(&bytes)?;
        let plugin_name_jstr = env.new_string(&plugin_name)?;

        let result = env.call_static_method(
            jni::jni_str!("org/patchbukkit/events/PatchBukkitEventFactory"),
            jni::jni_str!("fireEventFromBytes"),
            jni::jni_sig!("([BLjava/lang/String;)[B"),
            &[(&j_bytes).into(), (&plugin_name_jstr).into()],
        )?;

        let obj = result.l()?;
        if obj.is_null() {
            return Ok(FireEventResponse {
                cancelled: false,
                data: Some(payload.event),
            });
        }

        let j_byte_array = jni::objects::JByteArray::cast_local(env, obj)?;
        let resp_vec = env.convert_byte_array(&j_byte_array)?;
        let response = FireEventResponse::decode(resp_vec.as_slice())?;
        Ok(response)
    }

    pub fn register_player(
        env: &mut Env,
        player: &Arc<Player>,
        _server: &Arc<Server>,
    ) -> Result<()> {
        let player_permission_level = player.permission_lvl.load();
        let is_op = player_permission_level > pumpkin_util::permission::PermissionLvl::Zero
            || _server
                .data
                .operator_config
                .try_read()
                .is_ok_and(|ops| ops.get_entry(&player.gameprofile.id).is_some());

        let uuid_jstr = env.new_string(player.gameprofile.id.to_string())?;
        let name_jstr = env.new_string(&player.gameprofile.name)?;
        let entity_id = player.living_entity.entity.entity_id;

        env.call_static_method(
            jni::jni_str!("org/patchbukkit/PatchBukkitServer"),
            jni::jni_str!("registerPlayer"),
            jni::jni_sig!("(Ljava/lang/String;Ljava/lang/String;ZI)V"),
            &[
                (&uuid_jstr).into(),
                (&name_jstr).into(),
                jni::objects::JValue::Bool(is_op),
                jni::objects::JValue::Int(entity_id),
            ],
        )?;

        Ok(())
    }
}
