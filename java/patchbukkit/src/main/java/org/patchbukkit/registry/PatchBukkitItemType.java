package org.patchbukkit.registry;

import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

public final class PatchBukkitItemType {

    private PatchBukkitItemType() {}

    public static ItemType create(Material material) {
        if (material == null) return null;

        return (ItemType) Proxy.newProxyInstance(
                PatchBukkitItemType.class.getClassLoader(),
                new Class<?>[]{ItemType.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("getKey".equals(name) || "key".equals(name)) {
                        return material.getKey();
                    }
                    if ("asMaterial".equals(name)) {
                        return material;
                    }
                    // Paper's Material delegates these to ItemType, so they must not call back into Material.
                    switch (name) {
                        case "getMaxStackSize": {
                            var data = PatchBukkitTypeData.item(material);
                            return data.getFound() ? data.getMaxStackSize() : 64;
                        }
                        case "getMaxDurability":
                            return (short) PatchBukkitTypeData.item(material).getMaxDurability();
                        case "isEdible":
                            return PatchBukkitTypeData.item(material).getEdible();
                        case "isRecord":
                            return PatchBukkitTypeData.item(material).getRecord();
                        case "isFuel":
                            return PatchBukkitTypeData.item(material).getFuel();
                        case "isCompostable":
                            return PatchBukkitTypeData.item(material).getCompostChance() > 0;
                        case "getCompostChance": {
                            float chance = PatchBukkitTypeData.item(material).getCompostChance();
                            if (chance <= 0) {
                                throw new IllegalArgumentException(material.getKey() + " is not compostable");
                            }
                            return chance;
                        }
                        case "getTranslationKey":
                        case "translationKey": {
                            String key = PatchBukkitTypeData.item(material).getTranslationKey();
                            return key.isEmpty() ? "item." + material.getKey().getNamespace() + "." + material.getKey().getKey() : key;
                        }
                        default:
                            break;
                    }
                    if ("createItemStack".equals(name)) {
                        int amount = (args != null && args.length > 0 && args[0] instanceof Integer i) ? i : 1;
                        return new org.patchbukkit.inventory.PatchBukkitItemStack(material, amount);
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
                        return "ItemType{" + material.getKey() + "}";
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
