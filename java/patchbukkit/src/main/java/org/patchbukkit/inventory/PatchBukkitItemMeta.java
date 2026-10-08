package org.patchbukkit.inventory;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;
import org.patchbukkit.persistence.PatchBukkitPersistentDataContainer;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Property-backed {@link ItemMeta} that exposes the same meta sub-interfaces as Paper's
 * CraftItemMetas for each material, so plugins can use {@code instanceof} checks and casts.
 */
public final class PatchBukkitItemMeta implements InvocationHandler {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final Material material;
    private final Class<?>[] interfaces;
    private final Map<String, Object> state;
    private PatchBukkitPersistentDataContainer pdc;

    private PatchBukkitItemMeta(Material material, Class<?>[] interfaces, Map<String, Object> state) {
        this.material = material;
        this.interfaces = interfaces;
        this.state = state;
    }

    /** Creates empty meta for the material, or {@code null} for air like Paper. */
    public static ItemMeta create(Material material) {
        if (material == null || material.isAir()) {
            return null;
        }
        return newProxy(new PatchBukkitItemMeta(material, interfacesFor(material), new LinkedHashMap<>()));
    }

    /** Returns the handler behind a meta created by this class, or {@code null}. */
    public static PatchBukkitItemMeta handler(ItemMeta meta) {
        if (meta != null && Proxy.isProxyClass(meta.getClass())
            && Proxy.getInvocationHandler(meta) instanceof PatchBukkitItemMeta handler) {
            return handler;
        }
        return null;
    }

    /** Copies meta created by this class onto another material, keeping the applicable state. */
    public static ItemMeta convert(ItemMeta meta, Material material) {
        PatchBukkitItemMeta handler = handler(meta);
        if (handler == null || material == null || material.isAir()) {
            return create(material);
        }
        PatchBukkitItemMeta copy = handler.copyFor(material);
        return newProxy(copy);
    }

    public boolean isEmpty() {
        return this.state.isEmpty() && (this.pdc == null || this.pdc.isEmpty());
    }

    public Map<String, Object> state() {
        return Collections.unmodifiableMap(this.state);
    }

    public Material material() {
        return this.material;
    }

    private static ItemMeta newProxy(PatchBukkitItemMeta handler) {
        return (ItemMeta) Proxy.newProxyInstance(PatchBukkitItemMeta.class.getClassLoader(), handler.interfaces, handler);
    }

    private PatchBukkitItemMeta copyFor(Material target) {
        Map<String, Object> copied = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : this.state.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof List<?> list) {
                value = new ArrayList<>(list);
            } else if (value instanceof Map<?, ?> map) {
                value = new LinkedHashMap<>(map);
            } else if (value instanceof Set<?> set) {
                value = new LinkedHashSet<>(set);
            }
            copied.put(entry.getKey(), value);
        }
        PatchBukkitItemMeta copy = new PatchBukkitItemMeta(target, interfacesFor(target), copied);
        if (this.pdc != null && !this.pdc.isEmpty()) {
            copy.pdc = new PatchBukkitPersistentDataContainer();
            this.pdc.copyTo(copy.pdc, true);
        }
        return copy;
    }

    // Mirrors CraftItemMetas: which meta type Paper hands out for a material. Every item gets
    // Damageable and Repairable, plus its material specific sub-interface.
    private static Class<?>[] interfacesFor(Material material) {
        String name = material.name();
        Class<?> specific = switch (name) {
            case "PLAYER_HEAD", "PLAYER_WALL_HEAD" -> PatchBukkitMetaTypes.Skull.class;
            case "WRITTEN_BOOK" -> PatchBukkitMetaTypes.Book.class;
            case "WRITABLE_BOOK" -> PatchBukkitMetaTypes.WritableBook.class;
            case "ENCHANTED_BOOK" -> PatchBukkitMetaTypes.EnchantmentStorage.class;
            case "FIREWORK_ROCKET" -> PatchBukkitMetaTypes.Firework.class;
            case "FIREWORK_STAR" -> PatchBukkitMetaTypes.FireworkEffect.class;
            case "POTION", "SPLASH_POTION", "LINGERING_POTION", "TIPPED_ARROW" -> PatchBukkitMetaTypes.Potion.class;
            case "FILLED_MAP" -> PatchBukkitMetaTypes.Map.class;
            case "COMPASS" -> PatchBukkitMetaTypes.Compass.class;
            case "CROSSBOW" -> PatchBukkitMetaTypes.Crossbow.class;
            case "SUSPICIOUS_STEW" -> PatchBukkitMetaTypes.SuspiciousStew.class;
            case "BUNDLE" -> PatchBukkitMetaTypes.Bundle.class;
            default -> {
                if (name.endsWith("_BANNER")) {
                    yield PatchBukkitMetaTypes.Banner.class;
                } else if (name.startsWith("LEATHER_") && isArmor(name) || name.equals("WOLF_ARMOR")) {
                    yield PatchBukkitMetaTypes.LeatherArmor.class;
                } else if (isArmor(name)) {
                    yield PatchBukkitMetaTypes.Armor.class;
                } else if (name.endsWith("SHULKER_BOX") || name.endsWith("_SIGN") || name.equals("CHEST")
                    || name.equals("BARREL") || name.equals("FURNACE") || name.equals("SPAWNER")) {
                    yield PatchBukkitMetaTypes.BlockState.class;
                }
                yield PatchBukkitMetaTypes.DamageableRepairable.class;
            }
        };
        return new Class<?>[] { specific };
    }

    private static boolean isArmor(String name) {
        return name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE") || name.endsWith("_LEGGINGS")
            || name.endsWith("_BOOTS") || name.equals("TURTLE_HELMET");
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        int argc = args == null ? 0 : args.length;

        switch (name) {
            case "clone":
                if (argc == 0) {
                    return newProxy(copyFor(this.material));
                }
                break;
            case "equals":
                if (argc == 1) {
                    PatchBukkitItemMeta other = args[0] instanceof ItemMeta meta ? handler(meta) : null;
                    return other != null && this.state.equals(other.state) && pdcEquals(other);
                }
                break;
            case "hashCode":
                if (argc == 0) {
                    return this.state.hashCode();
                }
                break;
            case "toString":
                if (argc == 0) {
                    return "PatchBukkitItemMeta{" + this.material.getKey() + ", " + this.state + "}";
                }
                break;
            case "serialize":
                if (argc == 0) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("meta-type", "UNSPECIFIC");
                    out.putAll(this.state);
                    return out;
                }
                break;
            case "getPersistentDataContainer":
                if (this.pdc == null) {
                    this.pdc = new PatchBukkitPersistentDataContainer();
                }
                return this.pdc;
            case "getAsString":
            case "getAsComponentString":
                return this.state.toString();
            case "setVersion":
                return null;
            // Paper keeps the custom name, display name and the legacy string API in one component.
            case "hasCustomName":
            case "hasDisplayName":
                return this.state.containsKey("customName");
            case "customName":
            case "displayName":
                if (argc == 0) {
                    return this.state.get("customName");
                }
                putOrRemove("customName", args[0]);
                return null;
            case "getDisplayName": {
                Object component = this.state.get("customName");
                return component instanceof Component c ? LEGACY.serialize(c) : null;
            }
            case "setDisplayName":
                putOrRemove("customName", args[0] == null ? null : LEGACY.deserialize((String) args[0]));
                return null;
            case "hasItemName":
                return this.state.containsKey("itemName");
            case "itemName":
                if (argc == 0) {
                    return this.state.get("itemName");
                }
                putOrRemove("itemName", args[0]);
                return null;
            case "getItemName": {
                Object component = this.state.get("itemName");
                return component instanceof Component c ? LEGACY.serialize(c) : null;
            }
            case "setItemName":
                putOrRemove("itemName", args[0] == null ? null : LEGACY.deserialize((String) args[0]));
                return null;
            case "hasLore":
                return this.state.get("lore") instanceof List<?> lore && !lore.isEmpty();
            case "lore":
                if (argc == 0) {
                    return this.state.get("lore") instanceof List<?> lore ? new ArrayList<>(lore) : null;
                }
                putOrRemove("lore", args[0] == null ? null : new ArrayList<>((List<?>) args[0]));
                return null;
            case "getLore": {
                if (!(this.state.get("lore") instanceof List<?> lore)) {
                    return null;
                }
                List<String> out = new ArrayList<>();
                for (Object line : lore) {
                    out.add(line instanceof Component c ? LEGACY.serialize(c) : String.valueOf(line));
                }
                return out;
            }
            case "setLore": {
                if (args[0] == null) {
                    this.state.remove("lore");
                    return null;
                }
                List<Component> lore = new ArrayList<>();
                for (Object line : (List<?>) args[0]) {
                    lore.add(LEGACY.deserialize(String.valueOf(line)));
                }
                putOrRemove("lore", lore.isEmpty() ? null : lore);
                return null;
            }
            case "hasEnchants":
                return !enchants("enchants").isEmpty();
            case "hasEnchant":
                return enchants("enchants").containsKey((Enchantment) args[0]);
            case "getEnchantLevel":
                return enchants("enchants").getOrDefault((Enchantment) args[0], 0);
            case "getEnchants":
                return Collections.unmodifiableMap(new LinkedHashMap<>(enchants("enchants")));
            case "addEnchant":
                return addEnchant("enchants", (Enchantment) args[0], (Integer) args[1], (Boolean) args[2]);
            case "removeEnchant":
                return removeEnchant("enchants", (Enchantment) args[0]);
            case "removeEnchantments":
                this.state.remove("enchants");
                return null;
            case "hasConflictingEnchant":
                return conflicts("enchants", (Enchantment) args[0]);
            case "hasStoredEnchants":
                return !enchants("storedEnchants").isEmpty();
            case "hasStoredEnchant":
                return enchants("storedEnchants").containsKey((Enchantment) args[0]);
            case "getStoredEnchantLevel":
                return enchants("storedEnchants").getOrDefault((Enchantment) args[0], 0);
            case "getStoredEnchants":
                return Collections.unmodifiableMap(new LinkedHashMap<>(enchants("storedEnchants")));
            case "addStoredEnchant":
                return addEnchant("storedEnchants", (Enchantment) args[0], (Integer) args[1], (Boolean) args[2]);
            case "removeStoredEnchant":
                return removeEnchant("storedEnchants", (Enchantment) args[0]);
            case "hasConflictingStoredEnchant":
                return conflicts("storedEnchants", (Enchantment) args[0]);
            case "addItemFlags":
                itemFlags().addAll(List.of((ItemFlag[]) args[0]));
                return null;
            case "removeItemFlags":
                itemFlags().removeAll(List.of((ItemFlag[]) args[0]));
                if (itemFlags().isEmpty()) {
                    this.state.remove("itemFlags");
                }
                return null;
            case "getItemFlags":
                return Collections.unmodifiableSet(EnumSet.copyOf(itemFlagsOrEmpty()));
            case "hasItemFlag":
                return itemFlagsOrEmpty().contains((ItemFlag) args[0]);
            case "hasDamage":
                return this.state.get("damage") instanceof Integer damage && damage > 0;
            case "hasDamageValue":
                return this.state.containsKey("damage");
            case "getDamage":
                return this.state.getOrDefault("damage", 0);
            case "setDamage":
                putOrRemove("damage", args[0] instanceof Integer damage && damage > 0 ? damage : null);
                return null;
            case "resetDamage":
                this.state.remove("damage");
                return null;
            case "getMaxDamage":
                return this.state.containsKey("maxDamage") ? this.state.get("maxDamage") : (int) this.material.getMaxDurability();
            case "hasRepairCost":
                return this.state.get("repairCost") instanceof Integer cost && cost > 0;
            case "getRepairCost":
                return this.state.getOrDefault("repairCost", 0);
            case "setRepairCost":
                putOrRemove("repairCost", args[0] instanceof Integer cost && cost > 0 ? cost : null);
                return null;
            default:
                break;
        }

        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args);
        }
        return invokeProperty(method, name, args, argc);
    }

    // Generic bean-style storage for the remaining meta properties (potion data, colors, owners, ...).
    private Object invokeProperty(Method method, String name, Object[] args, int argc) {
        Class<?> returnType = method.getReturnType();
        if (name.startsWith("set") && name.length() > 3 && argc == 1) {
            String key = property(name.substring(3));
            Object value = args[0];
            if (value instanceof Boolean b && !b) {
                value = null;
            }
            putOrRemove(key, value instanceof Collection<?> c ? new ArrayList<>(c) : value);
            return defaultValue(returnType, true);
        }
        if (name.startsWith("has") && name.length() > 3 && argc == 0 && returnType == boolean.class) {
            Object value = this.state.get(property(name.substring(3)));
            if (value == null) {
                value = this.state.get(property(name.substring(3)) + "s");
            }
            return value instanceof Collection<?> c ? !c.isEmpty() : value != null;
        }
        if (name.startsWith("is") && name.length() > 2 && argc == 0 && returnType == boolean.class) {
            return Boolean.TRUE.equals(this.state.get(property(name.substring(2))));
        }
        if (name.startsWith("get") && name.length() > 3 && argc == 0) {
            String key = property(name.substring(3));
            if (key.endsWith("Size") && this.state.get(key.substring(0, key.length() - 4) + "s") instanceof Collection<?> c) {
                return c.size();
            }
            Object value = this.state.get(key);
            if (value instanceof List<?> list && List.class.isAssignableFrom(returnType)) {
                return new ArrayList<>(list);
            }
            if (value != null && (returnType.isInstance(value) || returnType.isPrimitive())) {
                return value;
            }
            if (List.class.isAssignableFrom(returnType)) {
                return new ArrayList<>();
            }
            return defaultValue(returnType, false);
        }
        if (name.startsWith("add") && name.length() > 3 && argc >= 1) {
            List<Object> list = list(property(name.substring(3)) + "s");
            if (args[0] instanceof Iterable<?> items) {
                items.forEach(list::add);
            } else if (args[0] instanceof Object[] items) {
                list.addAll(List.of(items));
            } else {
                list.add(args[0]);
            }
            return defaultValue(returnType, true);
        }
        if (name.startsWith("clear") && name.length() > 5 && argc == 0) {
            String key = property(name.substring(5));
            boolean had = this.state.remove(key) != null;
            return defaultValue(returnType, had);
        }
        if (name.startsWith("remove") && name.length() > 6 && argc == 1) {
            String key = property(name.substring(6)) + "s";
            if (this.state.get(key) instanceof List<?> list) {
                boolean removed = args[0] instanceof Integer index && !(returnType == boolean.class)
                    ? list.remove((int) index) != null
                    : list.remove(args[0]);
                if (list.isEmpty()) {
                    this.state.remove(key);
                }
                return defaultValue(returnType, removed);
            }
            return defaultValue(returnType, false);
        }
        return defaultValue(returnType, false);
    }

    private static Object defaultValue(Class<?> type, boolean bool) {
        if (type == void.class) return null;
        if (type == boolean.class) return bool;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (Map.class.isAssignableFrom(type)) return new LinkedHashMap<>();
        if (Set.class.isAssignableFrom(type)) return new LinkedHashSet<>();
        if (Collection.class.isAssignableFrom(type)) return new ArrayList<>();
        return null;
    }

    private static String property(String suffix) {
        return Character.toLowerCase(suffix.charAt(0)) + suffix.substring(1);
    }

    private void putOrRemove(String key, Object value) {
        if (value == null) {
            this.state.remove(key);
        } else {
            this.state.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> list(String key) {
        return (List<Object>) this.state.computeIfAbsent(key, k -> new ArrayList<>());
    }

    @SuppressWarnings("unchecked")
    private Map<Enchantment, Integer> enchants(String key) {
        Object value = this.state.get(key);
        return value instanceof Map<?, ?> map ? (Map<Enchantment, Integer>) map : Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private boolean addEnchant(String key, Enchantment enchantment, int level, boolean ignoreRestrictions) {
        Objects.requireNonNull(enchantment, "Enchantment cannot be null");
        if (!ignoreRestrictions && (level < enchantment.getStartLevel() || level > enchantment.getMaxLevel())) {
            return false;
        }
        Map<Enchantment, Integer> map = (Map<Enchantment, Integer>) this.state.computeIfAbsent(key, k -> new LinkedHashMap<>());
        Integer old = map.put(enchantment, level);
        return old == null || old != level;
    }

    private boolean removeEnchant(String key, Enchantment enchantment) {
        if (!(this.state.get(key) instanceof Map<?, ?> map)) {
            return false;
        }
        boolean removed = map.remove(enchantment) != null;
        if (map.isEmpty()) {
            this.state.remove(key);
        }
        return removed;
    }

    private boolean conflicts(String key, Enchantment enchantment) {
        for (Enchantment existing : enchants(key).keySet()) {
            if (!existing.equals(enchantment) && existing.conflictsWith(enchantment)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private Set<ItemFlag> itemFlags() {
        return (Set<ItemFlag>) this.state.computeIfAbsent("itemFlags", k -> EnumSet.noneOf(ItemFlag.class));
    }

    @SuppressWarnings("unchecked")
    private Set<ItemFlag> itemFlagsOrEmpty() {
        Object value = this.state.get("itemFlags");
        return value instanceof Set<?> set ? (Set<ItemFlag>) set : EnumSet.noneOf(ItemFlag.class);
    }

    private boolean pdcEquals(PatchBukkitItemMeta other) {
        boolean thisEmpty = this.pdc == null || this.pdc.isEmpty();
        boolean otherEmpty = other.pdc == null || other.pdc.isEmpty();
        if (thisEmpty || otherEmpty) {
            return thisEmpty == otherEmpty;
        }
        return this.pdc.equals(other.pdc);
    }
}
