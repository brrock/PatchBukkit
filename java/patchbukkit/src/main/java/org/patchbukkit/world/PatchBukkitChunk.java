package org.patchbukkit.world;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.key.Key;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.patchbukkit.persistence.PatchBukkitPersistentDataContainer;

@SuppressWarnings("deprecation")
public class PatchBukkitChunk implements Chunk {
    private final World world;
    private final int x;
    private final int z;
    private final Set<Plugin> tickets = ConcurrentHashMap.newKeySet();
    private long inhabitedTime = 0;
    private final org.bukkit.persistence.PersistentDataContainer pdc = new PatchBukkitPersistentDataContainer();

    public PatchBukkitChunk(World world, int x, int z) {
        this.world = world;
        this.x = x;
        this.z = z;
    }

    @Override
    public int getX() {
        return this.x;
    }

    @Override
    public int getZ() {
        return this.z;
    }

    @Override
    public @NotNull World getWorld() {
        return this.world;
    }

    @Override
    public @NotNull Block getBlock(int x, int y, int z) {
        return this.world.getBlockAt((this.x << 4) + (x & 0xF), y, (this.z << 4) + (z & 0xF));
    }

    @Override
    public @NotNull ChunkSnapshot getChunkSnapshot() {
        return getChunkSnapshot(true, false, false);
    }

    @Override
    public @NotNull ChunkSnapshot getChunkSnapshot(boolean includeMaxBlocky, boolean includeBiome, boolean includeBiomeTempRain) {
        return getChunkSnapshot(includeMaxBlocky, includeBiome, includeBiomeTempRain, false);
    }

    @Override
    public @NotNull ChunkSnapshot getChunkSnapshot(boolean includeMaxBlocky, boolean includeBiome, boolean includeBiomeTempRain, boolean includeBiome3D) {
        final String worldName = this.world.getName();
        final Key worldKey = this.world.getKey();
        final int chunkX = this.x;
        final int chunkZ = this.z;
        final long captureFullTime = this.world.getFullTime();

        return new ChunkSnapshot() {
            @Override
            public int getX() {
                return chunkX;
            }

            @Override
            public int getZ() {
                return chunkZ;
            }

            @Override
            public @NotNull String getWorldName() {
                return worldName;
            }

            @Override
            public @NotNull Key getWorldKey() {
                return worldKey;
            }

            @Override
            public @NotNull Material getBlockType(int x, int y, int z) {
                return getBlockData(x, y, z).getMaterial();
            }

            @Override
            public @NotNull BlockData getBlockData(int x, int y, int z) {
                return PatchBukkitChunk.this.getBlock(x, y, z).getBlockData();
            }

            @Override
            public int getData(int x, int y, int z) {
                return 0;
            }

            @Override
            public int getBlockSkyLight(int x, int y, int z) {
                return 15;
            }

            @Override
            public int getBlockEmittedLight(int x, int y, int z) {
                return 0;
            }

            @Override
            public int getHighestBlockYAt(int x, int z) {
                return PatchBukkitChunk.this.world.getHighestBlockYAt((chunkX << 4) + x, (chunkZ << 4) + z);
            }

            @Override
            public @NotNull Biome getBiome(int x, int z) {
                return PatchBukkitChunk.this.world.getBiome((chunkX << 4) + x, 64, (chunkZ << 4) + z);
            }

            @Override
            public @NotNull Biome getBiome(int x, int y, int z) {
                return PatchBukkitChunk.this.world.getBiome((chunkX << 4) + x, y, (chunkZ << 4) + z);
            }

            @Override
            public double getRawBiomeTemperature(int x, int z) {
                return 0.8;
            }

            @Override
            public double getRawBiomeTemperature(int x, int y, int z) {
                return 0.8;
            }

            @Override
            public long getCaptureFullTime() {
                return captureFullTime;
            }

            @Override
            public boolean isSectionEmpty(int sy) {
                return false;
            }

            @Override
            public boolean contains(@NotNull BlockData block) {
                return false;
            }

            @Override
            public boolean contains(@NotNull Biome biome) {
                return false;
            }
        };
    }

    @Override
    public boolean isEntitiesLoaded() {
        return true;
    }

    @Override
    public @NotNull Entity[] getEntities() {
        List<Entity> list = new ArrayList<>();
        int minX = this.x << 4;
        int maxX = minX + 15;
        int minZ = this.z << 4;
        int maxZ = minZ + 15;

        for (Entity e : this.world.getEntities()) {
            Location loc = e.getLocation();
            int ex = loc.getBlockX();
            int ez = loc.getBlockZ();
            if (ex >= minX && ex <= maxX && ez >= minZ && ez <= maxZ) {
                list.add(e);
            }
        }
        return list.toArray(new Entity[0]);
    }

    @Override
    public @NotNull BlockState[] getTileEntities() {
        return getTileEntities(true);
    }

    @Override
    public @NotNull BlockState[] getTileEntities(boolean useSnapshot) {
        return new BlockState[0];
    }

    @Override
    public boolean isGenerated() {
        return this.world.isChunkGenerated(this.x, this.z);
    }

    @Override
    public boolean isLoaded() {
        return this.world.isChunkLoaded(this.x, this.z);
    }

    @Override
    public boolean load(boolean generate) {
        return this.world.loadChunk(this.x, this.z, generate);
    }

    @Override
    public boolean load() {
        return load(true);
    }

    @Override
    public boolean unload(boolean save) {
        return true;
    }

    @Override
    public boolean unload() {
        return true;
    }

    @Override
    public boolean isSlimeChunk() {
        long seed = this.world.getSeed();
        java.util.Random rnd = new java.util.Random(
            seed +
            (long) (this.x * this.x * 4987142) +
            (long) (this.x * 5947611) +
            (long) (this.z * this.z) * 4392871L +
            (long) (this.z * 389711) ^ 987234911L
        );
        return rnd.nextInt(10) == 0;
    }

    @Override
    public boolean isForceLoaded() {
        return this.world.isChunkForceLoaded(this.x, this.z);
    }

    @Override
    public void setForceLoaded(boolean progress) {
        this.world.setChunkForceLoaded(this.x, this.z, progress);
    }

    @Override
    public boolean addPluginChunkTicket(@NotNull Plugin plugin) {
        return this.world.addPluginChunkTicket(this.x, this.z, plugin);
    }

    @Override
    public boolean removePluginChunkTicket(@NotNull Plugin plugin) {
        return this.world.removePluginChunkTicket(this.x, this.z, plugin);
    }

    @Override
    public @NotNull Collection<Plugin> getPluginChunkTickets() {
        return this.world.getPluginChunkTickets(this.x, this.z);
    }

    @Override
    public long getInhabitedTime() {
        return this.inhabitedTime;
    }

    @Override
    public void setInhabitedTime(long ticks) {
        this.inhabitedTime = ticks;
    }

    @Override
    public boolean contains(@NotNull BlockData block) {
        return false;
    }

    @Override
    public @NotNull Collection<Player> getPlayersSeeingChunk() {
        List<Player> seeing = new ArrayList<>();
        int minX = (this.x - 10) << 4;
        int maxX = (this.x + 11) << 4;
        int minZ = (this.z - 10) << 4;
        int maxZ = (this.z + 11) << 4;

        for (Player p : this.world.getPlayers()) {
            Location loc = p.getLocation();
            int px = loc.getBlockX();
            int pz = loc.getBlockZ();
            if (px >= minX && px <= maxX && pz >= minZ && pz <= maxZ) {
                seeing.add(p);
            }
        }
        return seeing;
    }

    @Override
    public @NotNull Collection<org.bukkit.generator.structure.GeneratedStructure> getStructures() {
        return Collections.emptyList();
    }

    @Override
    public @NotNull Collection<org.bukkit.generator.structure.GeneratedStructure> getStructures(@NotNull org.bukkit.generator.structure.Structure structure) {
        return Collections.emptyList();
    }

    @Override
    public @NotNull LoadLevel getLoadLevel() {
        return LoadLevel.ENTITY_TICKING;
    }

    @Override
    public boolean contains(@NotNull Biome biome) {
        return false;
    }

    @Override
    public @NotNull org.bukkit.persistence.PersistentDataContainer getPersistentDataContainer() {
        return this.pdc;
    }

    @Override
    public @NotNull Collection<BlockState> getTileEntities(@NotNull java.util.function.Predicate<? super Block> blockPredicate, boolean useSnapshot) {
        return Collections.emptyList();
    }
}
