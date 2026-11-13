package org.patchbukkit.entity;

import java.util.UUID;
import net.kyori.adventure.util.TriState;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.Item;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A dropped item entity. The stack is sent to the server when the item is spawned. */
public class PatchBukkitItem extends PatchBukkitEntity implements Item {

    private ItemStack stack = ItemStack.empty();
    private int pickupDelay = 10;
    private boolean unlimitedLifetime;
    private boolean canMobPickup = true;
    private boolean canPlayerPickup = true;
    private boolean willAge = true;
    private int health = 5;
    private UUID owner;
    private UUID thrower;
    private TriState friction = TriState.NOT_SET;

    public PatchBukkitItem(UUID uuid, int entityId) {
        super(uuid, "item", entityId);
    }

    @Override public @NotNull ItemStack getItemStack() { return stack.clone(); }
    @Override public void setItemStack(@NotNull ItemStack stack) { this.stack = stack.clone(); }
    @Override public int getPickupDelay() { return pickupDelay; }
    @Override public void setPickupDelay(int delay) { this.pickupDelay = delay; }
    @Override public void setUnlimitedLifetime(boolean unlimited) { this.unlimitedLifetime = unlimited; }
    @Override public boolean isUnlimitedLifetime() { return unlimitedLifetime; }
    @Override public @Nullable UUID getOwner() { return owner; }
    @Override public void setOwner(@Nullable UUID owner) { this.owner = owner; }
    @Override public @Nullable UUID getThrower() { return thrower; }
    @Override public void setThrower(@Nullable UUID thrower) { this.thrower = thrower; }
    @Override public boolean canMobPickup() { return canMobPickup; }
    @Override public void setCanMobPickup(boolean canMobPickup) { this.canMobPickup = canMobPickup; }
    @Override public boolean canPlayerPickup() { return canPlayerPickup; }
    @Override public void setCanPlayerPickup(boolean canPlayerPickup) { this.canPlayerPickup = canPlayerPickup; }
    @Override public boolean willAge() { return willAge; }
    @Override public void setWillAge(boolean willAge) { this.willAge = willAge; }
    @Override public int getHealth() { return health; }
    @Override public void setHealth(int health) { this.health = health; }
    @Override public @NotNull TriState getFrictionState() { return friction; }
    @Override public void setFrictionState(@NotNull TriState state) { this.friction = state; }
}
