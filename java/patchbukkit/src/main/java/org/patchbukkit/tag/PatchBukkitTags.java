package org.patchbukkit.tag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import patchbukkit.bridge.NativeBridgeFfi;
import patchbukkit.registry.GetTagsRequest;
import patchbukkit.registry.GetTagsResponse;
import patchbukkit.registry.TagEntry;

/**
 * Bukkit tags backed by the vanilla tag data Pumpkin ships with.
 */
public final class PatchBukkitTags {

    private static final Logger LOGGER = Logger.getLogger("PatchBukkit");

    /** Cached lookups, keyed by registry, value class and tag key. Absent tags are cached as empty. */
    private static final Map<String, Optional<Tag<?>>> CACHE = new ConcurrentHashMap<>();

    private PatchBukkitTags() {}

    /** Maps a Bukkit tag registry name ({@code Tag.REGISTRY_*}) to the vanilla registry name. */
    static String vanillaRegistry(String bukkitRegistry) {
        return switch (bukkitRegistry) {
            case Tag.REGISTRY_BLOCKS -> "block";
            case Tag.REGISTRY_ITEMS -> "item";
            case Tag.REGISTRY_FLUIDS -> "fluid";
            case Tag.REGISTRY_ENTITY_TYPES -> "entity_type";
            case Tag.REGISTRY_GAME_EVENTS -> "game_event";
            case "damage_types" -> "damage_type";
            case "enchantments" -> "enchantment";
            case "biomes" -> "worldgen/biome";
            default -> bukkitRegistry.endsWith("s") ? bukkitRegistry.substring(0, bukkitRegistry.length() - 1) : bukkitRegistry;
        };
    }

    @SuppressWarnings("unchecked")
    public static <T extends Keyed> @Nullable Tag<T> getTag(@NotNull String registry, @NotNull NamespacedKey key, @NotNull Class<T> clazz) {
        String cacheKey = registry + "|" + clazz.getName() + "|" + key;
        Optional<Tag<?>> cached = CACHE.get(cacheKey);
        if (cached != null) {
            return (Tag<T>) cached.orElse(null);
        }
        GetTagsResponse response;
        try {
            response = NativeBridgeFfi.getTags(GetTagsRequest.newBuilder()
                .setRegistry(vanillaRegistry(registry))
                .setTag(key.toString())
                .build());
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Failed to look up tag " + key + " in " + registry, t);
            return null;
        }
        Tag<T> tag = null;
        if (response != null && response.getTagsCount() > 0) {
            tag = toTag(key, response.getTags(0), clazz);
        }
        CACHE.put(cacheKey, Optional.ofNullable(tag));
        return tag;
    }

    public static <T extends Keyed> @NotNull Iterable<Tag<T>> getTags(@NotNull String registry, @NotNull Class<T> clazz) {
        GetTagsResponse response;
        try {
            response = NativeBridgeFfi.getTags(GetTagsRequest.newBuilder()
                .setRegistry(vanillaRegistry(registry))
                .build());
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Failed to list tags of " + registry, t);
            return Collections.emptyList();
        }
        if (response == null) {
            return Collections.emptyList();
        }
        List<Tag<T>> tags = new ArrayList<>(response.getTagsCount());
        for (TagEntry entry : response.getTagsList()) {
            NamespacedKey key = NamespacedKey.fromString(entry.getKey());
            if (key == null) continue;
            Tag<T> tag = toTag(key, entry, clazz);
            CACHE.putIfAbsent(registry + "|" + clazz.getName() + "|" + key, Optional.of(tag));
            tags.add(tag);
        }
        return tags;
    }

    private static <T extends Keyed> Tag<T> toTag(NamespacedKey key, TagEntry entry, Class<T> clazz) {
        Set<T> values = new LinkedHashSet<>();
        for (String raw : entry.getValuesList()) {
            T value = resolve(raw, clazz);
            if (value != null) {
                values.add(value);
            }
        }
        return new PatchBukkitTag<>(key, Collections.unmodifiableSet(values));
    }

    @SuppressWarnings("unchecked")
    private static <T extends Keyed> @Nullable T resolve(String raw, Class<T> clazz) {
        if (clazz == Material.class) {
            return (T) Material.matchMaterial(raw);
        }
        NamespacedKey key = NamespacedKey.fromString(raw.toLowerCase(Locale.ROOT));
        if (key == null) return null;
        try {
            Registry<T> registry = Bukkit.getRegistry(clazz);
            return registry != null ? registry.get(key) : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
