use pumpkin_util::text::TextComponent;

use crate::{
    java::native_callbacks::CALLBACK_CONTEXT, proto::patchbukkit::message::SendMessageRequest,
};

pub fn ffi_native_bridge_send_message_impl(request: SendMessageRequest) -> Option<()> {
    let ctx = CALLBACK_CONTEXT.get()?;
    let player_uuid = uuid::Uuid::parse_str(&request.uuid?.value).unwrap();

    let player = ctx.plugin_context.server.get_player_by_uuid(player_uuid);
    if let Some(player) = player {
        let text = if request.json {
            serde_json::from_str::<TextComponent>(&request.message).unwrap_or_else(|e| {
                tracing::warn!("SendMessage: invalid JSON text component ({e}), sending as legacy text");
                TextComponent::from_legacy_string(&request.message)
            })
        } else {
            TextComponent::from_legacy_string(&request.message)
        };
        player.send_system_message(&text);
    }

    Some(())
}
