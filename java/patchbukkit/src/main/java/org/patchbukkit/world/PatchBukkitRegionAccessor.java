package org.patchbukkit.world;

import io.papermc.paper.block.fluid.FluidData;
import io.papermc.paper.world.MoonPhase;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jspecify.annotations.NonNull;
import org.patchbukkit.bridge.BridgeUtils;
import org.patchbukkit.entity.PatchBukkitEntity;
import patchbukkit.bridge.NativeBridgeFfi;
import patchbukkit.world.GetBlockDataRequest;
import patchbukkit.world.SetBlockDataRequest;
import patchbukkit.world.SpawnWorldEntityRequest;

@SuppressWarnings({ "deprecation", "unchecked" })
public class PatchBukkitRegionAccessor implements RegionAccessor {

    @Override
    public @NotNull Biome getBiome(int x, int y, int z) {
        return Biome.PLAINS;
    }

    @Override
    public @NotNull Biome getComputedBiome(int x, int y, int z) {
        return getBiome(x, y, z);
    }

    @Override
    public void setBiome(int x, int y, int z, @NotNull Biome biome) {
    }

    @Override
    public @NotNull BlockState getBlockState(int x, int y, int z) {
        if (this instanceof PatchBukkitWorld world) {
            return new PatchBukkitBlockState(world.getBlockAt(x, y, z));
        }
        return new PatchBukkitBlockState(new PatchBukkitBlock(null, x, y, z));
    }

    @Override
    public @NotNull FluidData getFluidData(int x, int y, int z) {
        return new FluidData() {
            @Override public @NotNull org.bukkit.Fluid getFluidType() { return org.bukkit.Fluid.EMPTY; }
            @Override public @NotNull FluidData clone() { return this; }
            @Override public @NotNull org.bukkit.util.Vector computeFlowDirection(@NotNull Location location) { return new org.bukkit.util.Vector(0, 0, 0); }
            @Override public int getLevel() { return 0; }
            @Override public float computeHeight(@NotNull Location location) { return 0.0f; }
            @Override public boolean isSource() { return false; }
        };
    }

    @Override
    public @NotNull BlockData getBlockData(int x, int y, int z) {
        if (this instanceof PatchBukkitWorld world) {
            var request = GetBlockDataRequest.newBuilder()
                .setWorldUuid(BridgeUtils.convertUuid(world.getUID()))
                .setX(x)
                .setY(y)
                .setZ(z)
                .build();
            try {
                var response = NativeBridgeFfi.getBlockData(request);
                if (response != null && !response.getBlockState().isEmpty()) {
                    return Bukkit.createBlockData(response.getBlockState());
                }
            } catch (Throwable ignored) {}
        }
        return Bukkit.createBlockData(Material.AIR);
    }

    @Override
    public @NotNull Material getType(int x, int y, int z) {
        return getBlockData(x, y, z).getMaterial();
    }

    @Override
    public void setBlockData(int x, int y, int z, @NotNull BlockData blockData) {
        if (this instanceof PatchBukkitWorld world) {
            var request = SetBlockDataRequest.newBuilder()
                .setWorldUuid(BridgeUtils.convertUuid(world.getUID()))
                .setX(x)
                .setY(y)
                .setZ(z)
                .setBlockState(blockData.getAsString())
                .setApplyPhysics(true)
                .build();
            try {
                NativeBridgeFfi.setBlockData(request);
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public boolean generateTree(
        @NotNull Location location,
        @NotNull Random random,
        @NotNull TreeType type
    ) {
        return true;
    }

    @Override
    public boolean generateTree(
        @NotNull Location location,
        @NotNull Random random,
        @NotNull TreeType type,
        @Nullable Consumer<? super BlockState> stateConsumer
    ) {
        return true;
    }

    @Override
    public boolean generateTree(
        @NotNull Location location,
        @NotNull Random random,
        @NotNull TreeType type,
        @Nullable Predicate<? super BlockState> statePredicate
    ) {
        return true;
    }

    @Override
    public @NotNull Entity spawnEntity(
        @NotNull Location loc,
        @NotNull EntityType type,
        boolean randomizeData
    ) {
        UUID worldUuid = (this instanceof PatchBukkitWorld w) ? w.getUID() : UUID.randomUUID();
        UUID entityUuid = UUID.randomUUID();
        int entityId = -1;
        try {
            var res = NativeBridgeFfi.spawnWorldEntity(SpawnWorldEntityRequest.newBuilder()
                .setWorldUuid(BridgeUtils.convertUuid(worldUuid))
                .setEntityType(type.name())
                .setX(loc.getX())
                .setY(loc.getY())
                .setZ(loc.getZ())
                .setYaw(loc.getYaw())
                .setPitch(loc.getPitch())
                .build());
            if (res != null) {
                if (res.hasEntityUuid()) {
                    entityUuid = UUID.fromString(res.getEntityUuid().getValue());
                }
                if (res.getEntityId() > 0) {
                    entityId = res.getEntityId();
                }
            }
        } catch (Throwable ignored) {}

        Entity entity = PatchBukkitEntity.create(entityUuid, type, loc, entityId);
        return entity;
    }

    @Override
    public @NotNull List<Entity> getEntities() {
        if (this instanceof PatchBukkitWorld world) {
            return world.getEntities();
        }
        return Collections.emptyList();
    }

    @Override
    public @NotNull List<LivingEntity> getLivingEntities() {
        if (this instanceof PatchBukkitWorld world) {
            return world.getLivingEntities();
        }
        return Collections.emptyList();
    }

    @Override
    public @NotNull <T extends Entity> Collection<T> getEntitiesByClass(
        @NotNull Class<T> cls
    ) {
        List<T> list = new ArrayList<>();
        for (Entity e : getEntities()) {
            if (cls.isInstance(e)) {
                list.add((T) e);
            }
        }
        return list;
    }

    @Override
    public @NotNull Collection<Entity> getEntitiesByClasses(
        @NonNull @NotNull Class<?>... classes
    ) {
        List<Entity> list = new ArrayList<>();
        for (Entity e : getEntities()) {
            for (Class<?> c : classes) {
                if (c.isInstance(e)) {
                    list.add(e);
                    break;
                }
            }
        }
        return list;
    }

    @Override
    public @NonNull <T extends Entity> T createEntity(
        @NotNull Location location,
        @NotNull Class<T> clazz
    ) {
        // Prefer the type whose class is exactly the one asked for (Zombie, not Drowned).
        EntityType type = null;
        for (EntityType et : EntityType.values()) {
            if (et.getEntityClass() == clazz) {
                type = et;
                break;
            }
        }
        if (type == null) {
            type = EntityType.PIG;
            for (EntityType et : EntityType.values()) {
                if (et.getEntityClass() != null && clazz.isAssignableFrom(et.getEntityClass())) {
                    type = et;
                    break;
                }
            }
        }
        return (T) PatchBukkitEntity.create(UUID.randomUUID(), type, location);
    }

    @Override
    public @NonNull <T extends Entity> T spawn(
        @NotNull Location location,
        @NotNull Class<T> clazz,
        @Nullable Consumer<? super T> function,
        CreatureSpawnEvent.@NotNull SpawnReason reason
    ) throws IllegalArgumentException {
        T entity = createEntity(location, clazz);
        if (!(entity instanceof org.patchbukkit.entity.PatchBukkitExperienceOrb)
            && !(entity instanceof org.bukkit.entity.Player)) {
            // Put the entity in the world; the server answers with its uuid and id.
            Entity spawned = spawnEntity(location, entity.getType(), true);
            if (clazz.isInstance(spawned)) {
                entity = clazz.cast(spawned);
            }
        }
        if (function != null) {
            function.accept(entity);
        }
        if (entity instanceof org.patchbukkit.entity.PatchBukkitExperienceOrb orb) {
            // The amount is usually set right after spawn() returns, so send the orb afterwards.
            UUID worldUuid = (this instanceof PatchBukkitWorld w) ? w.getUID() : UUID.randomUUID();
            Location at = location.clone();
            org.patchbukkit.scheduler.PatchBukkitScheduler.runOnNextDrain(() -> {
                if (orb.getExperience() <= 0) {
                    return;
                }
                try {
                    NativeBridgeFfi.spawnWorldEntity(SpawnWorldEntityRequest.newBuilder()
                        .setWorldUuid(BridgeUtils.convertUuid(worldUuid))
                        .setEntityType("EXPERIENCE_ORB")
                        .setX(at.getX())
                        .setY(at.getY())
                        .setZ(at.getZ())
                        .setExperience(orb.getExperience())
                        .build());
                } catch (Throwable ignored) {}
            });
        }
        return addEntity(entity);
    }

    @Override
    public @NonNull <T extends Entity> T spawn(
        @NotNull Location location,
        @NotNull Class<T> clazz,
        boolean randomizeData,
        @Nullable Consumer<? super T> function
    ) throws IllegalArgumentException {
        return spawn(location, clazz, function, CreatureSpawnEvent.SpawnReason.DEFAULT);
    }

    @Override
    public int getHighestBlockYAt(int x, int z) {
        return getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
    }

    @Override
    public int getHighestBlockYAt(@NotNull Location location) {
        return getHighestBlockYAt(location.getBlockX(), location.getBlockZ());
    }

    @Override
    public int getHighestBlockYAt(int x, int z, @NotNull HeightMap heightMap) {
        int maxY = (this instanceof PatchBukkitWorld w) ? w.getMaxHeight() - 1 : 255;
        int minY = (this instanceof PatchBukkitWorld w) ? w.getMinHeight() : -64;
        for (int y = maxY; y >= minY; y--) {
            Material type = getType(x, y, z);
            if (type != Material.AIR && type != Material.VOID_AIR && type != Material.CAVE_AIR) {
                return y;
            }
        }
        return minY;
    }

    @Override
    public int getHighestBlockYAt(
        @NotNull Location location,
        @NotNull HeightMap heightMap
    ) {
        return getHighestBlockYAt(location.getBlockX(), location.getBlockZ(), heightMap);
    }

    @Override
    public @NonNull <T extends Entity> T addEntity(@NonNull T entity) {
        if (this instanceof PatchBukkitWorld world) {
            world.registerEntity(entity);
        }
        return entity;
    }

    @Override
    public @NotNull MoonPhase getMoonPhase() {
        long day = 0;
        if (this instanceof PatchBukkitWorld w) {
            day = w.getFullTime() / 24000L;
        }
        int phaseIndex = (int) (day % 8);
        return MoonPhase.values()[Math.abs(phaseIndex)];
    }

    @Override
    public @NotNull NamespacedKey getKey() {
        if (this instanceof PatchBukkitWorld world) {
            return world.getKey();
        }
        return NamespacedKey.minecraft("overworld");
    }

    @Override
    public boolean lineOfSightExists(
        @NotNull Location from,
        @NotNull Location to
    ) {
        return true;
    }

    @Override
    public boolean hasCollisionsIn(@NotNull BoundingBox boundingBox) {
        return false;
    }

    @Override
    public @Unmodifiable Set<FeatureFlag> getFeatureFlags() {
        return Collections.singleton(FeatureFlag.VANILLA);
    }
}
