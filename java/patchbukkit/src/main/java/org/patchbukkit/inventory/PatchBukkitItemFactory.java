package org.patchbukkit.inventory;

import java.lang.reflect.Proxy;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class PatchBukkitItemFactory {
    public static final ItemFactory INSTANCE = createFactory();

    private static ItemFactory createFactory() {
        return (ItemFactory) Proxy.newProxyInstance(
            ItemFactory.class.getClassLoader(),
            new Class<?>[] { ItemFactory.class },
            (proxy, method, args) -> {
                String name = method.getName();
                if ("getItemMeta".equals(name)) {
                    return args[0] instanceof Material mat ? PatchBukkitItemMeta.create(mat) : null;
                }
                if ("asMetaFor".equals(name)) {
                    Material target = args[1] instanceof ItemStack stack ? stack.getType() : (Material) args[1];
                    return PatchBukkitItemMeta.convert((ItemMeta) args[0], target);
                }
                if ("isApplicable".equals(name)) {
                    Material target = args[1] instanceof ItemStack stack ? stack.getType() : (Material) args[1];
                    return args[0] == null || (target != null && !target.isAir() && PatchBukkitItemMeta.handler((ItemMeta) args[0]) != null);
                }
                if ("equals".equals(name)) {
                    if (args != null && args.length == 2) {
                        return metaEquals((ItemMeta) args[0], (ItemMeta) args[1]);
                    }
                    return false;
                }
                if ("hashCode".equals(name) && (args == null || args.length == 0)) {
                    return System.identityHashCode(proxy);
                }
                if ("ensureServerConform".equals(name)) {
                    if (args != null && args.length > 0 && args[0] instanceof ItemStack stack) {
                        if (stack instanceof PatchBukkitItemStack) {
                            return stack;
                        }
                        PatchBukkitItemStack conform = new PatchBukkitItemStack(stack.getType(), stack.getAmount());
                        if (stack.hasItemMeta()) {
                            conform.setItemMeta(stack.getItemMeta());
                        }
                        return conform;
                    }
                    return new PatchBukkitItemStack(Material.AIR, 0);
                }
                if ("isItemEmpty".equals(name)) {
                    if (args != null && args.length > 0 && args[0] instanceof ItemStack stack) {
                        return PatchBukkitPlayerInventory.isItemEmpty(stack);
                    }
                    return true;
                }
                Class<?> returnType = method.getReturnType();
                if (returnType == boolean.class) return false;
                if (returnType == int.class) return 0;
                if (returnType == long.class) return 0L;
                if (returnType == double.class || returnType == float.class) return 0.0;
                return null;
            }
        );
    }

    private static boolean metaEquals(ItemMeta first, ItemMeta second) {
        PatchBukkitItemMeta a = PatchBukkitItemMeta.handler(first);
        PatchBukkitItemMeta b = PatchBukkitItemMeta.handler(second);
        boolean aEmpty = a == null || a.isEmpty();
        boolean bEmpty = b == null || b.isEmpty();
        if (aEmpty || bEmpty) {
            return aEmpty == bEmpty;
        }
        return first.equals(second);
    }
}
