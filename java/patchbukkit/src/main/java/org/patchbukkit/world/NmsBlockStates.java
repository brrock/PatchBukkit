package org.patchbukkit.world;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.World;

/**
 * Converts Pumpkin block states (as Bukkit block data strings) into vanilla block states, for
 * plugins that read NMS state through CraftBlock.
 */
public final class NmsBlockStates {

    private static final Map<String, BlockState> CACHE = new ConcurrentHashMap<>();

    private NmsBlockStates() {}

    public static BlockState fromString(String blockState) {
        return CACHE.computeIfAbsent(blockState, s -> {
            try {
                return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, s, false).blockState();
            } catch (Exception e) {
                return Blocks.AIR.defaultBlockState();
            }
        });
    }

    public static BlockState at(World world, int x, int y, int z) {
        return fromString(world.getBlockData(x, y, z).getAsString());
    }

    /**
     * A read-only view of a world as a vanilla {@link LevelAccessor}. Only block state reads are
     * backed by Pumpkin; anything else throws {@link UnsupportedOperationException}.
     */
    public static LevelAccessor levelAccessor(World world) {
        return (LevelAccessor) Proxy.newProxyInstance(
            NmsBlockStates.class.getClassLoader(),
            new Class<?>[]{LevelAccessor.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getBlockState" -> {
                    BlockPos pos = (BlockPos) args[0];
                    yield at(world, pos.getX(), pos.getY(), pos.getZ());
                }
                case "getFluidState" -> {
                    BlockPos pos = (BlockPos) args[0];
                    yield at(world, pos.getX(), pos.getY(), pos.getZ()).getFluidState();
                }
                case "getMinY" -> world.getMinHeight();
                case "getHeight" -> world.getMaxHeight() - world.getMinHeight();
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "PatchBukkitLevelAccessor[" + world.getName() + "]";
                default -> throw new UnsupportedOperationException(
                    "LevelAccessor." + method.getName() + " is not available on PatchBukkit");
            });
    }
}
