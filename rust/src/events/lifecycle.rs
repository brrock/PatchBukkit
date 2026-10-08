use std::sync::Arc;

use pumpkin::{
    entity::player::Player,
    plugin::{
        BoxFuture, Context, EventHandler, EventPriority,
        player::{player_join::PlayerJoinEvent, player_leave::PlayerLeaveEvent},
    },
    server::Server,
};
use tokio::sync::{mpsc, oneshot};

use crate::java::jvm::commands::JvmCommand;

/// Keeps the Bukkit online-player list in sync with Pumpkin, independent of plugin listeners.
///
/// Pumpkin runs blocking handlers before non-blocking ones, and Bukkit listeners are registered
/// as blocking. The join handler is blocking and registered before any plugin, so the player is
/// online when `PlayerJoinEvent` reaches plugins. The leave handler is non-blocking, so the player
/// stays online until every `PlayerQuitEvent` listener has run, as on Paper.
pub struct PlayerLifecycleHandler {
    command_tx: mpsc::Sender<JvmCommand>,
}

impl PlayerLifecycleHandler {
    pub fn register(context: &Context, command_tx: mpsc::Sender<JvmCommand>) {
        let handler = Arc::new(Self { command_tx });
        context.register_event::<PlayerJoinEvent, _>(handler.clone(), EventPriority::Highest, true);
        context.register_event::<PlayerLeaveEvent, _>(handler, EventPriority::Lowest, false);
    }

    async fn send(&self, player: Arc<Player>, server: Arc<Server>, joined: bool) {
        crate::java::native_callbacks::utils::cache_player(player.clone());
        let (tx, rx) = oneshot::channel();
        if self
            .command_tx
            .send(JvmCommand::PlayerLifecycle {
                player,
                server,
                joined,
                respond_to: tx,
            })
            .await
            .is_ok()
        {
            let _ = rx.await;
        }
    }
}

impl EventHandler<PlayerJoinEvent> for PlayerLifecycleHandler {
    fn handle_blocking<'a>(
        &'a self,
        server: &'a Arc<Server>,
        event: &'a mut PlayerJoinEvent,
    ) -> BoxFuture<'a, ()> {
        Box::pin(self.send(event.player.clone(), server.clone(), true))
    }
}

impl EventHandler<PlayerLeaveEvent> for PlayerLifecycleHandler {
    fn handle<'a>(&'a self, server: &'a Arc<Server>, event: &'a PlayerLeaveEvent) -> BoxFuture<'a, ()> {
        let player = event.player.clone();
        Box::pin(async move {
            self.send(player.clone(), server.clone(), false).await;
            crate::java::native_callbacks::utils::uncache_player(&player);
        })
    }
}
