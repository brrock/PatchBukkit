package org.patchbukkit.entity;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent.Reason;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.InventoryView.Property;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MainHand;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.permissions.PermissibleBase;
import org.bukkit.permissions.Permission;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Range;
import org.patchbukkit.PatchBukkitServer;
import org.patchbukkit.bridge.BridgeUtils;
import org.patchbukkit.inventory.PatchBukkitInventory;

import net.kyori.adventure.key.Key;
import patchbukkit.bridge.NativeBridgeFfi;
import patchbukkit.entity.GetCooldownRequest;
import patchbukkit.entity.SetCooldownRequest;
import patchbukkit.entity.SetExhaustionRequest;
import patchbukkit.entity.SetFoodLevelRequest;
import patchbukkit.entity.SetGamemodeRequest;
import patchbukkit.entity.SetOpRequest;
import patchbukkit.entity.SetSaturationRequest;
import patchbukkit.entity.SetSneakingRequest;
import patchbukkit.entity.SetSprintingRequest;

@SuppressWarnings({ "deprecation", "removal" })
public class PatchBukkitHumanEntity
    extends PatchBukkitLivingEntity
    implements HumanEntity {

    private boolean op;
    protected final PermissibleBase perm = new PermissibleBase(this);
    protected final PlayerInventory inventory = new org.patchbukkit.inventory.PatchBukkitPlayerInventory(this);
    protected final EntityEquipment equipment = new org.patchbukkit.inventory.PatchBukkitEntityEquipment(this);
    protected final Inventory enderChest = new PatchBukkitInventory(this, 27, "Ender Chest");
    private ItemStack cursorItem = ItemStack.empty();
    private InventoryView openInventoryView;
    private final Set<NamespacedKey> discoveredRecipes = new HashSet<>();
    private final Map<Material, Integer> cooldowns = new HashMap<>();
    private Location lastDeathLocation;
    private Location bedLocation;
    private Entity shoulderEntityLeft;
    private Entity shoulderEntityRight;
    private int saturatedRegenRate = 10;
    private int unsaturatedRegenRate = 80;
    private int starvationRate = 80;
    private int sleepTicks = 0;
    private int enchantmentSeed = 0;

    public PatchBukkitHumanEntity(UUID uuid, String name, int entityId) {
        super(uuid, name, entityId);
    }

    public PatchBukkitHumanEntity(UUID uuid, String name) {
        this(uuid, name, -1);
    }

    @Override
    public boolean undiscoverRecipe(@NotNull NamespacedKey key) {
        return this.discoveredRecipes.remove(key);
    }

    @Override
    public int undiscoverRecipes(@NotNull Collection<NamespacedKey> keys) {
        int count = 0;
        for (NamespacedKey k : keys) {
            if (this.discoveredRecipes.remove(k)) count++;
        }
        return count;
    }

    @Override
    public boolean discoverRecipe(@NotNull NamespacedKey key) {
        return this.discoveredRecipes.add(key);
    }

    @Override
    public int discoverRecipes(@NotNull Collection<NamespacedKey> keys) {
        int count = 0;
        for (NamespacedKey k : keys) {
            if (this.discoveredRecipes.add(k)) count++;
        }
        return count;
    }

    @Override
    public boolean hasDiscoveredRecipe(NamespacedKey recipe) {
        return this.discoveredRecipes.contains(recipe);
    }

    @Override
    public Set<NamespacedKey> getDiscoveredRecipes() {
        return Collections.unmodifiableSet(this.discoveredRecipes);
    }

    @Override
    public boolean isOp() {
        if (this.op) {
            return true;
        }
        if (PatchBukkitServer.getInstance().isOp(getUniqueId(), getName())) {
            this.op = true;
            if (this.perm != null) {
                this.perm.recalculatePermissions();
            }
            return true;
        }
        try {
            var resp = NativeBridgeFfi.isOp(BridgeUtils.convertUuid(getUniqueId()));
            if (resp != null && resp.getIsOp()) {
                this.op = true;
                if (this.perm != null) {
                    this.perm.recalculatePermissions();
                }
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @Override
    public void setOp(boolean value) {
        this.op = value;
        PatchBukkitServer.getInstance().setOperator(getUniqueId(), getName(), value);
        try {
            var req = SetOpRequest.newBuilder()
                .setUuid(BridgeUtils.convertUuid(getUniqueId()))
                .setIsOp(value)
                .build();
            NativeBridgeFfi.setOp(req);
        } catch (Throwable t) { BridgeUtils.logBridgeFailure("setOp", t); }
        this.perm.recalculatePermissions();
    }

    @Override
    public boolean isPermissionSet(String name) {
        return this.perm.isPermissionSet(name);
    }

    @Override
    public boolean isPermissionSet(Permission perm) {
        return this.perm.isPermissionSet(perm);
    }

    @Override
    public boolean hasPermission(String name) {
        return this.perm.hasPermission(name);
    }

    @Override
    public boolean hasPermission(Permission perm) {
        return this.perm.hasPermission(perm);
    }

    @Override
    public EntityEquipment getEquipment() {
        return this.equipment;
    }

    @Override
    public PlayerInventory getInventory() {
        return this.inventory;
    }

    @Override
    public Inventory getEnderChest() {
        return this.enderChest;
    }

    @Override
    public MainHand getMainHand() {
        return MainHand.RIGHT;
    }

    @Override
    public ItemStack getItemInHand() {
        return getInventory().getItemInMainHand();
    }

    @Override
    public void setItemInHand(@Nullable ItemStack item) {
        getInventory().setItemInMainHand(item);
    }

    @Override
    public ItemStack getItemOnCursor() {
        return this.cursorItem != null ? this.cursorItem : ItemStack.empty();
    }

    @Override
    public void setItemOnCursor(@Nullable ItemStack item) {
        this.cursorItem = item != null ? item : ItemStack.empty();
    }

    @Override
    public GameMode getGameMode() {
        try {
            var resp = NativeBridgeFfi.getGamemode(BridgeUtils.convertUuid(this.getUniqueId()));
            if (resp != null) {
                return GameMode.getByValue(resp.getGamemode());
            }
        } catch (Throwable t) { BridgeUtils.logBridgeFailure("getGameMode", t); }
        return GameMode.SURVIVAL;
    }

    @Override
    public void setGameMode(GameMode mode) {
        if (mode == null) return;
        try {
            var request = SetGamemodeRequest.newBuilder()
                .setUuid(BridgeUtils.convertUuid(this.getUniqueId()))
                .setGamemode(mode.getValue())
                .build();
            NativeBridgeFfi.setGamemode(request);
        } catch (Throwable t) { BridgeUtils.logBridgeFailure("setGameMode", t); }
    }

    @Override
    public boolean isSneaking() {
        try {
            var resp = NativeBridgeFfi.getPlayerPoseState(BridgeUtils.convertUuid(getUniqueId()));
            if (resp != null) {
                return resp.getIsSneaking();
            }
        } catch (Throwable t) { BridgeUtils.logBridgeFailure("isSneaking", t); }
        return false;
    }

    @Override
    public void setSneaking(boolean sneaking) {
        try {
            var req = SetSneakingRequest.newBuilder()
                .setUuid(BridgeUtils.convertUuid(getUniqueId()))
                .setSneaking(sneaking)
                .build();
            NativeBridgeFfi.setSneaking(req);
        } catch (Throwable t) { BridgeUtils.logBridgeFailure("setSneaking", t); }
    }

    public boolean isSprinting() {
        try {
            var resp = NativeBridgeFfi.getPlayerPoseState(BridgeUtils.convertUuid(getUniqueId()));
            if (resp != null) {
                return resp.getIsSprinting();
            }
        } catch (Throwable t) { BridgeUtils.logBridgeFailure("isSprinting", t); }
        return false;
    }

    public void setSprinting(boolean sprinting) {
        try {
            var req = SetSprintingRequest.newBuilder()
                .setUuid(BridgeUtils.convertUuid(getUniqueId()))
                .setSprinting(sprinting)
                .build();
            NativeBridgeFfi.setSprinting(req);
        } catch (Throwable t) { BridgeUtils.logBridgeFailure("setSprinting", t); }
    }

    @Override
    public boolean isGliding() {
        try {
            var resp = NativeBridgeFfi.getPlayerPoseState(BridgeUtils.convertUuid(getUniqueId()));
            if (resp != null) {
                return resp.getIsGliding();
            }
        } catch (Throwable t) { BridgeUtils.logBridgeFailure("isGliding", t); }
        return false;
    }

    @Override
    public void setGliding(boolean gliding) {
    }

    @Override
    public boolean isSwimming() {
        try {
            var resp = NativeBridgeFfi.getPlayerPoseState(BridgeUtils.convertUuid(getUniqueId()));
            if (resp != null) {
                return resp.getIsSwimming();
            }
        } catch (Throwable t) { BridgeUtils.logBridgeFailure("isSwimming", t); }
        return false;
    }

    @Override
    public void setSwimming(boolean swimming) {
    }

    @Override
    public boolean isSleeping() {
        try {
            var resp = NativeBridgeFfi.getPlayerPoseState(BridgeUtils.convertUuid(getUniqueId()));
            if (resp != null) {
                return resp.getIsSleeping();
            }
        } catch (Throwable t) { BridgeUtils.logBridgeFailure("isSleeping", t); }
        return false;
    }

    @Override
    public boolean isDeeplySleeping() {
        return isSleeping() && this.sleepTicks >= 100;
    }

    @Override
    public int getSleepTicks() {
        return this.sleepTicks;
    }

    @Override
    public boolean sleep(Location location, boolean force) {
        if (location == null) return false;
        this.bedLocation = location.clone();
        return true;
    }

    @Override
    public void wakeup(boolean setSpawnLocation) {
        if (setSpawnLocation && this.bedLocation != null) {
            setPotentialRespawnLocation(this.bedLocation);
        }
        this.bedLocation = null;
        this.sleepTicks = 0;
    }

    @Override
    public Location getBedLocation() {
        return this.bedLocation != null ? this.bedLocation.clone() : null;
    }

    @Override
    public @Nullable Location getPotentialRespawnLocation() {
        return this.bedLocation != null ? this.bedLocation.clone() : null;
    }

    public void setPotentialRespawnLocation(Location location) {
        this.bedLocation = location != null ? location.clone() : null;
    }

    @Override
    public int getFoodLevel() {
        try {
            var resp = NativeBridgeFfi.getFoodLevel(BridgeUtils.convertUuid(getUniqueId()));
            if (resp != null) {
                return resp.getFoodLevel();
            }
        } catch (Throwable t) { BridgeUtils.logBridgeFailure("getFoodLevel", t); }
        return 20;
    }

    @Override
    public void setFoodLevel(int value) {
        try {
            var req = SetFoodLevelRequest.newBuilder()
                .setUuid(BridgeUtils.convertUuid(getUniqueId()))
                .setFoodLevel(value)
                .build();
            NativeBridgeFfi.setFoodLevel(req);
        } catch (Throwable t) { BridgeUtils.logBridgeFailure("setFoodLevel", t); }
    }

    @Override
    public float getSaturation() {
        try {
            var resp = NativeBridgeFfi.getFoodLevel(BridgeUtils.convertUuid(getUniqueId()));
            if (resp != null) {
                return resp.getSaturation();
            }
        } catch (Throwable t) { BridgeUtils.logBridgeFailure("getSaturation", t); }
        return 5.0f;
    }

    @Override
    public void setSaturation(float value) {
        try {
            var req = SetSaturationRequest.newBuilder()
                .setUuid(BridgeUtils.convertUuid(getUniqueId()))
                .setSaturation(value)
                .build();
            NativeBridgeFfi.setSaturation(req);
        } catch (Throwable t) { BridgeUtils.logBridgeFailure("setSaturation", t); }
    }

    @Override
    public float getExhaustion() {
        try {
            var resp = NativeBridgeFfi.getFoodLevel(BridgeUtils.convertUuid(getUniqueId()));
            if (resp != null) {
                return resp.getExhaustion();
            }
        } catch (Throwable t) { BridgeUtils.logBridgeFailure("getExhaustion", t); }
        return 0.0f;
    }

    @Override
    public void setExhaustion(float value) {
        try {
            var req = SetExhaustionRequest.newBuilder()
                .setUuid(BridgeUtils.convertUuid(getUniqueId()))
                .setExhaustion(value)
                .build();
            NativeBridgeFfi.setExhaustion(req);
        } catch (Throwable t) { BridgeUtils.logBridgeFailure("setExhaustion", t); }
    }

    @Override
    public int getSaturatedRegenRate() {
        return this.saturatedRegenRate;
    }

    @Override
    public void setSaturatedRegenRate(int ticks) {
        this.saturatedRegenRate = ticks;
    }

    @Override
    public int getUnsaturatedRegenRate() {
        return this.unsaturatedRegenRate;
    }

    @Override
    public void setUnsaturatedRegenRate(int ticks) {
        this.unsaturatedRegenRate = ticks;
    }

    @Override
    public int getStarvationRate() {
        return this.starvationRate;
    }

    @Override
    public void setStarvationRate(int ticks) {
        this.starvationRate = ticks;
    }

    @Override
    public @Nullable Location getLastDeathLocation() {
        return this.lastDeathLocation != null ? this.lastDeathLocation.clone() : null;
    }

    @Override
    public void setLastDeathLocation(@Nullable Location location) {
        this.lastDeathLocation = location != null ? location.clone() : null;
    }

    @Override
    public boolean hasCooldown(Material material) {
        if (material == null) return false;
        try {
            var req = GetCooldownRequest.newBuilder()
                .setUuid(BridgeUtils.convertUuid(getUniqueId()))
                .setItemGroup(material.name().toLowerCase())
                .build();
            var resp = NativeBridgeFfi.getCooldown(req);
            if (resp != null) return resp.getIsOnCooldown();
        } catch (Throwable t) { BridgeUtils.logBridgeFailure("hasCooldown", t); }
        return this.cooldowns.getOrDefault(material, 0) > 0;
    }

    @Override
    public int getCooldown(Material material) {
        if (material == null) return 0;
        try {
            var req = GetCooldownRequest.newBuilder()
                .setUuid(BridgeUtils.convertUuid(getUniqueId()))
                .setItemGroup(material.name().toLowerCase())
                .build();
            var resp = NativeBridgeFfi.getCooldown(req);
            if (resp != null) return (int) (resp.getCooldown() * 100);
        } catch (Throwable t) { BridgeUtils.logBridgeFailure("getCooldown", t); }
        return this.cooldowns.getOrDefault(material, 0);
    }

    @Override
    public void setCooldown(Material material, int ticks) {
        if (material == null) return;
        this.cooldowns.put(material, ticks);
        try {
            var req = SetCooldownRequest.newBuilder()
                .setUuid(BridgeUtils.convertUuid(getUniqueId()))
                .setItemGroup(material.name().toLowerCase())
                .setDurationTicks(ticks)
                .build();
            NativeBridgeFfi.setCooldown(req);
        } catch (Throwable t) { BridgeUtils.logBridgeFailure("setCooldown", t); }
    }

    @Override
    public boolean hasCooldown(ItemStack item) {
        return item != null && hasCooldown(item.getType());
    }

    public int getCooldown(ItemStack item) {
        return item != null ? getCooldown(item.getType()) : 0;
    }

    public void setCooldown(ItemStack item, int ticks) {
        if (item != null) setCooldown(item.getType(), ticks);
    }

    public int getCooldown(Key key) {
        if (key == null) return 0;
        Material mat = Material.matchMaterial(key.asString());
        return mat != null ? getCooldown(mat) : 0;
    }

    public void setCooldown(Key key, int ticks) {
        if (key == null) return;
        Material mat = Material.matchMaterial(key.asString());
        if (mat != null) setCooldown(mat, ticks);
    }

    @Override
    public InventoryView getOpenInventory() {
        return this.openInventoryView;
    }

    @Override
    public @Nullable InventoryView openInventory(Inventory inventory) {
        if (inventory == null) return null;
        if (this.openInventoryView != null) {
            closeInventory(Reason.OPEN_NEW);
        }
        this.openInventoryView = new org.patchbukkit.inventory.PatchBukkitInventoryView(this, inventory);
        try {
            if (inventory instanceof org.bukkit.inventory.PlayerInventory pinv
                    && pinv.getHolder() instanceof org.bukkit.entity.HumanEntity target) {
                NativeBridgeFfi.openPlayerInventory(
                    patchbukkit.entity.OpenPlayerInventoryRequest.newBuilder()
                        .setViewer(BridgeUtils.convertUuid(getUniqueId()))
                        .setTarget(BridgeUtils.convertUuid(target.getUniqueId()))
                        .build());
            } else if (inventory.getType() == org.bukkit.event.inventory.InventoryType.ENDER_CHEST
                    || inventory == this.enderChest) {
                NativeBridgeFfi.openEnderChest(BridgeUtils.convertUuid(getUniqueId()));
            } else {
                BridgeUtils.logBridgeFailure("openInventory",
                    new UnsupportedOperationException("opening " + inventory.getType() + " inventories"));
            }
        } catch (Throwable t) { BridgeUtils.logBridgeFailure("openInventory", t); }
        return this.openInventoryView;
    }

    /** Forgets the tracked view once Pumpkin has closed the window. */
    public void clearOpenInventoryView() {
        this.openInventoryView = null;
    }

    @Override
    public void openInventory(InventoryView inventory) {
        if (inventory != null) {
            this.openInventoryView = inventory;
        }
    }

    @Override
    public @Nullable InventoryView openWorkbench(@Nullable Location location, boolean force) {
        Inventory inv = PatchBukkitServer.getInstance().createInventory(this, org.bukkit.event.inventory.InventoryType.WORKBENCH);
        return openInventory(inv);
    }

    @Override
    public @Nullable InventoryView openEnchanting(@Nullable Location location, boolean force) {
        Inventory inv = PatchBukkitServer.getInstance().createInventory(this, org.bukkit.event.inventory.InventoryType.ENCHANTING);
        return openInventory(inv);
    }

    @Override
    public @Nullable InventoryView openMerchant(Merchant merchant, boolean force) {
        Inventory inv = PatchBukkitServer.getInstance().createInventory(this, org.bukkit.event.inventory.InventoryType.MERCHANT);
        return openInventory(inv);
    }

    @Override
    public @Nullable InventoryView openAnvil(@Nullable Location location, boolean force) {
        Inventory inv = PatchBukkitServer.getInstance().createInventory(this, org.bukkit.event.inventory.InventoryType.ANVIL);
        return openInventory(inv);
    }

    @Override
    public @Nullable InventoryView openCartographyTable(@Nullable Location location, boolean force) {
        Inventory inv = PatchBukkitServer.getInstance().createInventory(this, org.bukkit.event.inventory.InventoryType.CARTOGRAPHY);
        return openInventory(inv);
    }

    @Override
    public @Nullable InventoryView openGrindstone(@Nullable Location location, boolean force) {
        Inventory inv = PatchBukkitServer.getInstance().createInventory(this, org.bukkit.event.inventory.InventoryType.GRINDSTONE);
        return openInventory(inv);
    }

    @Override
    public @Nullable InventoryView openLoom(@Nullable Location location, boolean force) {
        Inventory inv = PatchBukkitServer.getInstance().createInventory(this, org.bukkit.event.inventory.InventoryType.LOOM);
        return openInventory(inv);
    }

    @Override
    public @Nullable InventoryView openSmithingTable(@Nullable Location location, boolean force) {
        Inventory inv = PatchBukkitServer.getInstance().createInventory(this, org.bukkit.event.inventory.InventoryType.SMITHING);
        return openInventory(inv);
    }

    @Override
    public @Nullable InventoryView openStonecutter(@Nullable Location location, boolean force) {
        Inventory inv = PatchBukkitServer.getInstance().createInventory(this, org.bukkit.event.inventory.InventoryType.STONECUTTER);
        return openInventory(inv);
    }

    @Override
    public void closeInventory(Reason reason) {
        this.openInventoryView = null;
    }

    @Override
    public void closeInventory() {
        closeInventory(Reason.PLUGIN);
    }

    @Override
    public boolean setWindowProperty(Property prop, int value) {
        return false;
    }

    @Override
    public int getEnchantmentSeed() {
        return this.enchantmentSeed;
    }

    @Override
    public void setEnchantmentSeed(int seed) {
        this.enchantmentSeed = seed;
    }

    @Override
    public boolean isBlocking() {
        return false;
    }

    @Override
    public boolean isHandRaised() {
        return false;
    }

    @Override
    public int getExpToLevel() {
        return 0;
    }

    @Override
    public float getAttackCooldown() {
        return 1.0f;
    }

    @Override
    public @Nullable Entity getShoulderEntityLeft() {
        return this.shoulderEntityLeft;
    }

    @Override
    public void setShoulderEntityLeft(@Nullable Entity entity) {
        this.shoulderEntityLeft = entity;
    }

    @Override
    public @Nullable Entity getShoulderEntityRight() {
        return this.shoulderEntityRight;
    }

    @Override
    public void setShoulderEntityRight(@Nullable Entity entity) {
        this.shoulderEntityRight = entity;
    }

    @Override
    public @Nullable Entity releaseLeftShoulderEntity() {
        Entity e = this.shoulderEntityLeft;
        this.shoulderEntityLeft = null;
        return e;
    }

    @Override
    public @Nullable Entity releaseRightShoulderEntity() {
        Entity e = this.shoulderEntityRight;
        this.shoulderEntityRight = null;
        return e;
    }

    @Override
    public void openSign(Sign sign, Side side) {
    }

    @Override
    public boolean dropItem(boolean dropAll) {
        ItemStack item = getItemInHand();
        if (item.isEmpty()) return false;
        setItemInHand(null);
        getWorld().dropItem(getLocation(), item);
        return true;
    }

    @Override
    public @Nullable Item dropItem(int slot, int amount, boolean throwRandomly, @Nullable Consumer<Item> entityOperation) {
        ItemStack item = getInventory().getItem(slot);
        if (item == null || item.isEmpty()) return null;
        ItemStack toDrop = item.clone();
        toDrop.setAmount(Math.min(amount, item.getAmount()));
        item.setAmount(item.getAmount() - toDrop.getAmount());
        getInventory().setItem(slot, item.isEmpty() ? null : item);
        return getWorld().dropItem(getLocation(), toDrop, entityOperation);
    }

    @Override
    public @Nullable Item dropItem(EquipmentSlot slot, int amount, boolean throwRandomly, @Nullable Consumer<Item> entityOperation) {
        ItemStack item = getInventory().getItem(slot);
        if (item == null || item.isEmpty()) return null;
        ItemStack toDrop = item.clone();
        toDrop.setAmount(Math.min(amount, item.getAmount()));
        item.setAmount(item.getAmount() - toDrop.getAmount());
        getInventory().setItem(slot, item.isEmpty() ? null : item);
        return getWorld().dropItem(getLocation(), toDrop, entityOperation);
    }

    @Override
    public @Nullable Item dropItem(ItemStack itemStack, boolean throwRandomly, @Nullable Consumer<Item> entityOperation) {
        if (itemStack == null || itemStack.isEmpty()) return null;
        return getWorld().dropItem(getLocation(), itemStack, entityOperation);
    }

    @Override
    public @Nullable Firework fireworkBoost(ItemStack fireworkItemStack) {
        return null;
    }

    @Override
    public @Nullable FishHook getFishHook() {
        return null;
    }

    @Override
    public void startRiptideAttack(int duration, float attackStrength, @Nullable ItemStack attackItem) {
    }
}
