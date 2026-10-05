package org.patchbukkit.registry;

import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.block.BlockType;
import org.bukkit.block.data.BlockData;
import org.patchbukkit.PatchBukkitBlockData;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.function.Consumer;

public final class PatchBukkitBlockType {

    private PatchBukkitBlockType() {}

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
                    // Paper's Material delegates these to BlockType, so they must not call back into Material.
                    switch (name) {
                        case "isSolid":
                            return PatchBukkitTypeData.block(material).getSolid();
                        case "isAir":
                            return PatchBukkitTypeData.block(material).getAir();
                        case "isBurnable":
                            return PatchBukkitTypeData.block(material).getBurnable();
                        case "isFlammable":
                            return PatchBukkitTypeData.block(material).getFlammable();
                        case "isOccluding":
                            return PatchBukkitTypeData.block(material).getOccluding();
                        case "hasCollision":
                            return PatchBukkitTypeData.block(material).getHasCollision();
                        case "getHardness":
                            return PatchBukkitTypeData.block(material).getHardness();
                        case "getBlastResistance":
                            return PatchBukkitTypeData.block(material).getBlastResistance();
                        case "getSlipperiness":
                            return PatchBukkitTypeData.block(material).getSlipperiness();
                        case "translationKey":
                        case "getTranslationKey": {
                            String key = PatchBukkitTypeData.block(material).getTranslationKey();
                            return key.isEmpty() ? "block." + material.getKey().getNamespace() + "." + material.getKey().getKey() : key;
                        }
                        default:
                            break;
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
