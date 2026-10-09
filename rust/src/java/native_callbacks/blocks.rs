//! Bulk block, block-state registry and biome access for the Java side.
//!
//! These calls exist so plugins that touch many blocks (WorldEdit and friends) can move whole
//! batches across the FFI boundary instead of paying one call per block.

use std::collections::HashMap;
use std::sync::Arc;

use pumpkin::net::java::chunk_data::CChunkData;
use pumpkin::world::World;
use pumpkin::world::BlockFlags;
use pumpkin_data::{Block, BlockStateId, chunk::Biome};
use pumpkin_util::math::{position::BlockPos, vector2::Vector2};
use pumpkin_util::version::JavaMinecraftVersion;
use pumpkin_world::chunk::{ChunkData, io::Dirtiable};

use crate::{
    java::native_callbacks::CALLBACK_CONTEXT,
    proto::patchbukkit::{
        common::{EmptyRequest, Uuid as ProtoUuid},
        world::{
            GetBiomesRequest, GetBiomesResponse, GetBlockStateRegistryResponse,
            GetBlockStatesRequest, GetBlockStatesResponse, RefreshChunksRequest,
            SetBiomesRequest, SetBlockStatesRequest, SetBlockStatesResponse,
        },
    },
};

/// Upper bound on blocks moved by one bulk call, so a bad request can't allocate unbounded memory.
const MAX_BULK_BLOCKS: usize = 1 << 22;

fn find_world(uuid: Option<&ProtoUuid>) -> Option<Arc<World>> {
    let ctx = CALLBACK_CONTEXT.get()?;
    let world_uuid = uuid::Uuid::parse_str(&uuid?.value).ok()?;
    let worlds = ctx.plugin_context.server.worlds.load_full();
    worlds
        .iter()
        .find(|w| w.uuid == world_uuid)
        .cloned()
        .or_else(|| worlds.first().cloned())
}

fn unpack_pos(packed: i64) -> BlockPos {
    BlockPos::from_long_for_version(packed, &JavaMinecraftVersion::V_26_3)
}

/// Returns the chunk, loading (or generating) it first when it isn't in memory, like vanilla's
/// `Level#getChunk` does for a block access.
fn load_chunk(world: &Arc<World>, pos: Vector2<i32>) -> Option<Arc<ChunkData>> {
    if let Some(chunk) = world.level.read_chunk_sync(&pos, Arc::clone) {
        return Some(chunk);
    }
    let ctx = CALLBACK_CONTEXT.get()?;
    let level = world.level.clone();
    Some(
        ctx.runtime
            .block_on(async move { level.get_or_fetch_chunk(pos, Arc::clone).await }),
    )
}

/// `minecraft:block[prop=value,...]`, the format `BlockData#getAsString` uses.
pub(crate) fn state_to_string(state_id: BlockStateId) -> String {
    let block = Block::from_state_id(state_id);
    let mut out = format!("minecraft:{}", block.name);
    if let Some(props) = block.properties(state_id) {
        let props = props.to_props();
        if !props.is_empty() {
            out.push('[');
            for (i, (k, v)) in props.iter().enumerate() {
                if i > 0 {
                    out.push(',');
                }
                out.push_str(k);
                out.push('=');
                out.push_str(v);
            }
            out.push(']');
        }
    }
    out
}

/// Bits of `default_block_flags`, mirrored in `BlockStateRegistry` on the Java side.
const BLOCK_FLAG_AIR: u32 = 1;
const BLOCK_FLAG_SOLID: u32 = 1 << 1;
const BLOCK_FLAG_OCCLUDING: u32 = 1 << 2;
const BLOCK_FLAG_BURNABLE: u32 = 1 << 3;

pub fn ffi_native_bridge_get_block_state_registry_impl(
    _request: EmptyRequest,
) -> Option<GetBlockStateRegistryResponse> {
    let mut states = Vec::new();
    let mut default_state_ids = Vec::new();
    let mut default_block_flags = Vec::new();
    let mut last_block = None;
    for state_id in (0..=u16::MAX).map_while(BlockStateId::new) {
        let block = Block::from_state_id(state_id);
        if last_block != Some(block.id) {
            last_block = Some(block.id);
            default_state_ids.push(u32::from(block.default_state.id.as_u16()));
            let state = block.default_state;
            let mut flags = 0;
            if state.is_air() {
                flags |= BLOCK_FLAG_AIR;
            }
            if state.is_solid() {
                flags |= BLOCK_FLAG_SOLID;
            }
            if state.is_solid_render() {
                flags |= BLOCK_FLAG_OCCLUDING;
            }
            if block.flammable.is_some() {
                flags |= BLOCK_FLAG_BURNABLE;
            }
            default_block_flags.push(flags);
        }
        states.push(state_to_string(state_id));
    }
    let biomes = (0..=u8::MAX)
        .filter_map(Biome::from_id)
        .map(|biome| format!("minecraft:{}", biome.registry_id))
        .collect();
    Some(GetBlockStateRegistryResponse {
        states,
        default_state_ids,
        default_block_flags,
        biomes,
    })
}

pub fn ffi_native_bridge_get_block_states_impl(
    request: GetBlockStatesRequest,
) -> Option<GetBlockStatesResponse> {
    let world = find_world(request.world_uuid.as_ref())?;
    let (min_x, min_y, min_z) = (request.min_x, request.min_y, request.min_z);
    let (max_x, max_y, max_z) = (request.max_x, request.max_y, request.max_z);
    if max_x < min_x || max_y < min_y || max_z < min_z {
        return Some(GetBlockStatesResponse::default());
    }
    let size_x = (i64::from(max_x) - i64::from(min_x) + 1) as usize;
    let size_y = (i64::from(max_y) - i64::from(min_y) + 1) as usize;
    let size_z = (i64::from(max_z) - i64::from(min_z) + 1) as usize;
    let volume = size_x.checked_mul(size_y)?.checked_mul(size_z)?;
    if volume > MAX_BULK_BLOCKS {
        tracing::warn!("GetBlockStates: refusing to read {volume} blocks in one call");
        return None;
    }

    let air = u32::from(Block::AIR.default_state.id.as_u16());
    let mut state_ids = vec![air; volume];
    let world_min_y = world.dimension.min_y;
    let world_max_y = world_min_y + world.dimension.height - 1;

    for chunk_x in (min_x >> 4)..=(max_x >> 4) {
        for chunk_z in (min_z >> 4)..=(max_z >> 4) {
            let Some(chunk) = load_chunk(&world, Vector2::new(chunk_x, chunk_z)) else {
                continue;
            };
            let x0 = min_x.max(chunk_x << 4);
            let x1 = max_x.min((chunk_x << 4) + 15);
            let z0 = min_z.max(chunk_z << 4);
            let z1 = max_z.min((chunk_z << 4) + 15);
            for y in min_y.max(world_min_y)..=max_y.min(world_max_y) {
                for z in z0..=z1 {
                    for x in x0..=x1 {
                        let Some(id) = chunk.section.get_block_absolute_y(
                            (x & 15) as usize,
                            y,
                            (z & 15) as usize,
                        ) else {
                            continue;
                        };
                        let index = ((y - min_y) as usize * size_z + (z - min_z) as usize)
                            * size_x
                            + (x - min_x) as usize;
                        state_ids[index] = u32::from(id.as_u16());
                    }
                }
            }
        }
    }

    Some(GetBlockStatesResponse { state_ids })
}

pub fn ffi_native_bridge_set_block_states_impl(
    request: SetBlockStatesRequest,
) -> Option<SetBlockStatesResponse> {
    let world = find_world(request.world_uuid.as_ref())?;
    if request.positions.len() != request.state_ids.len()
        || request.positions.len() > MAX_BULK_BLOCKS
    {
        tracing::warn!(
            "SetBlockStates: invalid batch ({} positions, {} states)",
            request.positions.len(),
            request.state_ids.len()
        );
        return None;
    }

    // Group by chunk so each chunk is looked up (and loaded if needed) once.
    let mut by_chunk: HashMap<Vector2<i32>, Vec<(BlockPos, BlockStateId)>> = HashMap::new();
    for (&packed, &state) in request.positions.iter().zip(&request.state_ids) {
        let Some(state_id) = u16::try_from(state).ok().and_then(BlockStateId::new) else {
            continue;
        };
        let pos = unpack_pos(packed);
        if !world.is_in_build_limit(pos) {
            continue;
        }
        by_chunk
            .entry(pos.chunk_position())
            .or_default()
            .push((pos, state_id));
    }

    // Like WorldEdit on vanilla, replacing a container doesn't spill its contents.
    let mut flags = BlockFlags::NOTIFY_LISTENERS | BlockFlags::SKIP_BLOCK_ENTITY_REPLACED_CALLBACK;
    if request.notify_neighbors {
        flags |= BlockFlags::NOTIFY_NEIGHBORS;
    }
    if request.skip_placement_callbacks {
        flags |= BlockFlags::SKIP_BLOCK_ADDED_CALLBACK;
    }
    let fast_path = !request.notify_neighbors && request.skip_placement_callbacks;

    let mut changed = 0;
    for (chunk_pos, updates) in by_chunk {
        let Some(chunk) = load_chunk(&world, chunk_pos) else {
            continue;
        };
        if !fast_path {
            for (pos, state_id) in updates {
                if world.set_block_state(&pos, state_id, flags) != state_id {
                    changed += 1;
                }
            }
            continue;
        }

        // Blocks that own a block entity are created in their placement callback, so they take
        // the regular path even when callbacks are otherwise skipped.
        let (with_entity, plain): (Vec<_>, Vec<_>) = updates
            .into_iter()
            .partition(|(_, id)| Block::from_state_id(*id).default_state.block_entity_type != u16::MAX);
        for (pos, state_id) in with_entity {
            if world.set_block_state(&pos, state_id, flags.difference(BlockFlags::SKIP_BLOCK_ADDED_CALLBACK))
                != state_id
            {
                changed += 1;
            }
        }

        let replaced = chunk.set_blocks_batch(
            plain
                .iter()
                .map(|(pos, id)| ((pos.0.x & 15) as usize, pos.0.y, (pos.0.z & 15) as usize, *id)),
        );
        let mut sent = Vec::with_capacity(replaced.len());
        for ((pos, new_id), (_, _, _, old_id)) in plain.iter().zip(replaced) {
            if old_id == *new_id {
                continue;
            }
            changed += 1;
            sent.push((*pos, *new_id));
            let old_block = Block::from_state_id(old_id);
            if old_block.default_state.block_entity_type != u16::MAX
                && old_block != Block::from_state_id(*new_id)
            {
                world.remove_block_entity(pos);
            }
            if request.update_lighting
                && pumpkin_world::lighting::LightEngine::has_different_light_properties(
                    old_id.to_state(),
                    new_id.to_state(),
                )
            {
                world.level.light_engine.update_lighting_at(&world.level, *pos);
            }
        }
        world.queue_block_updates(&sent);
    }

    Some(SetBlockStatesResponse { changed })
}

pub fn ffi_native_bridge_get_biomes_impl(request: GetBiomesRequest) -> Option<GetBiomesResponse> {
    let world = find_world(request.world_uuid.as_ref())?;
    if request.positions.len() > MAX_BULK_BLOCKS {
        return None;
    }
    let biomes = request
        .positions
        .iter()
        .map(|&packed| {
            let pos = unpack_pos(packed);
            let id = load_chunk(&world, pos.chunk_position()).and_then(|chunk| {
                chunk.section.get_rough_biome_absolute_y(
                    (pos.0.x & 15) as usize,
                    pos.0.y,
                    (pos.0.z & 15) as usize,
                )
            });
            let biome = id.and_then(Biome::from_id).unwrap_or(&Biome::PLAINS);
            format!("minecraft:{}", biome.registry_id)
        })
        .collect();
    Some(GetBiomesResponse { biomes })
}

pub fn ffi_native_bridge_set_biomes_impl(request: SetBiomesRequest) -> Option<()> {
    let world = find_world(request.world_uuid.as_ref())?;
    if request.positions.len() != request.biomes.len()
        || request.positions.len() > MAX_BULK_BLOCKS
    {
        return None;
    }
    let min_y = world.dimension.min_y;
    let max_y = min_y + world.dimension.height - 1;
    for (&packed, name) in request.positions.iter().zip(&request.biomes) {
        let Some(biome) = Biome::from_name(name.strip_prefix("minecraft:").unwrap_or(name))
        else {
            tracing::warn!("SetBiomes: unknown biome {name}");
            continue;
        };
        let pos = unpack_pos(packed);
        if pos.0.y < min_y || pos.0.y > max_y {
            continue;
        }
        let Some(chunk) = load_chunk(&world, pos.chunk_position()) else {
            continue;
        };
        // Biomes are stored per 4x4x4 cell.
        chunk.section.set_relative_biome(
            ((pos.0.x & 15) >> 2) as usize,
            ((pos.0.y - min_y) >> 2) as usize,
            ((pos.0.z & 15) >> 2) as usize,
            biome.id,
        );
        chunk.mark_dirty(true);
    }
    Some(())
}

/// Re-sends whole chunks to the players that can see them, like `World#refreshChunk`.
pub fn ffi_native_bridge_refresh_chunks_impl(request: RefreshChunksRequest) -> Option<()> {
    let world = find_world(request.world_uuid.as_ref())?;
    for coord in &request.chunks {
        let pos = Vector2::new(coord.x, coord.z);
        if let Some(chunk) = world.level.read_chunk_sync(&pos, Arc::clone) {
            world.broadcast_to_chunk(pos, &CChunkData(&chunk));
        }
    }
    Some(())
}
