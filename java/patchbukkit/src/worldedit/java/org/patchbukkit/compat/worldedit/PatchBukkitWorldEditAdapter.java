package org.patchbukkit.compat.worldedit;

import com.sk89q.worldedit.blocks.BaseItemStack;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.bukkit.adapter.BukkitImplAdapter;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.internal.wna.WorldNativeAccess;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.registry.state.BooleanProperty;
import com.sk89q.worldedit.registry.state.DirectionalProperty;
import com.sk89q.worldedit.registry.state.EnumProperty;
import com.sk89q.worldedit.registry.state.IntegerProperty;
import com.sk89q.worldedit.registry.state.Property;
import com.sk89q.worldedit.util.Direction;
import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.util.formatting.text.Component;
import com.sk89q.worldedit.util.formatting.text.TextComponent;
import com.sk89q.worldedit.util.formatting.text.TranslatableComponent;
import com.sk89q.worldedit.world.DataFixer;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;
import com.sk89q.worldedit.world.entity.EntityTypes;
import com.sk89q.worldedit.world.item.ItemType;
import com.sk89q.worldedit.world.item.ItemTypes;
import com.sk89q.worldedit.world.registry.BlockMaterial;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.enginehub.linbus.tree.LinCompoundTag;
import org.patchbukkit.world.BlockStateRegistry;
import org.patchbukkit.world.BulkBlockAccess;

/**
 * WorldEdit's platform adapter for PatchBukkit, used in place of WorldEdit's NMS adapters (which
 * need a real CraftBukkit server). Blocks, block properties and biomes come from Pumpkin through
 * {@link BlockStateRegistry} and {@link BulkBlockAccess}, so edits are batched across the native
 * bridge instead of costing one call per block.
 *
 * <p>Selected with the {@code worldedit.bukkit.adapter} system property, which PatchBukkit sets
 * at startup. The class lives under {@code org.patchbukkit.compat} so it is defined in
 * WorldEdit's own class loader.
 */
public final class PatchBukkitWorldEditAdapter implements BukkitImplAdapter {

    private static final Set<SideEffect> SUPPORTED_SIDE_EFFECTS = Collections.unmodifiableSet(
        EnumSet.of(SideEffect.NEIGHBORS, SideEffect.LIGHTING, SideEffect.UPDATE));
    private static final Set<String> DIRECTION_NAMES = Set.of("north", "east", "south", "west", "up", "down");

    private final BlockStateRegistry registry = BlockStateRegistry.get();
    private final BlockState[] statesById = new BlockState[this.registry.size()];
    private final Map<BlockState, Integer> idsByState = new ConcurrentHashMap<>();
    private final Map<String, Property<?>> propertyCache = new ConcurrentHashMap<>();

    @Override
    public DataFixer getDataFixer() {
        // Pumpkin has no DataFixerUpper, so schematics from other Minecraft versions aren't upgraded.
        return null;
    }

    @Override
    public BlockState getBlock(Location location) {
        return stateFor(BulkBlockAccess.of(location.getWorld())
            .getStateId(location.getBlockX(), location.getBlockY(), location.getBlockZ()));
    }

    @Override
    public BaseBlock getFullBlock(Location location) {
        return getBlock(location).toBaseBlock();
    }

    @Override
    public WorldNativeAccess<?, ?, ?> createWorldNativeAccess(World world) {
        return new PatchBukkitWorldNativeAccess(this, BulkBlockAccess.of(world));
    }

    @Override
    public BaseEntity getEntity(Entity entity) {
        com.sk89q.worldedit.world.entity.EntityType type = EntityTypes.get(entity.getType().getKey().toString());
        return type != null ? new BaseEntity(type) : null;
    }

    @Override
    public Entity createEntity(Location location, BaseEntity state) {
        NamespacedKey key = NamespacedKey.fromString(state.getType().id());
        org.bukkit.entity.EntityType type = key != null ? Registry.ENTITY_TYPE.get(key) : null;
        if (type == null || !type.isSpawnable()) {
            return null;
        }
        return location.getWorld().spawnEntity(location, type);
    }

    @Override
    public Component getRichBlockName(BlockType blockType) {
        return translatable("block", blockType.id());
    }

    @Override
    public Component getRichItemName(ItemType itemType) {
        Material material = BukkitAdapter.adapt(itemType);
        // Block items use the block's name, as in vanilla.
        return translatable(material != null && material.isBlock() ? "block" : "item", itemType.id());
    }

    @Override
    public Component getRichItemName(BaseItemStack itemStack) {
        return getRichItemName(itemStack.getType());
    }

    @Override
    public BlockMaterial getBlockMaterial(BlockType blockType) {
        Material material = BukkitAdapter.adapt(blockType);
        if (material == null) {
            return null;
        }
        boolean occluding = safe(material::isOccluding);
        boolean solid = safe(material::isSolid);
        float hardness = 0;
        float resistance = 0;
        float slipperiness = 0.6f;
        try {
            hardness = material.getHardness();
            resistance = material.getBlastResistance();
            slipperiness = material.getSlipperiness();
        } catch (Throwable ignored) {
            // Keep vanilla's defaults when the server can't answer.
        }
        return new PatchBukkitBlockMaterial(material.isAir(), occluding, occluding,
            material == Material.WATER || material == Material.LAVA, solid,
            hardness, resistance, slipperiness, safe(material::isBurnable), !occluding);
    }

    @Override
    public Map<String, ? extends Property<?>> getProperties(BlockType blockType) {
        BlockStateRegistry.BlockInfo info = this.registry.block(blockType.id());
        Map<String, Property<?>> properties = new TreeMap<>();
        if (info != null) {
            info.properties().forEach((name, values) ->
                properties.put(name, this.propertyCache.computeIfAbsent(name + values, k -> createProperty(name, values))));
        }
        return properties;
    }

    @Override
    public void sendFakeNBT(Player player, BlockVector3 pos, LinCompoundTag nbtData) {
        // Only used for the CUI structure-block outline; there's no block entity bridge for it.
    }

    @Override
    public void sendFakeOP(Player player) {
    }

    @Override
    public boolean canPlaceAt(World world, BlockVector3 position, BlockState blockState) {
        return true;
    }

    @Override
    public ItemStack adapt(BaseItemStack item) {
        Material material = Material.matchMaterial(item.getType().id());
        return new ItemStack(material != null ? material : Material.AIR, item.getAmount());
    }

    @Override
    public BaseItemStack adapt(ItemStack itemStack) {
        ItemType type = ItemTypes.get(itemStack.getType().getKey().toString());
        return new BaseItemStack(type != null ? type : ItemTypes.AIR, itemStack.getAmount());
    }

    @Override
    public Set<SideEffect> getSupportedSideEffects() {
        return SUPPORTED_SIDE_EFFECTS;
    }

    @Override
    public OptionalInt getInternalBlockStateId(BlockData data) {
        int id = this.registry.toId(data.getAsString());
        return id >= 0 ? OptionalInt.of(id) : OptionalInt.empty();
    }

    @Override
    public OptionalInt getInternalBlockStateId(BlockState state) {
        int id = idFor(state);
        return id >= 0 ? OptionalInt.of(id) : OptionalInt.empty();
    }

    @Override
    public boolean clearContainerBlockContents(World world, BlockVector3 pt) {
        // Pumpkin drops a replaced block entity without spilling its contents when PatchBukkit
        // sets blocks in bulk, so there's nothing to clear first.
        return false;
    }

    @Override
    public BiomeType getBiome(Location location) {
        String key = BulkBlockAccess.of(location.getWorld())
            .getBiome(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        BiomeType biome = BiomeType.REGISTRY.get(key);
        return biome != null ? biome : BiomeType.REGISTRY.get("minecraft:plains");
    }

    @Override
    public void setBiome(Location location, BiomeType biome) {
        BulkBlockAccess.of(location.getWorld())
            .setBiome(location.getBlockX(), location.getBlockY(), location.getBlockZ(), biome.id());
    }

    @Override
    public void sendBiomeUpdates(World world, Iterable<BlockVector2> chunks) {
        List<long[]> coords = new ArrayList<>();
        for (BlockVector2 chunk : chunks) {
            coords.add(new long[] {chunk.x(), chunk.z()});
        }
        BulkBlockAccess.of(world).refreshChunks(coords);
    }

    @Override
    public void initializeRegistries() {
        // Registry.BIOME has no entries on PatchBukkit, so take the biome list from the server.
        for (String key : this.registry.biomes()) {
            if (BiomeType.REGISTRY.get(key) == null) {
                BiomeType.REGISTRY.register(key, new BiomeType(key));
            }
        }
    }

    BlockState stateFor(int stateId) {
        if (stateId < 0 || stateId >= this.statesById.length) {
            return BlockTypes.AIR.getDefaultState();
        }
        BlockState state = this.statesById[stateId];
        if (state == null) {
            state = parseState(stateId);
            this.statesById[stateId] = state;
            this.idsByState.putIfAbsent(state, stateId);
        }
        return state;
    }

    int idFor(BlockState state) {
        Integer cached = this.idsByState.get(state);
        if (cached != null) {
            return cached;
        }
        int id = this.registry.toId(state.getAsString());
        if (id < 0) {
            id = this.registry.toId(state.getBlockType().id());
        }
        if (id >= 0) {
            this.idsByState.put(state, id);
        }
        return id;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private BlockState parseState(int stateId) {
        String text = this.registry.toString(stateId);
        BlockType type = BlockTypes.get(BlockStateRegistry.blockKey(text));
        if (type == null) {
            return BlockTypes.AIR.getDefaultState();
        }
        BlockState state = type.getDefaultState();
        for (Map.Entry<String, String> entry : BlockStateRegistry.parseProperties(text).entrySet()) {
            Property property = type.getProperty(entry.getKey());
            if (property != null) {
                state = state.with(property, property.getValueFor(entry.getValue()));
            }
        }
        return state;
    }

    /** Vanilla's description id, e.g. {@code block.minecraft.stone} for {@code minecraft:stone}. */
    private static Component translatable(String kind, String id) {
        NamespacedKey key = NamespacedKey.fromString(id);
        return key != null
            ? TranslatableComponent.of(kind + "." + key.getNamespace() + "." + key.getKey().replace('/', '.'))
            : TextComponent.of(id);
    }

    private static Property<?> createProperty(String name, List<String> values) {
        if (values.stream().allMatch(v -> v.equals("true") || v.equals("false"))) {
            return new BooleanProperty(name, values.stream().map(Boolean::parseBoolean).toList());
        }
        if (values.stream().allMatch(v -> v.chars().allMatch(Character::isDigit))) {
            return new IntegerProperty(name, values.stream().map(Integer::parseInt).toList());
        }
        // Vanilla's EnumProperty<Direction> is the only enum whose values are all direction names.
        if (values.stream().allMatch(DIRECTION_NAMES::contains)) {
            return new DirectionalProperty(name,
                values.stream().map(v -> Direction.valueOf(v.toUpperCase(Locale.ROOT))).toList());
        }
        return new EnumProperty(name, values);
    }

    private static boolean safe(Supplier<Boolean> check) {
        try {
            return check.get();
        } catch (Throwable t) {
            return false;
        }
    }
}
