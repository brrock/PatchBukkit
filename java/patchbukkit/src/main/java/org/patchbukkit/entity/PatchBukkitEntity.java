package org.patchbukkit.entity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.EntityEffect;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.PistonMoveReaction;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.SpawnCategory;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.permissions.PermissibleBase;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.permissions.ServerOperator;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.patchbukkit.bridge.BridgeUtils;
import org.patchbukkit.world.PatchBukkitWorld;

import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentType.Valued;
import io.papermc.paper.entity.LookAnchor;
import io.papermc.paper.entity.TeleportFlag;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import net.kyori.adventure.sound.Sound.Source;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.util.TriState;
import patchbukkit.bridge.NativeBridgeFfi;
import patchbukkit.entity.EntityPassengerRequest;
import patchbukkit.entity.EntityStateResponse;
import patchbukkit.entity.SetEntityStateRequest;
import patchbukkit.entity.SetEntityVelocityRequest;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.patchbukkit.persistence.PatchBukkitPersistentDataContainer;
import patchbukkit.entity.TeleportEntityRequest;

public class PatchBukkitEntity implements Entity {

    @Override
    public boolean isInWaterOrRainOrBubbleColumn() {
        return false;
    }

    @Override
    public boolean isInWaterOrBubbleColumn() {
        return false;
    }

    protected final UUID uuid;
    protected final String name;
    private PermissibleBase perm;
    private boolean visibleByDefault = true;
    private final Map<String, List<MetadataValue>> metadataMap = new HashMap<>();

    private PermissibleBase getPermissible() {
        if (this.perm == null) {
            this.perm = new PermissibleBase(this);
        }
        return this.perm;
    }

    // Vanilla Entity#getTicksRequiredToFreeze and Entity.MAX_TAGS.
    private static final int TICKS_REQUIRED_TO_FREEZE = 140;
    private static final int MAX_SCOREBOARD_TAGS = 1024;

    private final PersistentDataContainer persistentDataContainer = new PatchBukkitPersistentDataContainer();
    private @Nullable EntityDamageEvent lastDamageCause;

    /** Reads this entity's live state from Pumpkin. */
    protected EntityStateResponse state() {
        EntityStateResponse state = NativeBridgeFfi.getEntityState(BridgeUtils.convertUuid(this.uuid));
        return state != null ? state : EntityStateResponse.getDefaultInstance();
    }

    /** Applies the fields set by {@code edit} to this entity in Pumpkin. */
    protected void updateState(java.util.function.Consumer<SetEntityStateRequest.Builder> edit) {
        SetEntityStateRequest.Builder builder = SetEntityStateRequest.newBuilder().setUuid(BridgeUtils.convertUuid(this.uuid));
        edit.accept(builder);
        NativeBridgeFfi.setEntityState(builder.build());
    }

    private static @Nullable Component customNameFromState(EntityStateResponse state) {
        return state.hasCustomName() ? GsonComponentSerializer.gson().deserialize(state.getCustomName()) : null;
    }

    private static @Nullable Entity resolveEntity(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        return player != null ? player : Bukkit.getEntity(uuid);
    }

    private Location cachedLocation;
    private EntityType entityType = EntityType.UNKNOWN;

    public static Entity create(UUID uuid, EntityType type, Location loc, int entityId) {
        PatchBukkitEntity entity;
        if (type == EntityType.ITEM) {
            entity = new PatchBukkitItem(uuid, entityId);
        } else if (type == EntityType.EXPERIENCE_ORB) {
            entity = new PatchBukkitExperienceOrb(uuid);
        } else {
            PatchBukkitEntity typed = TypedEntityClasses.create(type, uuid, entityId);
            entity = typed != null ? typed : new PatchBukkitEntity(uuid, type != null ? type.name() : "entity", entityId);
        }
        entity.entityType = type != null ? type : EntityType.UNKNOWN;
        entity.cachedLocation = loc != null ? loc.clone() : new Location(null, 0, 0, 0);
        return entity;
    }

    public static Entity create(UUID uuid, EntityType type, Location loc) {
        return create(uuid, type, loc, -1);
    }

    private static final java.util.concurrent.atomic.AtomicInteger NEXT_ENTITY_ID = new java.util.concurrent.atomic.AtomicInteger(1);
    protected int entityId;
    private int placeholderEntityId;

    public PatchBukkitEntity(
        UUID uuid,
        String name,
        int entityId
    ) {
        this.uuid = uuid;
        this.name = name;
        this.entityId = entityId > 0 ? entityId : -1;
    }

    public PatchBukkitEntity(
        UUID uuid,
        String name
    ) {
        this(uuid, name, -1);
    }

    public void setEntityId(int entityId) {
        if (entityId > 0) {
            this.entityId = entityId;
        }
    }

    @Override
    public void setMetadata(@NotNull String metadataKey, @NotNull MetadataValue newMetadataValue) {
        List<MetadataValue> list = metadataMap.computeIfAbsent(metadataKey, k -> new ArrayList<>());
        list.removeIf(v -> v.getOwningPlugin() == newMetadataValue.getOwningPlugin());
        list.add(newMetadataValue);
    }

    @Override
    public @NotNull List<MetadataValue> getMetadata(@NotNull String metadataKey) {
        List<MetadataValue> list = metadataMap.get(metadataKey);
        return list != null ? Collections.unmodifiableList(new ArrayList<>(list)) : Collections.emptyList();
    }

    @Override
    public boolean hasMetadata(@NotNull String metadataKey) {
        List<MetadataValue> list = metadataMap.get(metadataKey);
        return list != null && !list.isEmpty();
    }

    @Override
    public void removeMetadata(@NotNull String metadataKey, @NotNull Plugin owningPlugin) {
        List<MetadataValue> list = metadataMap.get(metadataKey);
        if (list != null) {
            list.removeIf(v -> v.getOwningPlugin() == owningPlugin);
            if (list.isEmpty()) {
                metadataMap.remove(metadataKey);
            }
        }
    }

    @Override
    public void sendMessage(String message) {
    }

    @Override
    public void sendMessage(String... messages) {
    }

    @Override
    public void sendMessage(UUID sender, String message) {
        this.sendMessage(message);
    }

    @Override
    public void sendMessage(UUID sender, String... messages) {
        this.sendMessage(messages);
    }

    @Override
    public @NotNull String getName() {
        return this.name;
    }

    @Override
    public @NotNull Component name() {
        return Component.text(this.name);
    }

    @Override
    public boolean isPermissionSet(@NotNull String name) {
        return getPermissible().isPermissionSet(name);
    }

    @Override
    public boolean isPermissionSet(@NotNull Permission perm) {
        return getPermissible().isPermissionSet(perm);
    }

    @Override
    public boolean hasPermission(String name) {
        return getPermissible().hasPermission(name);
    }

    @Override
    public boolean hasPermission(Permission perm) {
        return getPermissible().hasPermission(perm);
    }

    @Override
    public @NotNull PermissionAttachment addAttachment(@NotNull Plugin plugin, @NotNull String name, boolean value) {
        return getPermissible().addAttachment(plugin, name, value);
    }

    @Override
    public @NotNull PermissionAttachment addAttachment(@NotNull Plugin plugin) {
        return getPermissible().addAttachment(plugin);
    }

    @Override
    public @Nullable PermissionAttachment addAttachment(@NotNull Plugin plugin, @NotNull String name, boolean value, int ticks) {
        return getPermissible().addAttachment(plugin, name, value, ticks);
    }

    @Override
    public @Nullable PermissionAttachment addAttachment(@NotNull Plugin plugin, int ticks) {
        return getPermissible().addAttachment(plugin, ticks);
    }

    @Override
    public void removeAttachment(@NotNull PermissionAttachment attachment) {
        getPermissible().removeAttachment(attachment);
    }

    @Override
    public void recalculatePermissions() {
        getPermissible().recalculatePermissions();
    }

    @Override
    public @NotNull Set<PermissionAttachmentInfo> getEffectivePermissions() {
        return getPermissible().getEffectivePermissions();
    }

    @Override
    public boolean isOp() {
        return getPermissible().isOp();
    }

    @Override
    public void setOp(boolean value) {
        getPermissible().setOp(value);
    }

    @Override
    public @Nullable Component customName() {
        return customNameFromState(state());
    }

    @Override
    public void customName(@Nullable Component customName) {
        updateState(b -> b.setCustomName(customName == null ? "" : GsonComponentSerializer.gson().serialize(customName)));
    }

    @Override
    public @Nullable String getCustomName() {
        Component customName = customName();
        return customName == null ? null : LegacyComponentSerializer.legacySection().serialize(customName);
    }

    @Override
    public void setCustomName(@Nullable String name) {
        customName(name == null ? null : LegacyComponentSerializer.legacySection().deserialize(name));
    }

    @Override
    public @NotNull PersistentDataContainer getPersistentDataContainer() {
        return this.persistentDataContainer;
    }

    public <T> @org.jspecify.annotations.Nullable T getData(Valued<T> type) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getData'");
    }

    public <T> @org.jspecify.annotations.Nullable T getDataOrDefault(Valued<? extends T> type,
            @org.jspecify.annotations.Nullable T fallback) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getDataOrDefault'");
    }

    @Override
    public boolean hasData(DataComponentType type) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'hasData'");
    }

    @Override
    public @NotNull Location getLocation() {
        try {
            var location = NativeBridgeFfi.getLocation(BridgeUtils.convertUuid(this.uuid));
            if (location != null && location.hasWorld() && location.hasPosition() && location.getWorld().hasUuid()) {
                var world = PatchBukkitWorld.getOrCreate(BridgeUtils.convertUuid(location.getWorld().getUuid()));
                var position = location.getPosition();
                return new Location(world, position.getX(), position.getY(), position.getZ(), location.getYaw(), location.getPitch());
            }
        } catch (Throwable ignored) {}
        Location fallback = this.cachedLocation != null ? this.cachedLocation.clone() : new Location(null, 0, 0, 0);
        if (fallback.getWorld() == null && !Bukkit.getWorlds().isEmpty()) {
            fallback.setWorld(Bukkit.getWorlds().get(0));
        }
        return fallback;
    }

    @Override
    public @Nullable Location getLocation(@Nullable Location loc) {
        if (loc == null) {
            return this.getLocation();
        }

        var newLoc = this.getLocation();
        loc.set(newLoc.x(), newLoc.y(), newLoc.z());
        return newLoc;
    }

    private Vector velocity = new Vector(0, 0, 0);

    @Override
    public void setVelocity(@NotNull Vector velocity) {
        this.velocity = velocity.clone();
        var request = SetEntityVelocityRequest.newBuilder()
            .setUuid(BridgeUtils.convertUuid(this.uuid))
            .setX(velocity.getX())
            .setY(velocity.getY())
            .setZ(velocity.getZ())
            .build();
        NativeBridgeFfi.setEntityVelocity(request);
    }

    @Override
    public @NotNull Vector getVelocity() {
        var resp = NativeBridgeFfi.getEntityVelocity(BridgeUtils.convertUuid(this.uuid));
        if (resp != null) {
            return new Vector(resp.getX(), resp.getY(), resp.getZ());
        }
        return this.velocity.clone();
    }

    @Override
    public boolean teleport(@NotNull Location location) {
        var request = TeleportEntityRequest.newBuilder()
            .setUuid(BridgeUtils.convertUuid(this.uuid))
            .setLocation(BridgeUtils.convertLocation(location))
            .build();
        NativeBridgeFfi.teleportEntity(request);
        return true;
    }

    @Override
    public boolean teleport(@NotNull Location location, @NotNull TeleportCause cause) {
        return teleport(location);
    }

    @Override
    public double getHeight() {
        return state().getHeight();
    }

    @Override
    public double getWidth() {
        return state().getWidth();
    }

    @Override
    public @NotNull BoundingBox getBoundingBox() {
        EntityStateResponse state = state();
        return new BoundingBox(state.getBoundingBoxMin().getX(), state.getBoundingBoxMin().getY(), state.getBoundingBoxMin().getZ(),
            state.getBoundingBoxMax().getX(), state.getBoundingBoxMax().getY(), state.getBoundingBoxMax().getZ());
    }

    @Override
    public boolean isOnGround() {
        var resp = NativeBridgeFfi.isOnGround(BridgeUtils.convertUuid(this.uuid));
        return resp != null && resp.getOnGround();
    }

    @Override
    public boolean isInWater() {
        return state().getInWater();
    }

    @Override
    public @NotNull World getWorld() {
        try {
            var location = NativeBridgeFfi.getLocation(BridgeUtils.convertUuid(this.uuid));
            if (location != null && location.hasWorld() && location.getWorld().hasUuid()) {
                return PatchBukkitWorld.getOrCreate(BridgeUtils.convertUuid(location.getWorld().getUuid()));
            }
        } catch (Throwable ignored) {}
        if (this.cachedLocation != null && this.cachedLocation.getWorld() != null) {
            return this.cachedLocation.getWorld();
        }
        return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
    }

    @Override
    public void setRotation(float yaw, float pitch) {
        Location loc = getLocation();
        teleport(new Location(loc.getWorld(), loc.getX(), loc.getY(), loc.getZ(), yaw, pitch));
    }

    @Override
    public void setRotation(@NotNull io.papermc.paper.math.Angle yaw, @NotNull io.papermc.paper.math.Angle pitch) {
        setRotation(yaw.degrees(), pitch.degrees());
    }

    @Override
    public void lookAt(double x, double y, double z, @NotNull LookAnchor entityAnchor) {
        Location loc = getLocation();
        double dx = x - loc.getX();
        double dy = y - loc.getY();
        double dz = z - loc.getZ();
        double r = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(Math.atan2(-dy, r));
        teleport(new Location(loc.getWorld(), loc.getX(), loc.getY(), loc.getZ(), yaw, pitch));
    }

    @Override
    public boolean teleport(@NotNull Location location, @NotNull TeleportCause cause,
            @NotNull TeleportFlag @NotNull... teleportFlags) {
        return teleport(location, cause);
    }

    @Override
    public boolean teleport(@NotNull Entity destination) {
        return teleport(destination.getLocation());
    }

    @Override
    public boolean teleport(@NotNull Entity destination, @NotNull TeleportCause cause) {
        return teleport(destination.getLocation(), cause);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> teleportAsync(@NotNull Location loc, @NotNull TeleportCause cause,
            @NotNull TeleportFlag @NotNull... teleportFlags) {
        return CompletableFuture.completedFuture(teleport(loc, cause));
    }

    @Override
    public @NotNull List<Entity> getNearbyEntities(double x, double y, double z) {
        World world = getWorld();
        if (world == null) {
            return new ArrayList<>();
        }
        List<Entity> nearby = new ArrayList<>(world.getNearbyEntities(getLocation(), x, y, z));
        nearby.removeIf(entity -> entity.getUniqueId().equals(this.uuid));
        return nearby;
    }

    @Override
    public int getEntityId() {
        if (this.entityId <= 0) {
            try {
                var resp = NativeBridgeFfi.getEntityId(BridgeUtils.convertUuid(this.uuid));
                if (resp != null && resp.getEntityId() > 0) {
                    this.entityId = resp.getEntityId();
                }
            } catch (Throwable ignored) {}
        }
        if (this.entityId > 0) {
            return this.entityId;
        }
        // Not in the world yet (spawns are applied on the next server step): hand out a stable
        // placeholder but keep asking the server, so the real id replaces it once known.
        if (this.placeholderEntityId <= 0) {
            this.placeholderEntityId = NEXT_ENTITY_ID.incrementAndGet();
        }
        return this.placeholderEntityId;
    }

    @Override
    public int getFireTicks() {
        return state().getFireTicks();
    }

    @Override
    public int getMaxFireTicks() {
        // Vanilla Entity#getFireImmuneTicks: players get 20 ticks, everything else 1.
        return this instanceof Player ? 20 : 1;
    }

    @Override
    public void setFireTicks(int ticks) {
        updateState(b -> b.setFireTicks(ticks));
    }

    @Override
    public void setVisualFire(boolean fire) {
        updateState(b -> b.setVisualFire(fire));
    }

    @Override
    public void setVisualFire(@NotNull TriState fire) {
        updateState(b -> b.setVisualFire(fire == TriState.TRUE));
    }

    public boolean isVisualFire() {
        return state().getVisualFire();
    }

    @Override
    public @NotNull TriState getVisualFire() {
        return TriState.byBoolean(state().getVisualFire());
    }

    @Override
    public int getFreezeTicks() {
        return state().getFreezeTicks();
    }

    @Override
    public int getMaxFreezeTicks() {
        return TICKS_REQUIRED_TO_FREEZE;
    }

    @Override
    public void setFreezeTicks(int ticks) {
        updateState(b -> b.setFreezeTicks(ticks));
    }

    @Override
    public boolean isFrozen() {
        return state().getFreezeTicks() >= TICKS_REQUIRED_TO_FREEZE;
    }

    @Override
    public void setInvisible(boolean invisible) {
        updateState(b -> b.setInvisible(invisible));
    }

    @Override
    public boolean isInvisible() {
        return state().getInvisible();
    }

    @Override
    public void setNoPhysics(boolean noPhysics) {
        updateState(b -> b.setNoPhysics(noPhysics));
    }

    @Override
    public boolean hasNoPhysics() {
        return state().getNoPhysics();
    }

    @Override
    public boolean isFreezeTickingLocked() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'isFreezeTickingLocked'");
    }

    @Override
    public void lockFreezeTicks(boolean locked) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'lockFreezeTicks'");
    }

    @Override
    public void remove() {
        World world = this.getWorld();
        NativeBridgeFfi.removeEntity(BridgeUtils.convertUuid(this.uuid));
        if (world instanceof org.patchbukkit.world.PatchBukkitWorld pbWorld) {
            pbWorld.unregisterEntity(this.uuid);
        }
        if (world != null && org.bukkit.Bukkit.getServer() != null) {
            org.bukkit.Bukkit.getPluginManager().callEvent(
                new com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent(this, world));
        }
    }

    @Override
    public boolean isDead() {
        EntityStateResponse state = state();
        return !state.getFound() || state.getRemoved() || state.getDead();
    }

    @Override
    public boolean isValid() {
        EntityStateResponse state = state();
        return state.getFound() && !state.getRemoved() && !state.getDead();
    }

    @Override
    public @NotNull Server getServer() {
        return Bukkit.getServer();
    }

    @Override
    public boolean isPersistent() {
        return this.persistent;
    }

    @Override
    public void setPersistent(boolean persistent) {
        this.persistent = persistent;
    }

    // Bukkit entities persist with their chunk unless a plugin says otherwise.
    private volatile boolean persistent = true;

    @Override
    public @Nullable Entity getPassenger() {
        List<Entity> passengers = getPassengers();
        return passengers.isEmpty() ? null : passengers.get(0);
    }

    @Override
    public boolean setPassenger(@NotNull Entity passenger) {
        if (passenger.getUniqueId().equals(this.uuid)) {
            return false;
        }
        eject();
        return addPassenger(passenger);
    }

    @Override
    public @NotNull List<Entity> getPassengers() {
        List<Entity> passengers = new ArrayList<>();
        for (var passenger : state().getPassengersList()) {
            Entity entity = resolveEntity(BridgeUtils.convertUuid(passenger));
            if (entity != null) {
                passengers.add(entity);
            }
        }
        return passengers;
    }

    @Override
    public boolean addPassenger(@NotNull Entity passenger) {
        var response = NativeBridgeFfi.addEntityPassenger(EntityPassengerRequest.newBuilder()
            .setVehicle(BridgeUtils.convertUuid(this.uuid))
            .setPassenger(BridgeUtils.convertUuid(passenger.getUniqueId()))
            .build());
        return response != null && response.getSuccess();
    }

    @Override
    public boolean removePassenger(@NotNull Entity passenger) {
        var response = NativeBridgeFfi.removeEntityPassenger(EntityPassengerRequest.newBuilder()
            .setVehicle(BridgeUtils.convertUuid(this.uuid))
            .setPassenger(BridgeUtils.convertUuid(passenger.getUniqueId()))
            .build());
        return response != null && response.getSuccess();
    }

    @Override
    public boolean isEmpty() {
        return state().getPassengersCount() == 0;
    }

    @Override
    public boolean eject() {
        var response = NativeBridgeFfi.ejectEntityPassengers(BridgeUtils.convertUuid(this.uuid));
        return response != null && response.getSuccess();
    }

    public @NotNull ItemStack getPickItemStack() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getPickItemStack'");
    }

    private float fallDistance = 0.0f;

    @Override
    public float getFallDistance() {
        return this.fallDistance;
    }

    @Override
    public void setFallDistance(float distance) {
        this.fallDistance = distance;
    }

    @Override
    public void setLastDamageCause(@Nullable EntityDamageEvent event) {
        this.lastDamageCause = event;
    }

    @Override
    public @Nullable EntityDamageEvent getLastDamageCause() {
        return this.lastDamageCause;
    }

    @Override
    public @NotNull UUID getUniqueId() {
        return this.uuid;
    }

    @Override
    public int getTicksLived() {
        return state().getTicksLived();
    }

    @Override
    public void setTicksLived(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("Age value (" + value + ") must be positive");
        }
        updateState(b -> b.setTicksLived(value));
    }

    @Override
    public void playEffect(@NotNull EntityEffect effect) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'playEffect'");
    }

    @Override
    public @NotNull EntityType getType() {
        return this.entityType != null ? this.entityType : EntityType.UNKNOWN;
    }

    @Override
    public @NotNull Sound getSwimSound() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getSwimSound'");
    }

    @Override
    public @NotNull Sound getSwimSplashSound() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getSwimSplashSound'");
    }

    @Override
    public @NotNull Sound getSwimHighSpeedSplashSound() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getSwimHighSpeedSplashSound'");
    }

    @Override
    public boolean isInsideVehicle() {
        return state().hasVehicle();
    }

    @Override
    public boolean leaveVehicle() {
        var response = NativeBridgeFfi.leaveEntityVehicle(BridgeUtils.convertUuid(this.uuid));
        return response != null && response.getSuccess();
    }

    @Override
    public @Nullable Entity getVehicle() {
        EntityStateResponse state = state();
        return state.hasVehicle() ? resolveEntity(BridgeUtils.convertUuid(state.getVehicle())) : null;
    }

    @Override
    public void setCustomNameVisible(boolean flag) {
        updateState(b -> b.setCustomNameVisible(flag));
    }

    @Override
    public boolean isCustomNameVisible() {
        return state().getCustomNameVisible();
    }

    @Override
    public void setVisibleByDefault(boolean visible) {
        this.visibleByDefault = visible;
    }

    @Override
    public boolean isVisibleByDefault() {
        return this.visibleByDefault;
    }

    public @NotNull Set<Player> getTrackedBy() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getTrackedBy'");
    }

    @Override
    public boolean isTrackedBy(@NotNull Player player) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'isTrackedBy'");
    }

    @Override
    public void setGlowing(boolean flag) {
        updateState(b -> b.setGlowing(flag));
    }

    @Override
    public boolean isGlowing() {
        return state().getGlowing();
    }

    @Override
    public void setInvulnerable(boolean flag) {
        updateState(b -> b.setInvulnerable(flag));
    }

    @Override
    public boolean isInvulnerable() {
        return state().getInvulnerable();
    }

    @Override
    public boolean isSilent() {
        return state().getSilent();
    }

    @Override
    public void setSilent(boolean flag) {
        updateState(b -> b.setSilent(flag));
    }

    @Override
    public boolean hasGravity() {
        return !state().getNoGravity();
    }

    @Override
    public void setGravity(boolean gravity) {
        updateState(b -> b.setNoGravity(!gravity));
    }

    @Override
    public int getPortalCooldown() {
        return state().getPortalCooldown();
    }

    @Override
    public void setPortalCooldown(int cooldown) {
        if (cooldown < 0) {
            throw new IllegalArgumentException("Portal cooldown must not be negative");
        }
        updateState(b -> b.setPortalCooldown(cooldown));
    }

    @Override
    public @NotNull Set<String> getScoreboardTags() {
        return new java.util.HashSet<>(state().getScoreboardTagsList());
    }

    @Override
    public boolean addScoreboardTag(@NotNull String tag) {
        List<String> tags = state().getScoreboardTagsList();
        if (tags.contains(tag) || tags.size() >= MAX_SCOREBOARD_TAGS) {
            return false;
        }
        updateState(b -> b.addAddScoreboardTags(tag));
        return true;
    }

    @Override
    public boolean removeScoreboardTag(@NotNull String tag) {
        if (!state().getScoreboardTagsList().contains(tag)) {
            return false;
        }
        updateState(b -> b.addRemoveScoreboardTags(tag));
        return true;
    }

    @Override
    public @NotNull PistonMoveReaction getPistonMoveReaction() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getPistonMoveReaction'");
    }

    @Override
    public @NotNull BlockFace getFacing() {
        // Same rounding as vanilla Direction.fromYRot.
        int quarter = (int) Math.floor(getLocation().getYaw() / 90.0 + 0.5) & 3;
        return switch (quarter) {
            case 0 -> BlockFace.SOUTH;
            case 1 -> BlockFace.WEST;
            case 2 -> BlockFace.NORTH;
            default -> BlockFace.EAST;
        };
    }

    @Override
    public @NotNull Pose getPose() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getPose'");
    }

    private boolean sneaking = false;

    @Override
    public boolean isSneaking() {
        return this.sneaking;
    }

    @Override
    public void setSneaking(boolean sneak) {
        this.sneaking = sneak;
    }

    @Override
    public void setPose(@NotNull Pose pose, boolean fixed) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'setPose'");
    }

    public boolean hasFixedPose() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'hasFixedPose'");
    }

    public EntityRemoveEvent.@Nullable Cause getRemoveEventCause() {
        return null;
    }

    @Override
    public io.papermc.paper.entity.@Nullable RemovalReason getRemovalReason() {
        return null;
    }

    @Override
    public @NotNull SpawnCategory getSpawnCategory() {
        return spawnCategoryOf(this.getType());
    }

    /** The vanilla MobCategory of the type, as Bukkit's SpawnCategory (CraftSpawnCategory). */
    public static @NotNull SpawnCategory spawnCategoryOf(EntityType type) {
        if (type == null || type.getKey() == null) {
            return SpawnCategory.MISC;
        }
        try {
            var nmsType = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getOptional(net.minecraft.resources.Identifier.parse(type.getKey().toString()));
            if (nmsType.isEmpty()) {
                return SpawnCategory.MISC;
            }
            return switch (nmsType.get().getCategory()) {
                case MONSTER -> SpawnCategory.MONSTER;
                case CREATURE -> SpawnCategory.ANIMAL;
                case AMBIENT -> SpawnCategory.AMBIENT;
                case AXOLOTLS -> SpawnCategory.AXOLOTL;
                case UNDERGROUND_WATER_CREATURE -> SpawnCategory.WATER_UNDERGROUND_CREATURE;
                case WATER_CREATURE -> SpawnCategory.WATER_ANIMAL;
                case WATER_AMBIENT -> SpawnCategory.WATER_AMBIENT;
                default -> SpawnCategory.MISC;
            };
        } catch (Throwable t) {
            return SpawnCategory.MISC;
        }
    }

    @Override
    public boolean isInWorld() {
        EntityStateResponse state = state();
        return state.getFound() && !state.getRemoved();
    }

    @Override
    public @Nullable String getAsString() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getAsString'");
    }

    @Override
    public @Nullable EntitySnapshot createSnapshot() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'createSnapshot'");
    }

    @Override
    public @NotNull Entity copy() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'copy'");
    }

    @Override
    public @NotNull Entity copy(@NotNull Location to) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'copy'");
    }

    @Override
    public @NotNull Spigot spigot() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'spigot'");
    }

    @Override
    public @NotNull Component teamDisplayName() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'teamDisplayName'");
    }

    @Override
    public @Nullable Location getOrigin() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getOrigin'");
    }

    @Override
    public boolean fromMobSpawner() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'fromMobSpawner'");
    }

    @Override
    public @NotNull SpawnReason getEntitySpawnReason() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getEntitySpawnReason'");
    }

    @Override
    public boolean isUnderWater() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'isUnderWater'");
    }

    @Override
    public boolean isInRain() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'isInRain'");
    }

    @Override
    public boolean isInLava() {
        return state().getInLava();
    }

    @Override
    public boolean isTicking() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'isTicking'");
    }

    @Override
    public @NotNull Set<Player> getTrackedPlayers() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getTrackedPlayers'");
    }

    @Override
    public boolean spawnAt(@NotNull Location location, @NotNull SpawnReason reason) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'spawnAt'");
    }

    @Override
    public boolean isInPowderedSnow() {
        return state().getInPowderSnow();
    }

    @Override
    public double getX() {
        return getLocation().getX();
    }

    @Override
    public double getY() {
        return getLocation().getY();
    }

    @Override
    public double getZ() {
        return getLocation().getZ();
    }

    @Override
    public float getPitch() {
        return getLocation().getPitch();
    }

    @Override
    public float getYaw() {
        return getLocation().getYaw();
    }

    @Override
    public boolean collidesAt(@NotNull Location location) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'collidesAt'");
    }

    @Override
    public boolean wouldCollideUsing(@NotNull BoundingBox boundingBox) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'wouldCollideUsing'");
    }

    @Override
    public @NotNull EntityScheduler getScheduler() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getScheduler'");
    }

    @Override
    public @NotNull String getScoreboardEntryName() {
        return this instanceof Player ? getName() : this.uuid.toString();
    }

    public void broadcastHurtAnimation(@NotNull Collection<Player> players) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'broadcastHurtAnimation'");
    }

    public Source soundSource() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'soundSource'");
    }

    @Override
    public @NotNull SoundCategory getSoundCategory() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getSoundCategory'");
    }
}
