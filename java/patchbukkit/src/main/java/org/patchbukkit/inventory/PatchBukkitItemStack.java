package org.patchbukkit.inventory;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

public class PatchBukkitItemStack extends ItemStack {

    private Material type;
    private int amount;
    private ItemMeta meta;

    public PatchBukkitItemStack(Material type) {
        this(type, 1);
    }

    public PatchBukkitItemStack(Material type, int amount) {
        this.type = type != null ? type : Material.AIR;
        this.amount = amount;
    }

    @Override
    public @NonNull Material getType() {
        return type;
    }

    @Override
    public void setType(@Nullable Material type) {
        this.type = type != null ? type : Material.AIR;
    }

    @Override
    public int getAmount() {
        return amount;
    }

    @Override
    public void setAmount(int amount) {
        this.amount = amount;
    }

    @Override
    public @Nullable ItemMeta getItemMeta() {
        return meta;
    }

    @Override
    public boolean setItemMeta(@Nullable ItemMeta itemMeta) {
        this.meta = itemMeta;
        return true;
    }

    @Override
    public boolean hasItemMeta() {
        return meta != null;
    }

    @Override
    public boolean isEmpty() {
        return type == Material.AIR || amount <= 0;
    }

    @Override
    public @NonNull ItemStack clone() {
        PatchBukkitItemStack cloned = new PatchBukkitItemStack(type, amount);
        if (meta != null) {
            cloned.meta = meta.clone();
        }
        return cloned;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ItemStack other)) return false;
        return this.type == other.getType() && this.amount == other.getAmount() && Objects.equals(this.meta, other.getItemMeta());
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, amount, meta);
    }

    // Paper's ItemStack data accessors delegate to a CraftItemStack that PatchBukkit stacks never
    // have, so answer the durability components locally (a fresh item) and nothing else.

    @Override
    @SuppressWarnings("unchecked")
    public <T> @Nullable T getData(io.papermc.paper.datacomponent.DataComponentType.Valued<T> type) {
        int maxDurability = this.type.getMaxDurability();
        if (maxDurability <= 0) {
            return null;
        }
        if (type == io.papermc.paper.datacomponent.DataComponentTypes.MAX_DAMAGE) {
            return (T) Integer.valueOf(maxDurability);
        }
        if (type == io.papermc.paper.datacomponent.DataComponentTypes.DAMAGE) {
            return (T) Integer.valueOf(0);
        }
        return null;
    }

    @Override
    public <T> T getDataOrDefault(io.papermc.paper.datacomponent.DataComponentType.Valued<? extends T> type, T defaultValue) {
        @SuppressWarnings("unchecked")
        T value = getData((io.papermc.paper.datacomponent.DataComponentType.Valued<T>) type);
        return value != null ? value : defaultValue;
    }

    @Override
    public boolean hasData(io.papermc.paper.datacomponent.DataComponentType type) {
        return type == io.papermc.paper.datacomponent.DataComponentTypes.MAX_DAMAGE
            || type == io.papermc.paper.datacomponent.DataComponentTypes.DAMAGE
            ? this.type.getMaxDurability() > 0
            : false;
    }

    @Override
    public java.util.Set<io.papermc.paper.datacomponent.DataComponentType> getDataTypes() {
        return java.util.Set.of();
    }

    @Override
    public String toString() {
        return "ItemStack{" + type + " x " + amount + "}";
    }
}
