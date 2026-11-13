package org.patchbukkit.world;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.bukkit.World;
import org.patchbukkit.bridge.BridgeUtils;
import patchbukkit.bridge.NativeBridgeFfi;
import patchbukkit.world.ChunkCoordProto;
import patchbukkit.world.GetBiomesRequest;
import patchbukkit.world.GetBiomesResponse;
import patchbukkit.world.GetBlockStatesRequest;
import patchbukkit.world.GetBlockStatesResponse;
import patchbukkit.world.RefreshChunksRequest;
import patchbukkit.world.SetBiomesRequest;
import patchbukkit.world.SetBlockStatesRequest;
import patchbukkit.world.SetBlockStatesResponse;

/**
 * Block access for plugins that read and write blocks in bulk (WorldEdit and the like).
 *
 * <p>Reads fetch a whole 16x16x16 section per native call and are cached briefly. Writes are
 * queued and sent to Pumpkin in batches: when the queue is full, when a read needs an uncached
 * section, on {@link #flush()}, or one tick after the first queued write. Queued writes are
 * visible to reads through this class right away.
 */
public final class BulkBlockAccess {

    /** Run neighbour updates for the placed blocks (vanilla update flag 1). */
    public static final int NOTIFY_NEIGHBORS = 1;
    /** Skip the new blocks' placement callbacks ({@code onPlace}). */
    public static final int SKIP_PLACEMENT_CALLBACKS = 1 << 1;
    /** Relight changed blocks. */
    public static final int UPDATE_LIGHTING = 1 << 2;

    private static final int SECTION_SIZE = 16;
    private static final int SECTION_VOLUME = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;
    private static final int MAX_PENDING = 1 << 16;
    private static final long CACHE_TTL_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final long FLUSH_DELAY_MILLIS = 50;

    private static final Map<UUID, BulkBlockAccess> BY_WORLD = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService FLUSHER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "patchbukkit-block-flush");
        thread.setDaemon(true);
        return thread;
    });

    private final patchbukkit.common.UUID worldId;
    private final Map<Long, int[]> sections = new HashMap<>();
    private long sectionsLoadedAt = System.nanoTime();

    private long[] pendingPositions = new long[1024];
    private int[] pendingStates = new int[1024];
    private int pendingCount;
    private int pendingFlags;
    private boolean flushScheduled;
    private final Map<Long, String> pendingBiomes = new LinkedHashMap<>();

    private BulkBlockAccess(UUID worldId) {
        this.worldId = BridgeUtils.convertUuid(worldId);
    }

    public static BulkBlockAccess of(World world) {
        return BY_WORLD.computeIfAbsent(world.getUID(), BulkBlockAccess::new);
    }

    /** Sends every queued change for every world. */
    public static void flushAll() {
        for (BulkBlockAccess access : BY_WORLD.values()) {
            access.flush();
        }
    }

    public synchronized int getStateId(int x, int y, int z) {
        long now = System.nanoTime();
        if (now - this.sectionsLoadedAt > CACHE_TTL_NANOS) {
            this.sections.clear();
            this.sectionsLoadedAt = now;
        }
        long key = sectionKey(x, y, z);
        int[] section = this.sections.get(key);
        if (section == null) {
            // The section comes from the server, so queued writes have to land there first.
            flush();
            section = loadSection(x >> 4, y >> 4, z >> 4);
            this.sections.put(key, section);
        }
        return section[sectionIndex(x, y, z)];
    }

    /** Queues a block change. {@code flags} is a combination of this class's flag constants. */
    public synchronized void setStateId(int x, int y, int z, int stateId, int flags) {
        if (this.pendingCount > 0 && flags != this.pendingFlags) {
            flush();
        }
        this.pendingFlags = flags;
        if (this.pendingCount == this.pendingPositions.length) {
            int grown = Math.min(this.pendingPositions.length * 2, MAX_PENDING);
            this.pendingPositions = Arrays.copyOf(this.pendingPositions, grown);
            this.pendingStates = Arrays.copyOf(this.pendingStates, grown);
        }
        this.pendingPositions[this.pendingCount] = BlockPositions.pack(x, y, z);
        this.pendingStates[this.pendingCount] = stateId;
        this.pendingCount++;

        int[] section = this.sections.get(sectionKey(x, y, z));
        if (section != null) {
            section[sectionIndex(x, y, z)] = stateId;
        }

        if (this.pendingCount >= MAX_PENDING) {
            flush();
        } else if (!this.flushScheduled) {
            this.flushScheduled = true;
            FLUSHER.schedule(this::flush, FLUSH_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Sends queued block and biome changes to the server.
     *
     * @return how many blocks actually changed
     */
    public synchronized int flush() {
        this.flushScheduled = false;
        flushBiomes();
        if (this.pendingCount == 0) {
            return 0;
        }
        SetBlockStatesRequest.Builder request = SetBlockStatesRequest.newBuilder()
            .setWorldUuid(this.worldId)
            .setNotifyNeighbors((this.pendingFlags & NOTIFY_NEIGHBORS) != 0)
            .setSkipPlacementCallbacks((this.pendingFlags & SKIP_PLACEMENT_CALLBACKS) != 0)
            .setUpdateLighting((this.pendingFlags & UPDATE_LIGHTING) != 0);
        for (int i = 0; i < this.pendingCount; i++) {
            request.addPositions(this.pendingPositions[i]);
            request.addStateIds(this.pendingStates[i]);
        }
        this.pendingCount = 0;
        SetBlockStatesResponse response = NativeBridgeFfi.setBlockStates(request.build());
        return response != null ? response.getChanged() : 0;
    }

    public synchronized String getBiome(int x, int y, int z) {
        flushBiomes();
        GetBiomesResponse response = NativeBridgeFfi.getBiomes(GetBiomesRequest.newBuilder()
            .setWorldUuid(this.worldId)
            .addPositions(BlockPositions.pack(x, y, z))
            .build());
        return response != null && response.getBiomesCount() == 1 ? response.getBiomes(0) : "minecraft:plains";
    }

    /** Queues a biome change. Biomes are stored per 4x4x4 cell, so one write per cell is kept. */
    public synchronized void setBiome(int x, int y, int z, String biomeKey) {
        this.pendingBiomes.put(BlockPositions.pack(x & ~3, y & ~3, z & ~3), biomeKey);
        if (this.pendingBiomes.size() >= MAX_PENDING) {
            flushBiomes();
        } else if (!this.flushScheduled) {
            this.flushScheduled = true;
            FLUSHER.schedule(this::flush, FLUSH_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    /** Re-sends the given chunks to players, after sending queued changes. */
    public void refreshChunks(Iterable<long[]> chunkCoords) {
        flush();
        RefreshChunksRequest.Builder request = RefreshChunksRequest.newBuilder().setWorldUuid(this.worldId);
        for (long[] coord : chunkCoords) {
            request.addChunks(ChunkCoordProto.newBuilder().setX((int) coord[0]).setZ((int) coord[1]));
        }
        if (request.getChunksCount() > 0) {
            NativeBridgeFfi.refreshChunks(request.build());
        }
    }

    private void flushBiomes() {
        if (this.pendingBiomes.isEmpty()) {
            return;
        }
        SetBiomesRequest.Builder request = SetBiomesRequest.newBuilder().setWorldUuid(this.worldId);
        for (Map.Entry<Long, String> entry : this.pendingBiomes.entrySet()) {
            request.addPositions(entry.getKey());
            request.addBiomes(entry.getValue());
        }
        this.pendingBiomes.clear();
        NativeBridgeFfi.setBiomes(request.build());
    }

    private int[] loadSection(int sectionX, int sectionY, int sectionZ) {
        int minX = sectionX << 4;
        int minY = sectionY << 4;
        int minZ = sectionZ << 4;
        GetBlockStatesResponse response = NativeBridgeFfi.getBlockStates(GetBlockStatesRequest.newBuilder()
            .setWorldUuid(this.worldId)
            .setMinX(minX).setMinY(minY).setMinZ(minZ)
            .setMaxX(minX + SECTION_SIZE - 1).setMaxY(minY + SECTION_SIZE - 1).setMaxZ(minZ + SECTION_SIZE - 1)
            .build());
        int[] section = new int[SECTION_VOLUME];
        if (response != null && response.getStateIdsCount() == SECTION_VOLUME) {
            for (int i = 0; i < SECTION_VOLUME; i++) {
                section[i] = response.getStateIds(i);
            }
        }
        return section;
    }

    private static long sectionKey(int x, int y, int z) {
        return BlockPositions.pack(x >> 4, y >> 4, z >> 4);
    }

    /** Matches the x-fastest, then z, then y order of {@code GetBlockStates}. */
    private static int sectionIndex(int x, int y, int z) {
        return ((y & 15) * SECTION_SIZE + (z & 15)) * SECTION_SIZE + (x & 15);
    }
}
