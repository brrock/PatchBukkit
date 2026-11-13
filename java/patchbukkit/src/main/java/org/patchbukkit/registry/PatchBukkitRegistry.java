package org.patchbukkit.registry;

import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.tag.Tag;
import io.papermc.paper.registry.tag.TagKey;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.inventory.ItemType;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.patchbukkit.PatchBukkitLegacy;
import patchbukkit.bridge.NativeBridgeFfi;
import patchbukkit.registry.GetRegistryDataRequest;
import patchbukkit.registry.GetRegistryDataResponse;
import patchbukkit.registry.RegistryType;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;

public class PatchBukkitRegistry<P, B extends Keyed> implements Registry<B> {

    private final Map<NamespacedKey, B> entries = new ConcurrentHashMap<>();
    private final Map<String, PatchBukkitTag<B>> tags = new ConcurrentHashMap<>();
    private final ThreadLocal<Set<NamespacedKey>> inFallback = ThreadLocal.withInitial(HashSet::new);

    private final RegistryKey<B> registryKey;
    private final RegistryType registryType;
    private final Function<GetRegistryDataResponse, List<P>> extractor;
    private final Function<P, B> factory;
    private volatile boolean initialized = false;

    public PatchBukkitRegistry(RegistryKey<B> registryKey) {
        this(null, null, null, registryKey);
    }

    public PatchBukkitRegistry(
            RegistryType registryType,
            Function<GetRegistryDataResponse, List<P>> extractor,
            Function<P, B> factory
    ) {
        this(registryType, extractor, factory, null);
    }

    public PatchBukkitRegistry(
            RegistryType registryType,
            Function<GetRegistryDataResponse, List<P>> extractor,
            Function<P, B> factory,
            RegistryKey<B> registryKey
    ) {
        this.registryKey = registryKey;
        this.registryType = registryType;
        this.extractor = extractor;
        this.factory = factory;
    }

    @SuppressWarnings("unchecked")
    private void fillVanillaEnchantments() {
        for (PatchBukkitEnchantment ench : PatchBukkitEnchantment.createVanilla()) {
            entries.putIfAbsent(ench.getKey(), (B) ench);
        }
    }

    public void ensureInitialized() {
        if (initialized) return;
        synchronized (this) {
            if (initialized) return;
            initialized = true;
            initializeInternal();
        }
    }

    @SuppressWarnings("unchecked")
    private void initializeInternal() {
        if (registryType != null) {
            try {
                GetRegistryDataRequest request = GetRegistryDataRequest.newBuilder()
                        .setRegistry(registryType)
                        .build();

                GetRegistryDataResponse response = NativeBridgeFfi.getRegistryData(request);
                if (response != null && extractor != null) {
                    List<P> protoEntries = extractor.apply(response);
                    if (protoEntries != null) {
                        for (P protoEntry : protoEntries) {
                            if (protoEntry == null) continue;
                            try {
                                B value = factory != null ? factory.apply(protoEntry) : null;
                                if (value != null && value.getKey() != null) {
                                    entries.put(value.getKey(), value);
                                }
                            } catch (Throwable t) {
                                // Skip invalid entry safely
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                // Ignore FFI or RPC failure safely
            }
        }

        // Auto-discover pre-registered Paper/Bukkit constants for this registry
        if (registryKey != null) {
            if (RegistryKey.ITEM.equals(registryKey) || "item".equalsIgnoreCase(registryKey.key().value())) {
                for (Material mat : Material.values()) {
                    try {
                        if (!mat.isLegacy() && mat.getKey() != null) {
                            B itemType = (B) PatchBukkitItemType.create(mat);
                            if (itemType != null) {
                                entries.put(mat.getKey(), itemType);
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            } else if (RegistryKey.BLOCK.equals(registryKey) || "block".equalsIgnoreCase(registryKey.key().value())) {
                for (Material mat : Material.values()) {
                    try {
                        if (!mat.isLegacy() && mat.getKey() != null) {
                            B blockType = (B) PatchBukkitBlockType.create(mat);
                            if (blockType != null) {
                                entries.put(mat.getKey(), blockType);
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            } else if (RegistryKey.SOUND_EVENT.equals(registryKey) || "sound_event".equalsIgnoreCase(registryKey.key().value())) {
                // Do not reflectively autoDiscover Sound.class because Sound.<clinit> depends on Registry.SOUNDS!
            } else if (RegistryKey.MENU.equals(registryKey) || "menu".equalsIgnoreCase(registryKey.key().value())) {
                // Do not reflectively autoDiscover MenuType.class because MenuType.<clinit> depends on Registry.MENU!
            } else if (RegistryKey.ENCHANTMENT.equals(registryKey)) {
                // Enchantment.<clinit> depends on this registry, so never reflect on it.
                fillVanillaEnchantments();
            } else if (RegistryKey.DAMAGE_TYPE.equals(registryKey) || "damage_type".equalsIgnoreCase(registryKey.key().value())) {
                // Do not reflectively autoDiscover DamageType.class because DamageType.<clinit> depends on Registry.DAMAGE_TYPE!
                populateDefaultDamageTypes();
            } else {
                Class<B> valueClass = (Class<B>) LegacyRegistryIdentifiers.KEY_TO_CLASS_MAP.get(registryKey);
                if (valueClass != null) {
                    Map<NamespacedKey, B> discovered = autoDiscover(valueClass);
                    entries.putAll(discovered);
                }
            }
        }
    }

    private static final String[] DEFAULT_DAMAGE_TYPES = {
        "arrow",
        "bad_respawn_point",
        "cactus",
        "campfire",
        "cramming",
        "dragon_breath",
        "drown",
        "dry_out",
        "ender_pearl",
        "explosion",
        "fall",
        "falling_anvil",
        "falling_block",
        "falling_stalactite",
        "fireball",
        "fireworks",
        "fly_into_wall",
        "freeze",
        "generic",
        "generic_kill",
        "hot_floor",
        "in_fire",
        "in_wall",
        "indirect_magic",
        "lava",
        "lightning_bolt",
        "mace_smash",
        "magic",
        "mob_attack",
        "mob_attack_no_aggro",
        "mob_projectile",
        "on_fire",
        "out_of_world",
        "outside_border",
        "player_attack",
        "player_explosion",
        "sonic_boom",
        "spear",
        "spit",
        "stalagmite",
        "starve",
        "sting",
        "sulfur_cube_hot",
        "sweet_berry_bush",
        "thorns",
        "thrown",
        "trident",
        "unattributed_fireball",
        "wind_charge",
        "wither",
        "wither_skull"
    };

    @SuppressWarnings("unchecked")
    private void populateDefaultDamageTypes() {
        for (String name : DEFAULT_DAMAGE_TYPES) {
            NamespacedKey key = NamespacedKey.minecraft(name);
            entries.put(key, (B) new PatchBukkitDamageType(key));
        }
    }

    @SuppressWarnings("unchecked")
    /** A minimal instance of a keyed registry interface: identity, key and translation key. */
    private static Object keyedHandle(Class<?> type, NamespacedKey key) {
        return java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
            (proxy, method, args) -> switch (method.getName()) {
                case "getKey", "key", "getKeyOrThrow", "getKeyOrNull" -> key;
                case "isRegistered" -> true;
                case "translationKey" -> type.getSimpleName().toLowerCase(java.util.Locale.ROOT) + "." + key.getNamespace() + "." + key.getKey();
                case "hashCode" -> key.hashCode();
                case "equals" -> args[0] == proxy;
                case "toString" -> type.getSimpleName() + "{" + key + "}";
                default -> {
                    Class<?> r = method.getReturnType();
                    if (!r.isPrimitive()) yield null;
                    if (r == boolean.class) yield false;
                    if (r == void.class) yield null;
                    yield r == long.class ? (Object) 0L : r == float.class ? (Object) 0f : r == double.class ? (Object) 0d : (Object) 0;
                }
            });
    }

    private static <B extends Keyed> Map<NamespacedKey, B> autoDiscover(Class<B> clazz) {
        Map<NamespacedKey, B> map = new LinkedHashMap<>();
        if (clazz == null) return map;
        try {
            if (clazz.isEnum()) {
                for (B enumConstant : clazz.getEnumConstants()) {
                    if (enumConstant != null && enumConstant.getKey() != null) {
                        map.put(enumConstant.getKey(), enumConstant);
                    }
                }
            }
            for (Field field : clazz.getFields()) {
                if (Modifier.isStatic(field.getModifiers()) && clazz.isAssignableFrom(field.getType())) {
                    try {
                        field.setAccessible(true);
                        Object val = field.get(null);
                        if (val != null && clazz.isInstance(val)) {
                            B item = clazz.cast(val);
                            if (item.getKey() != null) {
                                map.put(item.getKey(), item);
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return map;
    }

    @Override
    public @Nullable B get(NamespacedKey key) {
        if (key == null) {
            return null;
        }
        ensureInitialized();
        B value = entries.get(key);
        if (value != null) {
            return value;
        }
        if (RegistryKey.ENCHANTMENT.equals(registryKey)) {
            // Creating the first enchantment runs Enchantment.<clinit>, which reads this
            // registry before initialization has stored anything. Fill it now; the class
            // initializer is already running on this thread, so this does not recurse.
            fillVanillaEnchantments();
            value = entries.get(key);
            if (value != null) {
                return value;
            }
        }

        Set<NamespacedKey> fallbackSet = inFallback.get();
        if (!fallbackSet.add(key)) {
            return null;
        }
        try {
            // Fallback for SoundEvent
            if (RegistryKey.SOUND_EVENT.equals(registryKey) || "sound_event".equalsIgnoreCase(registryKey != null ? registryKey.key().value() : "")) {
                try {
                    PatchBukkitSound dynamicSound = new PatchBukkitSound(key.getKey(), entries.size());
                    entries.put(key, (B) dynamicSound);
                    return (B) dynamicSound;
                } catch (Throwable t) {
                    return null;
                }
            }
            // Fallback for ItemType via Material
            if (RegistryKey.ITEM.equals(registryKey) || "item".equalsIgnoreCase(registryKey != null ? registryKey.key().value() : "")) {
                try {
                    Material mat = Material.matchMaterial(key.getKey());
                    if (mat != null && mat.isItem()) {
                        B itemType = (B) PatchBukkitItemType.create(mat);
                        if (itemType != null) {
                            entries.put(key, itemType);
                            return itemType;
                        }
                    }
                } catch (Throwable ignored) {}
            }
            // Fallback for BlockType via Material
            if (RegistryKey.BLOCK.equals(registryKey) || "block".equalsIgnoreCase(registryKey != null ? registryKey.key().value() : "")) {
                try {
                    Material mat = Material.matchMaterial(key.getKey());
                    if (mat == null) {
                        mat = Material.matchMaterial(key.toString());
                    }
                    if (mat != null && mat.isLegacy()) {
                        mat = PatchBukkitLegacy.fromLegacy(mat);
                    }
                    if (mat != null && !mat.isLegacy()) {
                        B blockType = (B) PatchBukkitBlockType.create(mat);
                        if (blockType != null) {
                            entries.put(key, blockType);
                            return blockType;
                        }
                    }
                } catch (Throwable ignored) {}
            }
            // Fallback for MenuType via CraftMenuType
            if (RegistryKey.MENU.equals(registryKey) || "menu".equalsIgnoreCase(registryKey != null ? registryKey.key().value() : "")) {
                try {
                    var nmsIdent = org.bukkit.craftbukkit.util.CraftNamespacedKey.toMinecraft(key);
                    var holderOpt = net.minecraft.core.registries.BuiltInRegistries.MENU.get(nmsIdent);
                    if (holderOpt != null && holderOpt.isPresent()) {
                        B menuType = (B) new org.bukkit.craftbukkit.inventory.CraftMenuType<>(holderOpt.get());
                        entries.put(key, menuType);
                        return menuType;
                    }
                } catch (Throwable ignored) {}
            }
            // Fallback for DamageType
            if (RegistryKey.DAMAGE_TYPE.equals(registryKey) || "damage_type".equalsIgnoreCase(registryKey != null ? registryKey.key().value() : "")) {
                try {
                    PatchBukkitDamageType damageType = new PatchBukkitDamageType(key);
                    entries.put(key, (B) damageType);
                    return (B) damageType;
                } catch (Throwable ignored) {}
            }
            // Fallback for data-driven variant registries (Cow.Variant, Pig.Variant, Cat.Type, ...):
            // their constants are looked up through this registry in <clinit>, and there is no
            // NMS registry behind them, so stand in a keyed handle for vanilla keys.
            if (registryKey != null && "minecraft".equals(key.getNamespace())) {
                Class<?> valueClass = LegacyRegistryIdentifiers.KEY_TO_CLASS_MAP.get(registryKey);
                if (valueClass != null && valueClass.isInterface()) {
                    B handle = (B) keyedHandle(valueClass, key);
                    entries.put(key, handle);
                    return handle;
                }
            }
        } finally {
            fallbackSet.remove(key);
        }

        return null;
    }

    @Override
    public @Nullable NamespacedKey getKey(B value) {
        return value != null ? value.getKey() : null;
    }

    @Override
    public boolean hasTag(TagKey<B> key) {
        ensureInitialized();
        return key != null && tags.containsKey(key.key().asString());
    }

    @Override
    public @NonNull Tag<B> getTag(TagKey<B> key) {
        ensureInitialized();
        PatchBukkitTag<B> tag = tags.get(key.key().asString());
        if (tag == null) {
            throw new NoSuchElementException("Unknown tag: " + key.key().asString());
        }
        return tag;
    }

    public static <B extends Keyed> PatchBukkitRegistry<Object, B> empty(RegistryKey<B> registryKey) {
        return new PatchBukkitRegistry<>(registryKey);
    }

    @Override
    public @NonNull Collection<Tag<B>> getTags() {
        ensureInitialized();
        return Collections.unmodifiableCollection(tags.values());
    }

    @Override
    public @NonNull Stream<B> stream() {
        ensureInitialized();
        return entries.values().stream();
    }

    public @NonNull Stream<NamespacedKey> keyStream() {
        ensureInitialized();
        return entries.keySet().stream();
    }

    @Override
    public int size() {
        ensureInitialized();
        return entries.size();
    }

    @Override
    public @NonNull Iterator<B> iterator() {
        ensureInitialized();
        return Collections.unmodifiableCollection(entries.values()).iterator();
    }
}
