package org.patchbukkit.inventory;

import org.bukkit.entity.HumanEntity;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.patchbukkit.bridge.BridgeUtils;
import patchbukkit.bridge.NativeBridgeFfi;

/** A player's ender chest, backed by the Pumpkin player's ender chest inventory. */
public class PatchBukkitEnderChest extends PatchBukkitInventory {
    private final HumanEntity owner;
    private ItemStack[] lastPulled;

    public PatchBukkitEnderChest(HumanEntity owner) {
        super(owner, 27, "Ender Chest", InventoryType.ENDER_CHEST);
        this.owner = owner;
    }

    public HumanEntity getOwner() {
        return owner;
    }

    @Override
    protected void pullContents() {
        try {
            var resp = NativeBridgeFfi.getEnderChest(BridgeUtils.convertUuid(owner.getUniqueId()));
            if (resp == null) return;
            ItemStack[] contents = rawContents();
            for (int i = 0; i < contents.length; i++) {
                contents[i] = i < resp.getItemsCount()
                    ? PatchBukkitPlayerInventory.fromProto(resp.getItems(i))
                    : ItemStack.empty();
            }
            lastPulled = new ItemStack[contents.length];
            for (int i = 0; i < contents.length; i++) lastPulled[i] = contents[i].clone();
        } catch (Throwable t) { BridgeUtils.logBridgeFailure("getEnderChest", t); }
    }

    @Override
    protected void pushContents() {
        ItemStack[] contents = rawContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i] == null ? ItemStack.empty() : contents[i];
            if (lastPulled != null && item.equals(lastPulled[i])) continue;
            try {
                NativeBridgeFfi.setEnderChestSlot(
                    patchbukkit.itemstack.SetPlayerInventorySlotRequest.newBuilder()
                        .setUuid(BridgeUtils.convertUuid(owner.getUniqueId()))
                        .setSlot(i)
                        .setItem(PatchBukkitPlayerInventory.toProto(item))
                        .build());
            } catch (Throwable t) { BridgeUtils.logBridgeFailure("setEnderChestSlot", t); }
        }
        lastPulled = null;
    }
}
