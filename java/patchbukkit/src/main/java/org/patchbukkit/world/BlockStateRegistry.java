package org.patchbukkit.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;
import patchbukkit.bridge.NativeBridgeFfi;
import patchbukkit.common.EmptyRequest;
import patchbukkit.world.GetBlockStateRegistryResponse;

/**
 * The server's block state palette, fetched from Pumpkin once: maps between global state ids and
 * {@code minecraft:block[prop=value,...]} strings, and knows each block's default state and
 * property values.
 */
public final class BlockStateRegistry {

    /** Default state of {@link #FLAG_AIR}: the block is air. */
    public static final int FLAG_AIR = 1;
    /** The default state is solid ({@code Material#isSolid}). */
    public static final int FLAG_SOLID = 1 << 1;
    /** The default state is an opaque full cube ({@code Material#isOccluding}). */
    public static final int FLAG_OCCLUDING = 1 << 2;
    /** The block can burn ({@code Material#isBurnable}). */
    public static final int FLAG_BURNABLE = 1 << 3;

    /**
     * A block's properties, in the order the server lists them, with their values in order, and
     * the {@code FLAG_*} bits of its default state.
     */
    public record BlockInfo(String key, int defaultStateId, int flags, Map<String, List<String>> properties) {}

    private static volatile BlockStateRegistry instance;

    private final String[] states;
    private final Map<String, Integer> idByState;
    private final Map<String, BlockInfo> blocks;

    private BlockStateRegistry(GetBlockStateRegistryResponse response) {
        int count = response.getStatesCount();
        this.states = response.getStatesList().toArray(new String[0]);
        this.idByState = new HashMap<>(count * 2);
        for (int id = 0; id < count; id++) {
            this.idByState.put(this.states[id], id);
        }

        Map<String, Map<String, List<String>>> props = new HashMap<>();
        for (String state : this.states) {
            int bracket = state.indexOf('[');
            String key = bracket < 0 ? state : state.substring(0, bracket);
            Map<String, List<String>> blockProps = props.computeIfAbsent(key, k -> new LinkedHashMap<>());
            if (bracket < 0) {
                continue;
            }
            for (String pair : state.substring(bracket + 1, state.length() - 1).split(",")) {
                int eq = pair.indexOf('=');
                List<String> values = blockProps.computeIfAbsent(pair.substring(0, eq), k -> new ArrayList<>());
                String value = pair.substring(eq + 1);
                if (!values.contains(value)) {
                    values.add(value);
                }
            }
        }

        this.blocks = new HashMap<>();
        List<Integer> defaultIds = response.getDefaultStateIdsList();
        for (int i = 0; i < defaultIds.size(); i++) {
            int defaultId = defaultIds.get(i);
            int flags = i < response.getDefaultBlockFlagsCount() ? response.getDefaultBlockFlags(i) : 0;
            String key = blockKey(this.states[defaultId]);
            Map<String, List<String>> blockProps = new LinkedHashMap<>();
            props.getOrDefault(key, Map.of()).forEach((name, values) -> blockProps.put(name, List.copyOf(values)));
            this.blocks.put(key, new BlockInfo(key, defaultId, flags, Collections.unmodifiableMap(blockProps)));
        }
    }

    public static BlockStateRegistry get() {
        BlockStateRegistry registry = instance;
        if (registry == null) {
            synchronized (BlockStateRegistry.class) {
                registry = instance;
                if (registry == null) {
                    GetBlockStateRegistryResponse response =
                        NativeBridgeFfi.getBlockStateRegistry(EmptyRequest.getDefaultInstance());
                    if (response == null || response.getStatesCount() == 0) {
                        throw new IllegalStateException("Pumpkin did not return the block state registry");
                    }
                    registry = new BlockStateRegistry(response);
                    instance = registry;
                }
            }
        }
        return registry;
    }

    public int size() {
        return this.states.length;
    }

    /** The {@code minecraft:block[...]} string of a state id, or null if out of range. */
    public @Nullable String toString(int stateId) {
        return stateId >= 0 && stateId < this.states.length ? this.states[stateId] : null;
    }

    public @Nullable BlockInfo block(String key) {
        return this.blocks.get(normalizeKey(key));
    }

    /**
     * Resolves block state text, filling every property that isn't given from the block's
     * default state like {@code Bukkit.createBlockData(String)} does.
     *
     * @return the state id, or -1 if the block or a property value is unknown
     */
    public int toId(String text) {
        Integer exact = this.idByState.get(text);
        if (exact != null) {
            return exact;
        }
        String trimmed = text.trim();
        int bracket = trimmed.indexOf('[');
        BlockInfo info = block(bracket < 0 ? trimmed : trimmed.substring(0, bracket));
        if (info == null) {
            return -1;
        }
        if (bracket < 0 || info.properties().isEmpty()) {
            return info.defaultStateId();
        }
        if (!trimmed.endsWith("]")) {
            return -1;
        }

        Map<String, String> values = parseProperties(this.states[info.defaultStateId()]);
        String body = trimmed.substring(bracket + 1, trimmed.length() - 1).trim();
        if (!body.isEmpty()) {
            for (String pair : body.split(",")) {
                int eq = pair.indexOf('=');
                if (eq < 0) {
                    return -1;
                }
                String name = pair.substring(0, eq).trim();
                String value = pair.substring(eq + 1).trim();
                List<String> allowed = info.properties().get(name);
                if (allowed == null || !allowed.contains(value)) {
                    return -1;
                }
                values.put(name, value);
            }
        }

        StringBuilder canonical = new StringBuilder(info.key()).append('[');
        boolean first = true;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!first) {
                canonical.append(',');
            }
            first = false;
            canonical.append(entry.getKey()).append('=').append(entry.getValue());
        }
        Integer id = this.idByState.get(canonical.append(']').toString());
        return id != null ? id : -1;
    }

    /** Property values of a state string, in order. */
    public static Map<String, String> parseProperties(String state) {
        Map<String, String> values = new LinkedHashMap<>();
        int bracket = state.indexOf('[');
        if (bracket < 0 || !state.endsWith("]")) {
            return values;
        }
        String body = state.substring(bracket + 1, state.length() - 1);
        if (body.isEmpty()) {
            return values;
        }
        for (String pair : body.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                values.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return values;
    }

    public static String blockKey(String state) {
        int bracket = state.indexOf('[');
        return bracket < 0 ? state : state.substring(0, bracket);
    }

    private static String normalizeKey(String key) {
        String trimmed = key.trim();
        return trimmed.indexOf(':') < 0 ? "minecraft:" + trimmed : trimmed;
    }
}
