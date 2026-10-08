use pumpkin_util::math::{vector2::Vector2, vector3::Vector3};
use std::sync::Arc;

use crate::{
    java::native_callbacks::CALLBACK_CONTEXT,
    proto::patchbukkit::{
        common::{EmptyRequest, Uuid as ProtoUuid},
        world::{
            ChunkCoordProto, ChunkRequest, ChunkStateResponse, GetBlockDropsRequest, GetBlockDropsResponse, CreateWorldExplosionRequest, EntitySummaryProto, GetBlockDataRequest,
            GetBlockDataResponse, GetForceLoadedChunksRequest, GetForceLoadedChunksResponse,
            GetWorldBorderRequest, GetWorldEntitiesRequest, GetWorldEntitiesResponse,
            GetWorldGamerulesRequest, GetWorldGamerulesResponse, GetWorldInfoRequest,
            GetWorldInfoResponse, GetWorldsResponse, PlayWorldSoundRequest, SaveWorldRequest,
            SetBlockDataRequest, SetChunkForceLoadedRequest, SetWorldBorderRequest,
            SetWorldDifficultyRequest, SetWorldGameruleRequest, SetWorldPvpRequest,
            SetWorldSpawnRequest, SetWorldTimeRequest, SetWorldWeatherRequest,
            SpawnParticleRequest, SpawnWorldEntityRequest, SpawnWorldEntityResponse,
            WorldBorderData,
        },
    },
};

pub fn ffi_native_bridge_get_block_data_impl(
    request: GetBlockDataRequest,
) -> Option<GetBlockDataResponse> {
    let ctx = CALLBACK_CONTEXT.get()?;
    let uuid_str = &request.world_uuid.as_ref()?.value;
    let world_uuid = uuid::Uuid::parse_str(uuid_str).ok()?;

    let worlds = ctx.plugin_context.server.worlds.load_full();
    let world = worlds
        .iter()
        .find(|w| w.uuid == world_uuid)
        .cloned()
        .or_else(|| worlds.first().cloned())?;
    let pos = pumpkin_util::math::position::BlockPos::new(request.x, request.y, request.z);

    let state_id = world.get_block_state(&pos).id;
    let block = pumpkin_data::Block::from_state_id(state_id);
    let key = block.name;
    let mut block_state = if key.starts_with("minecraft:") {
        key.to_string()
    } else {
        format!("minecraft:{key}")
    };

    if let Some(props) = block.properties(state_id) {
        let props = props.to_props();
        if !props.is_empty() {
            block_state.push('[');
            for (i, (k, v)) in props.iter().enumerate() {
                if i > 0 {
                    block_state.push(',');
                }
                block_state.push_str(k);
                block_state.push('=');
                block_state.push_str(v);
            }
            block_state.push(']');
        }
    }

    Some(GetBlockDataResponse { block_state })
}

pub fn ffi_native_bridge_set_block_data_impl(request: SetBlockDataRequest) -> Option<()> {
    let ctx = CALLBACK_CONTEXT.get()?;
    let uuid_str = &request.world_uuid.as_ref()?.value;
    let world_uuid = uuid::Uuid::parse_str(uuid_str).ok()?;

    let worlds = ctx.plugin_context.server.worlds.load_full();
    let world = worlds
        .iter()
        .find(|w| w.uuid == world_uuid)
        .cloned()
        .or_else(|| worlds.first().cloned())?;
    let pos = pumpkin_util::math::position::BlockPos::new(request.x, request.y, request.z);

    let block_state_str = request.block_state;
    let clean_key = block_state_str
        .split('[')
        .next()
        .unwrap_or(&block_state_str)
        .trim_start_matches("minecraft:");

    let state_id = if let Some(b) = pumpkin_data::Block::from_registry_key(clean_key) {
        match block_state_str.split_once('[') {
            Some((_, props_str)) => {
                let props: Vec<(&str, &str)> = props_str
                    .trim_end_matches(']')
                    .split(',')
                    .filter_map(|pair| pair.split_once('='))
                    .map(|(k, v)| (k.trim(), v.trim()))
                    .collect();
                if props.is_empty() {
                    b.default_state.id
                } else {
                    b.from_properties(&props).to_state_id(b)
                }
            }
            None => b.default_state.id,
        }
    } else {
        pumpkin_data::BlockStateId::new_or_air(0)
    };

    let flags = if request.apply_physics {
        pumpkin::world::BlockFlags::NOTIFY_ALL
    } else {
        pumpkin::world::BlockFlags::NOTIFY_LISTENERS
    };
    world.set_block_state(&pos, state_id, flags);

    Some(())
}

pub fn ffi_native_bridge_spawn_particle_impl(_request: SpawnParticleRequest) -> Option<()> {
    Some(())
}

pub fn ffi_native_bridge_get_worlds_impl(_request: EmptyRequest) -> Option<GetWorldsResponse> {
    let ctx = CALLBACK_CONTEXT.get()?;
    let worlds = ctx.plugin_context.server.worlds.load_full();
    let world_uuids = worlds
        .iter()
        .map(|w| ProtoUuid {
            value: w.uuid.to_string(),
        })
        .collect();

    Some(GetWorldsResponse { world_uuids })
}

pub fn ffi_native_bridge_get_world_border_impl(
    request: GetWorldBorderRequest,
) -> Option<WorldBorderData> {
    let ctx = CALLBACK_CONTEXT.get()?;
    let uuid_str = &request.world_uuid.as_ref()?.value;
    let world_uuid = uuid::Uuid::parse_str(uuid_str).ok()?;

    let worlds = ctx.plugin_context.server.worlds.load_full();
    let world = worlds
        .iter()
        .find(|w| w.uuid == world_uuid)
        .cloned()
        .or_else(|| worlds.first().cloned())?;

    let wb = world.worldborder.try_lock().ok()?;

    Some(WorldBorderData {
        center_x: wb.center_x,
        center_z: wb.center_z,
        size: wb.old_diameter,
        target_size: wb.new_diameter,
        speed: wb.speed,
        warning_time: wb.warning_time,
        warning_blocks: wb.warning_blocks,
        damage_per_block: wb.damage_per_block as f64,
        damage_buffer: wb.buffer as f64,
        max_center_coordinate: wb.portal_teleport_boundary,
    })
}

pub fn ffi_native_bridge_set_world_border_impl(request: SetWorldBorderRequest) -> Option<()> {
    let ctx = CALLBACK_CONTEXT.get()?;
    let uuid_str = &request.world_uuid.as_ref()?.value;
    let world_uuid = uuid::Uuid::parse_str(uuid_str).ok()?;
    let border_data = request.border?;

    let worlds = ctx.plugin_context.server.worlds.load_full();
    let world = worlds
        .iter()
        .find(|w| w.uuid == world_uuid)
        .cloned()
        .or_else(|| worlds.first().cloned())?;

    {
        let mut wb = world
            .worldborder
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        wb.center_x = border_data.center_x;
        wb.center_z = border_data.center_z;
        wb.old_diameter = border_data.size;
        wb.new_diameter = border_data.target_size;
        wb.speed = border_data.speed;
        wb.warning_time = border_data.warning_time;
        wb.warning_blocks = border_data.warning_blocks;
        wb.damage_per_block = border_data.damage_per_block as f32;
        wb.buffer = border_data.damage_buffer as f32;
        wb.portal_teleport_boundary = border_data.max_center_coordinate;
    }

    Some(())
}

pub fn ffi_native_bridge_get_world_info_impl(
    request: GetWorldInfoRequest,
) -> Option<GetWorldInfoResponse> {
    let ctx = CALLBACK_CONTEXT.get()?;
    let uuid_str = &request.world_uuid.as_ref()?.value;
    let world_uuid = uuid::Uuid::parse_str(uuid_str).ok()?;

    let worlds = ctx.plugin_context.server.worlds.load_full();
    let world = worlds
        .iter()
        .find(|w| w.uuid == world_uuid)
        .cloned()
        .or_else(|| worlds.first().cloned())?;

    let min_height = world.dimension.min_y;
    let height = world.dimension.height;
    let max_height = min_height + height;
    let logical_height = world.dimension.logical_height;
    let sea_level = world.sea_level;
    let dimension = world.dimension.minecraft_name.to_string();

    let level_data = world.level_info.load();
    let name = level_data.level_name.clone();
    let seed = 0i64;
    let difficulty = format!("{:?}", level_data.difficulty);
    let hardcore = false;
    let spawn_x = level_data.spawn_x;
    let spawn_y = level_data.spawn_y;
    let spawn_z = level_data.spawn_z;
    let spawn_angle = level_data.spawn_yaw;

    let (time, full_time) = if let Ok(lt) = world.level_time.try_lock() {
        (lt.time_of_day, lt.world_age)
    } else {
        (0, 0)
    };

    let (is_storm, is_thundering, weather_duration, thunder_duration, clear_weather_duration) =
        if let Ok(w) = world.weather.try_lock() {
            (
                w.raining,
                w.thundering,
                w.rain_time,
                w.thunder_time,
                w.clear_weather_time,
            )
        } else {
            (false, false, 0, 0, 0)
        };

    let pvp = ctx.plugin_context.server.advanced_config.pvp.enabled;

    Some(GetWorldInfoResponse {
        min_height,
        max_height,
        height,
        seed,
        name,
        dimension,
        sea_level,
        logical_height,
        difficulty,
        hardcore,
        pvp,
        spawn_x,
        spawn_y,
        spawn_z,
        spawn_angle,
        time,
        full_time,
        is_storm,
        is_thundering,
        weather_duration,
        thunder_duration,
        clear_weather_duration,
    })
}

pub fn ffi_native_bridge_set_world_time_impl(request: SetWorldTimeRequest) -> Option<()> {
    let ctx = CALLBACK_CONTEXT.get()?;
    let uuid_str = &request.world_uuid.as_ref()?.value;
    let world_uuid = uuid::Uuid::parse_str(uuid_str).ok()?;

    let worlds = ctx.plugin_context.server.worlds.load_full();
    let world = worlds
        .iter()
        .find(|w| w.uuid == world_uuid)
        .cloned()
        .or_else(|| worlds.first().cloned())?;

    if let Ok(mut lt) = world.level_time.try_lock() {
        if request.time >= 0 {
            lt.time_of_day = request.time;
        }
        if request.full_time >= 0 {
            lt.world_age = request.full_time;
        }
    }
    Some(())
}

pub fn ffi_native_bridge_set_world_weather_impl(request: SetWorldWeatherRequest) -> Option<()> {
    let ctx = CALLBACK_CONTEXT.get()?;
    let uuid_str = &request.world_uuid.as_ref()?.value;
    let world_uuid = uuid::Uuid::parse_str(uuid_str).ok()?;

    let worlds = ctx.plugin_context.server.worlds.load_full();
    let world = worlds
        .iter()
        .find(|w| w.uuid == world_uuid)
        .cloned()
        .or_else(|| worlds.first().cloned())?;

    if let Ok(mut w) = world.weather.try_lock() {
        w.raining = request.storm;
        w.thundering = request.thundering;
        if request.weather_duration > 0 {
            w.rain_time = request.weather_duration;
        }
        if request.thunder_duration > 0 {
            w.thunder_time = request.thunder_duration;
        }
        if request.clear_weather_duration > 0 {
            w.clear_weather_time = request.clear_weather_duration;
        }
    }
    Some(())
}

pub fn ffi_native_bridge_set_world_spawn_impl(request: SetWorldSpawnRequest) -> Option<()> {
    let ctx = CALLBACK_CONTEXT.get()?;
    let uuid_str = &request.world_uuid.as_ref()?.value;
    let world_uuid = uuid::Uuid::parse_str(uuid_str).ok()?;

    let worlds = ctx.plugin_context.server.worlds.load_full();
    let world = worlds
        .iter()
        .find(|w| w.uuid == world_uuid)
        .cloned()
        .or_else(|| worlds.first().cloned())?;

    let mut level_data = (**world.level_info.load()).clone();
    level_data.spawn_x = request.x;
    level_data.spawn_y = request.y;
    level_data.spawn_z = request.z;
    level_data.spawn_yaw = request.angle;
    world.level_info.store(Arc::new(level_data));

    Some(())
}

pub fn ffi_native_bridge_set_world_difficulty_impl(
    request: SetWorldDifficultyRequest,
) -> Option<()> {
    let ctx = CALLBACK_CONTEXT.get()?;
    let uuid_str = &request.world_uuid.as_ref()?.value;
    let world_uuid = uuid::Uuid::parse_str(uuid_str).ok()?;

    let worlds = ctx.plugin_context.server.worlds.load_full();
    let world = worlds
        .iter()
        .find(|w| w.uuid == world_uuid)
        .cloned()
        .or_else(|| worlds.first().cloned())?;

    let diff = match request.difficulty.to_uppercase().as_str() {
        "PEACEFUL" => pumpkin_util::Difficulty::Peaceful,
        "EASY" => pumpkin_util::Difficulty::Easy,
        "HARD" => pumpkin_util::Difficulty::Hard,
        _ => pumpkin_util::Difficulty::Normal,
    };

    let mut level_data = (**world.level_info.load()).clone();
    level_data.difficulty = diff;
    world.level_info.store(Arc::new(level_data));

    Some(())
}

pub fn ffi_native_bridge_set_world_pvp_impl(_request: SetWorldPvpRequest) -> Option<()> {
    Some(())
}

pub fn ffi_native_bridge_set_world_gamerule_impl(_request: SetWorldGameruleRequest) -> Option<()> {
    Some(())
}

pub fn ffi_native_bridge_get_world_gamerules_impl(
    _request: GetWorldGamerulesRequest,
) -> Option<GetWorldGamerulesResponse> {
    let mut gamerules = std::collections::HashMap::new();
    gamerules.insert("doDaylightCycle".to_string(), "true".to_string());
    gamerules.insert("doMobSpawning".to_string(), "true".to_string());
    gamerules.insert("doFireTick".to_string(), "true".to_string());
    gamerules.insert("keepInventory".to_string(), "false".to_string());
    gamerules.insert("mobGriefing".to_string(), "true".to_string());
    gamerules.insert("doWeatherCycle".to_string(), "true".to_string());
    Some(GetWorldGamerulesResponse { gamerules })
}

pub fn ffi_native_bridge_get_world_entities_impl(
    request: GetWorldEntitiesRequest,
) -> Option<GetWorldEntitiesResponse> {
    let ctx = CALLBACK_CONTEXT.get()?;
    let uuid_str = &request.world_uuid.as_ref()?.value;
    let world_uuid = uuid::Uuid::parse_str(uuid_str).ok()?;

    let worlds = ctx.plugin_context.server.worlds.load_full();
    let world = worlds
        .iter()
        .find(|w| w.uuid == world_uuid)
        .cloned()
        .or_else(|| worlds.first().cloned())?;

    let mut entities = Vec::new();
    for p in world.players.load().iter() {
        let pos = p.living_entity.entity.pos.load();
        entities.push(EntitySummaryProto {
            uuid: Some(ProtoUuid {
                value: p.gameprofile.id.to_string(),
            }),
            entity_type: "PLAYER".to_string(),
            x: pos.x,
            y: pos.y,
            z: pos.z,
            yaw: p.living_entity.entity.yaw.load(),
            pitch: p.living_entity.entity.pitch.load(),
            is_player: true,
            custom_name: p.gameprofile.name.clone(),
            entity_id: p.living_entity.entity.entity_id,
        });
    }

    for e in world.entities.load().iter() {
        let base = e.get_entity();
        let pos = base.pos.load();
        entities.push(EntitySummaryProto {
            uuid: Some(ProtoUuid {
                value: base.entity_uuid.to_string(),
            }),
            entity_type: format!("{:?}", base.entity_type),
            x: pos.x,
            y: pos.y,
            z: pos.z,
            yaw: base.yaw.load(),
            pitch: base.pitch.load(),
            is_player: false,
            custom_name: String::new(),
            entity_id: base.entity_id,
        });
    }

    Some(GetWorldEntitiesResponse { entities })
}

pub fn ffi_native_bridge_spawn_world_entity_impl(
    request: SpawnWorldEntityRequest,
) -> Option<SpawnWorldEntityResponse> {
    let ctx = CALLBACK_CONTEXT.get()?;
    let uuid_str = &request.world_uuid.as_ref()?.value;
    let world_uuid = uuid::Uuid::parse_str(uuid_str).ok()?;

    let worlds = ctx.plugin_context.server.worlds.load_full();
    let world = worlds
        .iter()
        .find(|w| w.uuid == world_uuid)
        .cloned()
        .or_else(|| worlds.first().cloned())?;

    let new_uuid = uuid::Uuid::new_v4();
    let pos = Vector3::new(request.x, request.y, request.z);

    let entity_type: &'static pumpkin_data::entity::EntityType =
        match request.entity_type.to_uppercase().as_str() {
            "LIGHTNING_BOLT" | "LIGHTNING" => &pumpkin_data::entity::EntityType::LIGHTNING_BOLT,
            "ITEM" | "DROPPED_ITEM" => &pumpkin_data::entity::EntityType::ITEM,
            "ZOMBIE" => &pumpkin_data::entity::EntityType::ZOMBIE,
            "SKELETON" => &pumpkin_data::entity::EntityType::SKELETON,
            "CREEPER" => &pumpkin_data::entity::EntityType::CREEPER,
            "COW" => &pumpkin_data::entity::EntityType::COW,
            "PIG" => &pumpkin_data::entity::EntityType::PIG,
            "SHEEP" => &pumpkin_data::entity::EntityType::SHEEP,
            _ => &pumpkin_data::entity::EntityType::PIG,
        };
    let entity = pumpkin::entity::r#type::from_type(entity_type, pos, &world, new_uuid);
    let entity_id = entity.get_entity().entity_id;

    let w = world.clone();
    ctx.runtime.spawn(async move {
        w.spawn_entity(entity);
    });

    Some(SpawnWorldEntityResponse {
        entity_uuid: Some(ProtoUuid {
            value: new_uuid.to_string(),
        }),
        success: true,
        entity_id,
    })
}

pub fn ffi_native_bridge_create_world_explosion_impl(
    request: CreateWorldExplosionRequest,
) -> Option<()> {
    let ctx = CALLBACK_CONTEXT.get()?;
    let uuid_str = &request.world_uuid.as_ref()?.value;
    let world_uuid = uuid::Uuid::parse_str(uuid_str).ok()?;

    let worlds = ctx.plugin_context.server.worlds.load_full();
    let world = worlds
        .iter()
        .find(|w| w.uuid == world_uuid)
        .cloned()
        .or_else(|| worlds.first().cloned())?;

    let pos = Vector3::new(request.x, request.y, request.z);
    let power = request.power;
    let interaction = if request.break_blocks {
        pumpkin::world::ExplosionInteraction::Block
    } else {
        pumpkin::world::ExplosionInteraction::None
    };

    world.explode(pos, power, interaction);

    Some(())
}

pub fn ffi_native_bridge_play_world_sound_impl(request: PlayWorldSoundRequest) -> Option<()> {
    let ctx = CALLBACK_CONTEXT.get()?;
    let uuid_str = &request.world_uuid.as_ref()?.value;
    let world_uuid = uuid::Uuid::parse_str(uuid_str).ok()?;

    let worlds = ctx.plugin_context.server.worlds.load_full();
    let world = worlds
        .iter()
        .find(|w| w.uuid == world_uuid)
        .cloned()
        .or_else(|| worlds.first().cloned())?;

    let pos = Vector3::new(request.x, request.y, request.z);
    let sound_name = request.sound;
    if let Some(sound) = pumpkin_data::sound::Sound::from_name(&sound_name) {
        world.play_sound_raw(
            sound as u16,
            pumpkin_data::sound::SoundCategory::Master,
            &pos,
            request.volume,
            request.pitch,
        );
    }

    Some(())
}

pub fn ffi_native_bridge_set_chunk_force_loaded_impl(
    request: SetChunkForceLoadedRequest,
) -> Option<()> {
    let ctx = CALLBACK_CONTEXT.get()?;
    let uuid_str = &request.world_uuid.as_ref()?.value;
    let world_uuid = uuid::Uuid::parse_str(uuid_str).ok()?;

    let worlds = ctx.plugin_context.server.worlds.load_full();
    let world = worlds
        .iter()
        .find(|w| w.uuid == world_uuid)
        .cloned()
        .or_else(|| worlds.first().cloned())?;

    let coord = pumpkin_util::math::vector2::Vector2::new(request.x, request.z);
    if let Ok(mut forced) = world.forced_chunks.lock() {
        if request.forced {
            forced.insert(coord);
        } else {
            forced.remove(&coord);
        }
    }

    Some(())
}

pub fn ffi_native_bridge_get_force_loaded_chunks_impl(
    request: GetForceLoadedChunksRequest,
) -> Option<GetForceLoadedChunksResponse> {
    let ctx = CALLBACK_CONTEXT.get()?;
    let uuid_str = &request.world_uuid.as_ref()?.value;
    let world_uuid = uuid::Uuid::parse_str(uuid_str).ok()?;

    let worlds = ctx.plugin_context.server.worlds.load_full();
    let world = worlds
        .iter()
        .find(|w| w.uuid == world_uuid)
        .cloned()
        .or_else(|| worlds.first().cloned())?;

    let mut chunks = Vec::new();
    if let Ok(forced) = world.forced_chunks.lock() {
        for coord in forced.iter() {
            chunks.push(ChunkCoordProto {
                x: coord.x,
                z: coord.y,
            });
        }
    }

    Some(GetForceLoadedChunksResponse { chunks })
}

pub fn ffi_native_bridge_save_world_impl(request: SaveWorldRequest) -> Option<()> {
    let ctx = CALLBACK_CONTEXT.get()?;
    let uuid_str = &request.world_uuid.as_ref()?.value;
    let world_uuid = uuid::Uuid::parse_str(uuid_str).ok()?;

    let worlds = ctx.plugin_context.server.worlds.load_full();
    let world = worlds
        .iter()
        .find(|w| w.uuid == world_uuid)
        .cloned()
        .or_else(|| worlds.first().cloned())?;

    ctx.runtime.spawn(async move {
        let _ = world.save().await;
    });

    Some(())
}

/// How long a Java thread may wait for Pumpkin to load or generate one chunk.
const CHUNK_LOAD_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(60);

fn find_world_exact(world_uuid: Option<&ProtoUuid>) -> Option<Arc<pumpkin::world::World>> {
    let ctx = CALLBACK_CONTEXT.get()?;
    let world_uuid = uuid::Uuid::parse_str(&world_uuid?.value).ok()?;
    ctx.plugin_context
        .server
        .worlds
        .load_full()
        .iter()
        .find(|w| w.uuid == world_uuid)
        .cloned()
}

/// Runs `fut` on the PatchBukkit runtime and blocks the calling (Java) thread until it
/// finishes. A std channel is used because the JVM worker thread is itself inside a
/// `block_on`, where tokio's own blocking helpers would panic.
fn block_on_runtime<T: Send + 'static>(
    fut: impl std::future::Future<Output = T> + Send + 'static,
    timeout: std::time::Duration,
) -> Option<T> {
    let ctx = CALLBACK_CONTEXT.get()?;
    let (tx, rx) = std::sync::mpsc::sync_channel(1);
    ctx.runtime.spawn(async move {
        let _ = tx.send(fut.await);
    });
    rx.recv_timeout(timeout).ok()
}

/// Whether a full chunk exists in the region files of `world`.
async fn chunk_exists_on_disk(world: &pumpkin::world::World, pos: Vector2<i32>) -> bool {
    use pumpkin_world::chunk::io::{FileIO, LoadedData};
    let level = &world.level;
    let (tx, mut rx) = tokio::sync::mpsc::channel(1);
    let positions = [pos];
    let fetch = level.chunk_saver.fetch_chunks(&level.level_folder, &positions, tx);
    let (_, data) = tokio::join!(fetch, rx.recv());
    matches!(data, Some(LoadedData::Loaded(_)))
}

pub fn ffi_native_bridge_get_chunk_state_impl(request: ChunkRequest) -> Option<ChunkStateResponse> {
    let world = find_world_exact(request.world_uuid.as_ref())?;
    let pos = Vector2::new(request.x, request.z);
    if world.level.is_chunk_loaded(&pos) {
        return Some(ChunkStateResponse {
            loaded: true,
            generated: true,
        });
    }
    if !request.check_disk {
        return Some(ChunkStateResponse {
            loaded: false,
            generated: false,
        });
    }
    let generated = block_on_runtime(
        async move { chunk_exists_on_disk(&world, pos).await },
        CHUNK_LOAD_TIMEOUT,
    )?;
    Some(ChunkStateResponse {
        loaded: false,
        generated,
    })
}

/// Loads a chunk through Pumpkin's chunk system, generating it when `generate` is set and
/// it is not on disk yet. Blocks the calling Java thread until the chunk is available.
pub fn ffi_native_bridge_load_chunk_impl(request: ChunkRequest) -> Option<ChunkStateResponse> {
    let world = find_world_exact(request.world_uuid.as_ref())?;
    let pos = Vector2::new(request.x, request.z);
    let generate = request.generate;
    block_on_runtime(
        async move {
            if !generate
                && !world.level.is_chunk_loaded(&pos)
                && !chunk_exists_on_disk(&world, pos).await
            {
                return ChunkStateResponse {
                    loaded: false,
                    generated: false,
                };
            }
            world.level.get_or_fetch_chunk(pos, |_| ()).await;
            ChunkStateResponse {
                loaded: world.level.is_chunk_loaded(&pos),
                generated: true,
            }
        },
        CHUNK_LOAD_TIMEOUT,
    )
}

/// Rolls the block's loot table like Paper's CraftBlock#getDrops: with a tool that cannot
/// harvest the block there are no drops.
pub fn ffi_native_bridge_get_block_drops_impl(
    request: GetBlockDropsRequest,
) -> Option<GetBlockDropsResponse> {
    use pumpkin_data::item_stack::ItemStack;
    let world = find_world_exact(request.world_uuid.as_ref())?;
    let pos = pumpkin_util::math::position::BlockPos::new(request.x, request.y, request.z);
    let state = world.get_block_state(&pos);
    let block = pumpkin_data::Block::from_state_id(state.id);

    let tool = request.tool.as_ref().and_then(|tool| {
        let item = pumpkin_data::item::Item::from_registry_key(&tool.r#type)?;
        Some(ItemStack::new(tool.amount.clamp(1, 99) as u8, item))
    });
    let empty = Some(GetBlockDropsResponse { drops: Vec::new() });
    if let Some(tool) = &tool
        && state.tool_required()
        && !tool.is_correct_for_drops(block)
    {
        return empty;
    }
    let Some(loot_table) = world.get_loot_table(&format!("minecraft:blocks/{}", block.name)) else {
        return empty;
    };
    let params = pumpkin::world::loot::LootContextParameters {
        block_state: Some(state),
        tool,
        position: Some(pos.to_f64()),
        ..Default::default()
    };
    let drops = pumpkin::world::loot::generate_loot_from_handle(&loot_table, rand::random(), &params)
        .into_iter()
        .filter(|stack| stack.item_count > 0)
        .map(|stack| crate::proto::patchbukkit::itemstack::ItemStack {
            r#type: format!("minecraft:{}", stack.item.registry_key),
            amount: u32::from(stack.item_count),
        })
        .collect();
    Some(GetBlockDropsResponse { drops })
}
