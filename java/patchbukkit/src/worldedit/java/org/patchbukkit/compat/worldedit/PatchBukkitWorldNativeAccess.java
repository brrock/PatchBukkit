package org.patchbukkit.compat.worldedit;

import com.sk89q.worldedit.internal.wna.WorldNativeAccess;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import org.enginehub.linbus.tree.LinCompoundTag;
import org.patchbukkit.world.BlockPositions;
import org.patchbukkit.world.BulkBlockAccess;

/**
 * Block writes for one world. Natives are Pumpkin state ids and packed block positions; there is
 * no chunk handle, so the chunk type is unused. Writes are queued in {@link BulkBlockAccess} and
 * Pumpkin applies the side effects (neighbour updates, placement callbacks, lighting) when the
 * batch is sent.
 */
final class PatchBukkitWorldNativeAccess implements WorldNativeAccess<Object, Integer, Long> {

    private final PatchBukkitWorldEditAdapter adapter;
    private final BulkBlockAccess access;
    private SideEffectSet sideEffectSet = SideEffectSet.defaults();

    PatchBukkitWorldNativeAccess(PatchBukkitWorldEditAdapter adapter, BulkBlockAccess access) {
        this.adapter = adapter;
        this.access = access;
    }

    @Override
    public <B extends BlockStateHolder<B>> boolean setBlock(BlockVector3 position, B block, SideEffectSet sideEffects) {
        int stateId = this.adapter.idFor(block.toImmutableState());
        if (stateId < 0) {
            return false;
        }
        // Block entity NBT (BaseBlock#getNbt) has no bridge yet, so only the state is placed.
        this.access.setStateId(position.x(), position.y(), position.z(), stateId, flagsFor(sideEffects));
        return true;
    }

    @Override
    public void applySideEffects(BlockVector3 position, BlockState previousType, SideEffectSet sideEffectSet) {
        // Pumpkin applies side effects while setting the block, so they can't be replayed for a
        // block that is already in place.
    }

    @Override
    public void setCurrentSideEffectSet(SideEffectSet sideEffectSet) {
        this.sideEffectSet = sideEffectSet;
    }

    @Override
    public Object getChunk(int x, int z) {
        return null;
    }

    @Override
    public Integer toNative(BlockState state) {
        return this.adapter.idFor(state);
    }

    @Override
    public Integer getBlockState(Object chunk, Long position) {
        return this.access.getStateId(
            BlockPositions.unpackX(position), BlockPositions.unpackY(position), BlockPositions.unpackZ(position));
    }

    @Override
    public Integer setBlockState(Object chunk, Long position, Integer state) {
        Integer old = getBlockState(chunk, position);
        this.access.setStateId(BlockPositions.unpackX(position), BlockPositions.unpackY(position),
            BlockPositions.unpackZ(position), state, flagsFor(this.sideEffectSet));
        return old;
    }

    @Override
    public Integer getValidBlockForPosition(Integer block, Long position) {
        return block;
    }

    @Override
    public Long getPosition(int x, int y, int z) {
        return BlockPositions.pack(x, y, z);
    }

    @Override
    public void updateLightingForBlock(Long position) {
    }

    @Override
    public boolean updateTileEntity(Long position, LinCompoundTag tag) {
        return false;
    }

    @Override
    public void notifyBlockUpdate(Object chunk, Long position, Integer oldState, Integer newState) {
    }

    @Override
    public boolean isChunkTicking(Object chunk) {
        return true;
    }

    @Override
    public void markBlockChanged(Object chunk, Long position) {
    }

    @Override
    public void notifyNeighbors(Long pos, Integer oldState, Integer newState) {
    }

    @Override
    public void updateNeighbors(Long pos, Integer oldState, Integer newState, int recursionLimit) {
    }

    @Override
    public void onBlockStateChange(Long pos, Integer oldState, Integer newState) {
    }

    private static int flagsFor(SideEffectSet sideEffects) {
        int flags = 0;
        if (sideEffects.shouldApply(SideEffect.NEIGHBORS)) {
            flags |= BulkBlockAccess.NOTIFY_NEIGHBORS;
        }
        if (!sideEffects.shouldApply(SideEffect.UPDATE)) {
            flags |= BulkBlockAccess.SKIP_PLACEMENT_CALLBACKS;
        }
        if (sideEffects.shouldApply(SideEffect.LIGHTING)) {
            flags |= BulkBlockAccess.UPDATE_LIGHTING;
        }
        return flags;
    }
}
