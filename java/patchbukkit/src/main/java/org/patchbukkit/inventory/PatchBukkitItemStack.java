package org.patchbukkit.inventory;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Standalone item stack used in place of Paper's CraftItemStack.
 *
 * <p>Paper's {@link ItemStack} forwards nearly everything to a server-side delegate, so every
 * delegating method is implemented here.
 */
public class PatchBukkitItemStack extends ItemStack {

    private Material type;
    private int amount;
    private ItemMeta meta;
    /** When set, this stack mirrors an inventory slot: changes are written back to it. */
    private transient Consumer<ItemStack> mirror;

    /**
     * Binds this stack to an inventory slot, like CraftBukkit's mirror stacks, so plugins
     * that edit a stack obtained from an inventory (e.g. EssentialsX /more) change the slot.
     */
    public PatchBukkitItemStack mirrorTo(Consumer<ItemStack> writer) {
        this.mirror = writer;
        return this;
    }

    private void writeBack() {
        Consumer<ItemStack> writer = this.mirror;
        if (writer != null) {
            this.mirror = null; // avoid re-entry while the slot is written
            try {
                writer.accept(this);
            } finally {
                this.mirror = writer;
            }
        }
    }

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
        Material newType = type != null ? type : Material.AIR;
        if (this.meta != null) {
            this.meta = newType.isAir() ? null : PatchBukkitItemMeta.convert(this.meta, newType);
        }
        this.type = newType;
        writeBack();
    }

    @Override
    public @NonNull ItemStack withType(@NonNull Material type) {
        PatchBukkitItemStack copy = (PatchBukkitItemStack) clone();
        copy.setType(type);
        return copy;
    }

    @Override
    public int getAmount() {
        return amount;
    }

    @Override
    public void setAmount(int amount) {
        this.amount = amount;
        writeBack();
    }

    @Override
    public int getMaxStackSize() {
        if (this.meta != null && this.meta.hasMaxStackSize()) {
            return this.meta.getMaxStackSize();
        }
        return this.type.isAir() ? 0 : this.type.getMaxStackSize();
    }

    @Override
    public short getDurability() {
        return this.meta instanceof Damageable damageable ? (short) damageable.getDamage() : 0;
    }

    @Override
    public void setDurability(short durability) {
        if (getItemMetaInternal() instanceof Damageable damageable) {
            damageable.setDamage(durability);
        }
        writeBack();
    }

    /** Returns the live meta, creating it for non-air items. */
    private ItemMeta getItemMetaInternal() {
        if (this.meta == null && !this.type.isAir()) {
            this.meta = PatchBukkitItemMeta.create(this.type);
        }
        return this.meta;
    }

    @Override
    public @Nullable ItemMeta getItemMeta() {
        ItemMeta current = getItemMetaInternal();
        return current == null ? null : current.clone();
    }

    @Override
    public boolean setItemMeta(@Nullable ItemMeta itemMeta) {
        if (itemMeta == null) {
            this.meta = null;
            writeBack();
            return true;
        }
        if (this.type.isAir() || PatchBukkitItemMeta.handler(itemMeta) == null) {
            writeBack();
            return false;
        }
        this.meta = PatchBukkitItemMeta.convert(itemMeta, this.type);
        writeBack();
        return true;
    }

    @Override
    public boolean hasItemMeta() {
        PatchBukkitItemMeta handler = PatchBukkitItemMeta.handler(this.meta);
        return !isEmpty() && handler != null && !handler.isEmpty();
    }

    @Override
    public boolean isEmpty() {
        return type.isAir() || amount <= 0;
    }

    @Override
    public boolean isSimilar(@Nullable ItemStack stack) {
        if (stack == null) {
            return false;
        }
        if (stack == this) {
            return true;
        }
        if (this.type != stack.getType()) {
            return false;
        }
        boolean thisHas = hasItemMeta();
        boolean otherHas = stack.hasItemMeta();
        if (!thisHas || !otherHas) {
            return thisHas == otherHas;
        }
        return Objects.equals(this.meta, stack.getItemMeta());
    }

    @Override
    public boolean containsEnchantment(@NonNull Enchantment ench) {
        return this.meta != null && this.meta.hasEnchant(ench);
    }

    @Override
    public int getEnchantmentLevel(@NonNull Enchantment ench) {
        return this.meta == null ? 0 : this.meta.getEnchantLevel(ench);
    }

    @Override
    public @NonNull Map<Enchantment, Integer> getEnchantments() {
        return this.meta == null ? Collections.emptyMap() : this.meta.getEnchants();
    }

    @Override
    public void addUnsafeEnchantment(@NonNull Enchantment ench, int level) {
        ItemMeta current = getItemMetaInternal();
        if (current != null) {
            current.addEnchant(ench, level, true);
        }
        writeBack();
    }

    @Override
    public int removeEnchantment(@NonNull Enchantment ench) {
        int level = getEnchantmentLevel(ench);
        if (level > 0 && this.meta != null) {
            this.meta.removeEnchant(ench);
        }
        writeBack();
        return level;
    }

    @Override
    public void removeEnchantments() {
        if (this.meta != null) {
            this.meta.removeEnchantments();
        }
        writeBack();
    }

    @Override
    public io.papermc.paper.persistence.@NonNull PersistentDataContainerView getPersistentDataContainer() {
        ItemMeta current = getItemMetaInternal();
        return current != null
            ? current.getPersistentDataContainer()
            : new org.patchbukkit.persistence.PatchBukkitPersistentDataContainer();
    }

    @Override
    public boolean editPersistentDataContainer(@NonNull Consumer<PersistentDataContainer> consumer) {
        ItemMeta current = getItemMetaInternal();
        if (current == null) {
            writeBack();
            return false;
        }
        consumer.accept(current.getPersistentDataContainer());
        writeBack();
        return true;
    }

    @Override
    public @NonNull String translationKey() {
        return this.type.translationKey();
    }

    @Override
    public @NonNull Component effectiveName() {
        if (this.meta != null && this.meta.hasCustomName() && this.meta.customName() != null) {
            return this.meta.customName();
        }
        if (this.meta != null && this.meta.hasItemName() && this.meta.itemName() != null) {
            return this.meta.itemName();
        }
        return Component.translatable(translationKey());
    }

    @Override
    public @NonNull Map<String, Object> serialize() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("v", org.bukkit.Bukkit.getUnsafe().getDataVersion());
        result.put("type", this.type.name());
        if (this.amount != 1) {
            result.put("amount", this.amount);
        }
        if (hasItemMeta()) {
            result.put("meta", this.meta);
        }
        return result;
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
        return this.amount == other.getAmount() && isSimilar(other);
    }

    @Override
    public int hashCode() {
        int hash = 1;
        hash = hash * 31 + this.type.hashCode();
        hash = hash * 31 + this.amount;
        hash = hash * 31 + (hasItemMeta() ? this.meta.hashCode() : 0);
        return hash;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("ItemStack{").append(type.name()).append(" x ").append(amount);
        if (hasItemMeta()) {
            builder.append(", ").append(this.meta);
        }
        return builder.append('}').toString();
    }
}
