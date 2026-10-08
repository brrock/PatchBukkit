package org.patchbukkit.registry;

import io.papermc.paper.enchantments.EnchantmentRarity;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.set.RegistryKeySet;
import io.papermc.paper.registry.set.RegistrySet;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.enchantments.EnchantmentTarget;
import org.bukkit.entity.EntityCategory;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;
import org.jspecify.annotations.NonNull;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Static description of a vanilla enchantment. Enchantments are data-driven in
 * vanilla and Paper's registry needs a running NMS server, so PatchBukkit ships
 * the vanilla set directly; item NBT is handled by Pumpkin.
 */
public class PatchBukkitEnchantment extends Enchantment {

    private record Def(String key, String legacyName, int maxLevel, EnchantmentTarget target,
                       boolean treasure, boolean cursed, int weight, int anvilCost) {}

    // Held outside this class: Enchantment.<clinit> (our superclass) reads the
    // registry, which calls createVanilla() before this class's statics are set.
    private static final class Defs {
    private static final List<Def> VANILLA = List.of(
        new Def("protection", "PROTECTION_ENVIRONMENTAL", 4, EnchantmentTarget.ARMOR, false, false, 10, 1),
        new Def("fire_protection", "PROTECTION_FIRE", 4, EnchantmentTarget.ARMOR, false, false, 5, 2),
        new Def("feather_falling", "PROTECTION_FALL", 4, EnchantmentTarget.ARMOR_FEET, false, false, 5, 2),
        new Def("blast_protection", "PROTECTION_EXPLOSIONS", 4, EnchantmentTarget.ARMOR, false, false, 2, 4),
        new Def("projectile_protection", "PROTECTION_PROJECTILE", 4, EnchantmentTarget.ARMOR, false, false, 5, 2),
        new Def("respiration", "OXYGEN", 3, EnchantmentTarget.ARMOR_HEAD, false, false, 2, 4),
        new Def("aqua_affinity", "WATER_WORKER", 1, EnchantmentTarget.ARMOR_HEAD, false, false, 2, 4),
        new Def("thorns", "THORNS", 3, EnchantmentTarget.ARMOR, false, false, 1, 8),
        new Def("depth_strider", "DEPTH_STRIDER", 3, EnchantmentTarget.ARMOR_FEET, false, false, 2, 4),
        new Def("frost_walker", "FROST_WALKER", 2, EnchantmentTarget.ARMOR_FEET, true, false, 2, 4),
        new Def("binding_curse", "BINDING_CURSE", 1, EnchantmentTarget.WEARABLE, true, true, 1, 8),
        new Def("sharpness", "DAMAGE_ALL", 5, EnchantmentTarget.WEAPON, false, false, 10, 1),
        new Def("smite", "DAMAGE_UNDEAD", 5, EnchantmentTarget.WEAPON, false, false, 5, 2),
        new Def("bane_of_arthropods", "DAMAGE_ARTHROPODS", 5, EnchantmentTarget.WEAPON, false, false, 5, 2),
        new Def("knockback", "KNOCKBACK", 2, EnchantmentTarget.WEAPON, false, false, 5, 2),
        new Def("fire_aspect", "FIRE_ASPECT", 2, EnchantmentTarget.WEAPON, false, false, 2, 4),
        new Def("looting", "LOOT_BONUS_MOBS", 3, EnchantmentTarget.WEAPON, false, false, 2, 4),
        new Def("sweeping_edge", "SWEEPING_EDGE", 3, EnchantmentTarget.WEAPON, false, false, 2, 4),
        new Def("efficiency", "DIG_SPEED", 5, EnchantmentTarget.TOOL, false, false, 10, 1),
        new Def("silk_touch", "SILK_TOUCH", 1, EnchantmentTarget.TOOL, false, false, 1, 8),
        new Def("unbreaking", "DURABILITY", 3, EnchantmentTarget.BREAKABLE, false, false, 5, 2),
        new Def("fortune", "LOOT_BONUS_BLOCKS", 3, EnchantmentTarget.TOOL, false, false, 2, 4),
        new Def("power", "ARROW_DAMAGE", 5, EnchantmentTarget.BOW, false, false, 10, 1),
        new Def("punch", "ARROW_KNOCKBACK", 2, EnchantmentTarget.BOW, false, false, 2, 4),
        new Def("flame", "ARROW_FIRE", 1, EnchantmentTarget.BOW, false, false, 2, 4),
        new Def("infinity", "ARROW_INFINITE", 1, EnchantmentTarget.BOW, false, false, 1, 8),
        new Def("luck_of_the_sea", "LUCK", 3, EnchantmentTarget.FISHING_ROD, false, false, 2, 4),
        new Def("lure", "LURE", 3, EnchantmentTarget.FISHING_ROD, false, false, 2, 4),
        new Def("loyalty", "LOYALTY", 3, EnchantmentTarget.TRIDENT, false, false, 5, 2),
        new Def("impaling", "IMPALING", 5, EnchantmentTarget.TRIDENT, false, false, 2, 4),
        new Def("riptide", "RIPTIDE", 3, EnchantmentTarget.TRIDENT, false, false, 2, 4),
        new Def("channeling", "CHANNELING", 1, EnchantmentTarget.TRIDENT, false, false, 1, 8),
        new Def("multishot", "MULTISHOT", 1, EnchantmentTarget.CROSSBOW, false, false, 2, 4),
        new Def("quick_charge", "QUICK_CHARGE", 3, EnchantmentTarget.CROSSBOW, false, false, 5, 2),
        new Def("piercing", "PIERCING", 4, EnchantmentTarget.CROSSBOW, false, false, 10, 1),
        new Def("density", "DENSITY", 5, EnchantmentTarget.WEAPON, false, false, 5, 2),
        new Def("breach", "BREACH", 4, EnchantmentTarget.WEAPON, false, false, 2, 4),
        new Def("wind_burst", "WIND_BURST", 3, EnchantmentTarget.WEAPON, true, false, 2, 4),
        new Def("mending", "MENDING", 1, EnchantmentTarget.BREAKABLE, true, false, 2, 4),
        new Def("vanishing_curse", "VANISHING_CURSE", 1, EnchantmentTarget.VANISHABLE, true, true, 1, 8),
        new Def("soul_speed", "SOUL_SPEED", 3, EnchantmentTarget.ARMOR_FEET, true, false, 1, 8),
        new Def("swift_sneak", "SWIFT_SNEAK", 3, EnchantmentTarget.ARMOR_LEGS, true, false, 1, 8),
        new Def("lunge", "LUNGE", 3, EnchantmentTarget.WEAPON, false, false, 5, 2)
    );
    }

    public static List<PatchBukkitEnchantment> createVanilla() {
        return Defs.VANILLA.stream().map(PatchBukkitEnchantment::new).toList();
    }

    private final Def def;
    private final NamespacedKey key;

    private PatchBukkitEnchantment(Def def) {
        this.def = def;
        this.key = NamespacedKey.minecraft(def.key());
    }

    @Override public @NonNull NamespacedKey getKey() { return key; }
    @Override public @NonNull String getName() { return def.legacyName(); }
    @Override public int getMaxLevel() { return def.maxLevel(); }
    @Override public int getStartLevel() { return 1; }
    @Override public @NonNull EnchantmentTarget getItemTarget() { return def.target(); }
    @Override public boolean isTreasure() { return def.treasure(); }
    @Override public boolean isCursed() { return def.cursed(); }

    @Override
    public boolean conflictsWith(@NonNull Enchantment other) {
        return other.getKey().equals(this.key);
    }

    @Override
    public boolean canEnchantItem(@NonNull ItemStack item) {
        String id;
        try {
            id = item.getType().getKey().getKey();
        } catch (Throwable t) {
            return false;
        }
        if (id.equals("enchanted_book") || id.equals("book")) return true;
        return targetIncludes(def.target(), id);
    }

    // Name-based matching; Bukkit's EnchantmentTarget.includes relies on
    // material data (durability, tags) that PatchBukkit does not model.
    private static boolean targetIncludes(EnchantmentTarget target, String id) {
        return switch (target) {
            case ARMOR -> isArmor(id);
            case ARMOR_FEET -> id.endsWith("_boots");
            case ARMOR_LEGS -> id.endsWith("_leggings");
            case ARMOR_TORSO -> id.endsWith("_chestplate");
            case ARMOR_HEAD -> id.endsWith("_helmet");
            case WEAPON -> isWeapon(id);
            case TOOL -> isTool(id);
            case BOW -> id.equals("bow");
            case FISHING_ROD -> id.equals("fishing_rod");
            case TRIDENT -> id.equals("trident");
            case CROSSBOW -> id.equals("crossbow");
            case BREAKABLE -> isBreakable(id);
            case WEARABLE -> isWearable(id);
            case VANISHABLE -> isBreakable(id) || isWearable(id) || id.equals("compass");
            default -> true;
        };
    }

    private static boolean isArmor(String id) {
        return id.endsWith("_helmet") || id.endsWith("_chestplate") || id.endsWith("_leggings") || id.endsWith("_boots");
    }

    private static boolean isWeapon(String id) {
        return id.endsWith("_sword") || id.endsWith("_axe") || id.endsWith("_spear") || id.equals("mace") || id.equals("trident");
    }

    private static boolean isTool(String id) {
        return id.endsWith("_pickaxe") || id.endsWith("_shovel") || id.endsWith("_axe") || id.endsWith("_hoe") || id.equals("shears");
    }

    private static boolean isWearable(String id) {
        return isArmor(id) || id.equals("elytra") || id.equals("carved_pumpkin") || id.endsWith("_head") || id.endsWith("_skull");
    }

    private static boolean isBreakable(String id) {
        return isArmor(id) || isWeapon(id) || isTool(id) || id.equals("bow") || id.equals("crossbow")
            || id.equals("fishing_rod") || id.equals("shield") || id.equals("elytra") || id.equals("flint_and_steel")
            || id.equals("carrot_on_a_stick") || id.equals("warped_fungus_on_a_stick") || id.equals("brush");
    }

    @Override public @NonNull Component displayName(int level) {
        Component name = Component.translatable(translationKey());
        return def.maxLevel() > 1 || level > 1
            ? name.append(Component.space()).append(Component.translatable("enchantment.level." + level))
            : name;
    }
    @Override public boolean isTradeable() { return !def.cursed(); }
    @Override public boolean isDiscoverable() { return !def.treasure(); }
    @Override public int getMinModifiedCost(int level) { return 1 + (level - 1) * 10; }
    @Override public int getMaxModifiedCost(int level) { return getMinModifiedCost(level) + 50; }
    @Override public int getAnvilCost() { return def.anvilCost(); }

    @Override
    public @NonNull EnchantmentRarity getRarity() {
        int w = def.weight();
        if (w >= 10) return EnchantmentRarity.COMMON;
        if (w >= 5) return EnchantmentRarity.UNCOMMON;
        if (w >= 2) return EnchantmentRarity.RARE;
        return EnchantmentRarity.VERY_RARE;
    }

    @Override public float getDamageIncrease(int level, @NonNull EntityCategory category) { return 0f; }
    @Override public float getDamageIncrease(int level, @NonNull EntityType type) { return 0f; }
    @Override public @NonNull Set<EquipmentSlotGroup> getActiveSlotGroups() { return Collections.singleton(EquipmentSlotGroup.ANY); }
    @Override public @NonNull Component description() { return Component.translatable(translationKey()); }

    @Override
    public @NonNull RegistryKeySet<ItemType> getSupportedItems() {
        return RegistrySet.keySet(RegistryKey.ITEM, List.of());
    }

    @Override
    public @NonNull RegistryKeySet<ItemType> getPrimaryItems() {
        return RegistrySet.keySet(RegistryKey.ITEM, List.of());
    }

    @Override public int getWeight() { return def.weight(); }

    @Override
    public @NonNull RegistryKeySet<Enchantment> getExclusiveWith() {
        return RegistrySet.keySet(RegistryKey.ENCHANTMENT, List.of());
    }

    @Override public @NonNull String translationKey() { return "enchantment.minecraft." + def.key(); }
    @Override public @NonNull String getTranslationKey() { return translationKey(); }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Enchantment e && key.equals(e.getKey()));
    }

    @Override public int hashCode() { return key.hashCode(); }

    @Override
    public String toString() {
        return "PatchBukkitEnchantment{" + key.toString().toLowerCase(Locale.ROOT) + "}";
    }
}
