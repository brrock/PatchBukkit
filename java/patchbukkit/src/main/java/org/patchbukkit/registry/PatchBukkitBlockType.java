package org.patchbukkit.registry;

import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.block.BlockType;
import org.bukkit.block.data.BlockData;
import org.patchbukkit.PatchBukkitBlockData;
import org.patchbukkit.world.BlockStateRegistry;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.function.Consumer;

public final class PatchBukkitBlockType {

    private PatchBukkitBlockType() {}

    /** Whether the block's default state has the given {@link BlockStateRegistry} flag. */
    private static boolean hasFlag(Material material, int flag) {
        try {
            BlockStateRegistry.BlockInfo info = BlockStateRegistry.get().block(material.getKey().toString());
            return info != null && (info.flags() & flag) != 0;
        } catch (Throwable t) {
            // The native bridge isn't up (e.g. unit tests).
            return false;
        }
    }

    /** Blocks that fall when unsupported; the server doesn't report this yet. */
    private static boolean hasGravity(Material material) {
        String name = material.name();
        return name.equals("SAND") || name.equals("RED_SAND") || name.equals("GRAVEL") || name.equals("DRAGON_EGG")
            || name.endsWith("_CONCRETE_POWDER") || name.endsWith("ANVIL");
    }

    public static BlockType create(Material material) {
        if (material == null || material.isLegacy()) return null;

        return (BlockType) Proxy.newProxyInstance(
                PatchBukkitBlockType.class.getClassLoader(),
                new Class<?>[]{BlockType.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("getKey".equals(name) || "key".equals(name)) {
                        return material.getKey();
                    }
                    if ("asMaterial".equals(name)) {
                        return material;
                    }
                    if ("createBlockData".equals(name)) {
                        BlockType self = (BlockType) proxy;
                        if (args == null || args.length == 0) {
                            return PatchBukkitBlockData.newData(material, self, null);
                        } else if (args.length == 1 && args[0] instanceof String s) {
                            return PatchBukkitBlockData.newData(material, self, s);
                        } else if (args.length == 1 && args[0] instanceof Consumer consumer) {
                            BlockData data = PatchBukkitBlockData.newData(material, self, null);
                            consumer.accept(data);
                            return data;
                        }
                    }
                    if ("createBlockDataStates".equals(name)) {
                        BlockType self = (BlockType) proxy;
                        return Collections.singletonList(PatchBukkitBlockData.newData(material, self, null));
                    }
                    if ("getBlockDataClass".equals(name)) {
                        return BlockData.class;
                    }
                    if ("getItemType".equals(name)) {
                        return material.isItem() ? PatchBukkitItemType.create(material) : null;
                    }
                    if ("hasItemType".equals(name)) {
                        return material.isItem();
                    }
                    // Material's own isAir/isSolid/... delegate to this proxy, so answer from the
                    // server's block data instead of calling back into Material.
                    if ("isSolid".equals(name)) {
                        return hasFlag(material, BlockStateRegistry.FLAG_SOLID);
                    }
                    if ("isAir".equals(name)) {
                        return material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR;
                    }
                    if ("isBurnable".equals(name)) {
                        return hasFlag(material, BlockStateRegistry.FLAG_BURNABLE);
                    }
                    if ("isEdible".equals(name)) {
                        return material.isEdible();
                    }
                    if ("isOccluding".equals(name)) {
                        return hasFlag(material, BlockStateRegistry.FLAG_OCCLUDING);
                    }
                    if ("hasGravity".equals(name) || "isGravity".equals(name)) {
                        return hasGravity(material);
                    }
                    if ("translationKey".equals(name) || "getTranslationKey".equals(name)) {
                        return material.getTranslationKey();
                    }
                    if ("typed".equals(name)) {
                        return proxy;
                    }
                    if ("equals".equals(name) && args != null && args.length == 1) {
                        if (args[0] instanceof Keyed k) {
                            return material.getKey().equals(k.getKey());
                        }
                        return false;
                    }
                    if ("hashCode".equals(name)) {
                        return material.getKey().hashCode();
                    }
                    if ("toString".equals(name)) {
                        return "BlockType{" + material.getKey() + "}";
                    }
                    if (method.isDefault()) {
                        return InvocationHandler.invokeDefault(proxy, method, args);
                    }

                    Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class) return false;
                    if (returnType == int.class || returnType == short.class || returnType == long.class || returnType == byte.class) return 0;
                    if (returnType == float.class || returnType == double.class) return 0.0f;
                    return null;
                }
        );
    }
}
