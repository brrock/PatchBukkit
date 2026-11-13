package org.patchbukkit.inventory;

import org.bukkit.inventory.ItemStack;

public class CraftItemStack extends ItemStack {

    public CraftItemStack() {
        super();
    }

    public static net.minecraft.world.item.ItemStack asNMSCopy(ItemStack original) {
        try {
            return org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(original);
        } catch (Throwable t) {
            return null;
        }
    }

    public static ItemStack asBukkitCopy(net.minecraft.world.item.ItemStack original) {
        try {
            return org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy(original);
        } catch (Throwable t) {
            return null;
        }
    }

    public static ItemStack asCraftMirror(net.minecraft.world.item.ItemStack original) {
        try {
            return org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitMirror(original);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Called by transformed plugin code before a cast to CraftBukkit's CraftItemStack: Paper
     * hands out CraftItemStacks, PatchBukkit hands out plain Bukkit stacks, so convert them.
     */
    public static Object toCraftItemStack(Object stack) {
        if (stack instanceof ItemStack bukkit && !(stack instanceof org.bukkit.craftbukkit.inventory.CraftItemStack)) {
            try {
                return org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitMirror(
                    org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(bukkit));
            } catch (Throwable ignored) {
                // No NMS item to mirror here. Hand back an empty CraftItemStack (handle == null), which
                // CraftBukkit callers already treat as "no item", so the cast succeeds.
                try {
                    java.lang.reflect.Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                    theUnsafe.setAccessible(true);
                    sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
                    return unsafe.allocateInstance(org.bukkit.craftbukkit.inventory.CraftItemStack.class);
                } catch (Throwable t) {
                    return stack;
                }
            }
        }
        return stack;
    }
}
