package org.patchbukkit.inventory;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.patchbukkit.bridge.BridgeUtils;
import org.patchbukkit.entity.PatchBukkitHumanEntity;
import patchbukkit.bridge.NativeBridgeFfi;
import patchbukkit.itemstack.GetPlayerInventoryResponse;
import patchbukkit.itemstack.SetPlayerEquipmentRequest;
import patchbukkit.itemstack.SetPlayerInventorySlotRequest;
import patchbukkit.itemstack.SetPlayerSelectedSlotRequest;

import org.jetbrains.annotations.Nullable;

import java.util.*;

public class PatchBukkitPlayerInventory implements PlayerInventory {
    private final PatchBukkitHumanEntity holder;

    public PatchBukkitPlayerInventory(PatchBukkitHumanEntity holder) {
        this.holder = holder;
    }

    private GetPlayerInventoryResponse fetchInventory() {
        var resp = NativeBridgeFfi.getPlayerInventory(BridgeUtils.convertUuid(holder.getUniqueId()));
        if (resp == null) {
            return GetPlayerInventoryResponse.newBuilder().build();
        }
        return resp;
    }

    public static boolean isItemEmpty(@Nullable ItemStack item) {
        if (item == null) {
            return true;
        }
        try {
            if (item.getType() == Material.AIR || item.getAmount() <= 0) {
                return true;
            }
            return item.isEmpty();
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static patchbukkit.itemstack.ItemStack toProto(ItemStack item) {
        if (isItemEmpty(item)) {
            return patchbukkit.itemstack.ItemStack.newBuilder()
                .setType("minecraft:air")
                .setAmount(0)
                .build();
        }
        try {
            String type = item.getType().getKey().toString();
            var builder = patchbukkit.itemstack.ItemStack.newBuilder()
                .setType(type)
                .setAmount(item.getAmount());
            try {
                for (var e : item.getEnchantments().entrySet()) {
                    if (e.getKey() != null && e.getValue() != null && e.getValue() > 0) {
                        builder.putEnchantments(e.getKey().getKey().toString(), e.getValue());
                    }
                }
            } catch (Throwable ignored) {}
            try {
                writeMeta(item, builder);
            } catch (Throwable ignored) {}
            return builder.build();
        } catch (Throwable ignored) {
            return patchbukkit.itemstack.ItemStack.newBuilder()
                .setType("minecraft:air")
                .setAmount(0)
                .build();
        }
    }

    /** Copies the meta Pumpkin understands (name, lore, damage, unbreakable, colour) to the proto. */
    private static void writeMeta(ItemStack item, patchbukkit.itemstack.ItemStack.Builder builder) {
        if (!item.hasItemMeta()) return;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        if (meta.hasDisplayName()) builder.setCustomName(meta.getDisplayName());
        if (meta.hasLore() && meta.getLore() != null) builder.addAllLore(meta.getLore());
        if (meta instanceof org.bukkit.inventory.meta.Damageable d && d.hasDamage()) builder.setDamage(d.getDamage());
        if (meta.isUnbreakable()) builder.setUnbreakable(true);
        if (meta instanceof org.bukkit.inventory.meta.LeatherArmorMeta leather) {
            org.bukkit.Color color = leather.getColor();
            if (color != null && !color.equals(org.bukkit.Bukkit.getItemFactory().getDefaultLeatherColor())) {
                builder.setDyedColor(color.asRGB());
            }
        }
    }

    private static void readMeta(patchbukkit.itemstack.ItemStack proto, ItemStack stack) {
        boolean any = proto.hasCustomName() || proto.getLoreCount() > 0 || proto.getDamage() > 0
            || proto.getUnbreakable() || proto.hasDyedColor();
        if (!any) return;
        org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
        if (meta == null) return;
        if (proto.hasCustomName()) meta.setDisplayName(proto.getCustomName());
        if (proto.getLoreCount() > 0) meta.setLore(new java.util.ArrayList<>(proto.getLoreList()));
        if (proto.getDamage() > 0 && meta instanceof org.bukkit.inventory.meta.Damageable d) d.setDamage(proto.getDamage());
        if (proto.getUnbreakable()) meta.setUnbreakable(true);
        if (proto.hasDyedColor() && meta instanceof org.bukkit.inventory.meta.LeatherArmorMeta leather) {
            leather.setColor(org.bukkit.Color.fromRGB(proto.getDyedColor() & 0xFFFFFF));
        }
        stack.setItemMeta(meta);
    }

    public static ItemStack fromProto(patchbukkit.itemstack.ItemStack proto) {
        if (proto == null || proto.getAmount() <= 0 || proto.getType() == null || proto.getType().isEmpty() || proto.getType().equalsIgnoreCase("minecraft:air")) {
            return new PatchBukkitItemStack(Material.AIR, 0);
        }
        Material mat = Material.matchMaterial(proto.getType());
        if (mat == null || mat == Material.AIR) {
            return new PatchBukkitItemStack(Material.AIR, 0);
        }
        PatchBukkitItemStack stack = new PatchBukkitItemStack(mat, proto.getAmount());
        try {
            readMeta(proto, stack);
        } catch (Throwable ignored) {}
        if (proto.getEnchantmentsCount() > 0) {
            try {
                var registry = org.bukkit.Registry.ENCHANTMENT;
                for (var e : proto.getEnchantmentsMap().entrySet()) {
                    org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(e.getKey());
                    org.bukkit.enchantments.Enchantment ench = key == null ? null : registry.get(key);
                    if (ench != null) {
                        stack.addUnsafeEnchantment(ench, e.getValue());
                    }
                }
            } catch (Throwable ignored) {}
        }
        return stack;
    }

    @Override
    public ListIterator<ItemStack> iterator() {
        return Arrays.asList(getContents()).listIterator();
    }

    @Override
    public ListIterator<ItemStack> iterator(int index) {
        return Arrays.asList(getContents()).listIterator(index);
    }

    @Override
    public ItemStack[] getArmorContents() {
        GetPlayerInventoryResponse resp = fetchInventory();
        ItemStack[] armor = new ItemStack[4];
        armor[0] = fromProto(resp.getBoots());
        armor[1] = fromProto(resp.getLeggings());
        armor[2] = fromProto(resp.getChestplate());
        armor[3] = fromProto(resp.getHelmet());
        return armor;
    }

    @Override
    public void setArmorContents(ItemStack[] items) {
        if (items == null) items = new ItemStack[4];
        setBoots(items.length > 0 ? items[0] : null);
        setLeggings(items.length > 1 ? items[1] : null);
        setChestplate(items.length > 2 ? items[2] : null);
        setHelmet(items.length > 3 ? items[3] : null);
    }

    @Override
    public ItemStack[] getExtraContents() {
        GetPlayerInventoryResponse resp = fetchInventory();
        return new ItemStack[] { fromProto(resp.getOffHand()) };
    }

    @Override
    public void setExtraContents(ItemStack[] items) {
        if (items != null && items.length > 0) {
            setItemInOffHand(items[0]);
        } else {
            setItemInOffHand(null);
        }
    }

    @Override
    public ItemStack getHelmet() {
        return fromProto(fetchInventory().getHelmet());
    }

    @Override
    public ItemStack getChestplate() {
        return fromProto(fetchInventory().getChestplate());
    }

    @Override
    public ItemStack getLeggings() {
        return fromProto(fetchInventory().getLeggings());
    }

    @Override
    public ItemStack getBoots() {
        return fromProto(fetchInventory().getBoots());
    }

    @Override
    public void setHelmet(ItemStack helmet) {
        setEquipment(5, helmet);
    }

    @Override
    public void setChestplate(ItemStack chestplate) {
        setEquipment(4, chestplate);
    }

    @Override
    public void setLeggings(ItemStack leggings) {
        setEquipment(3, leggings);
    }

    @Override
    public void setBoots(ItemStack boots) {
        setEquipment(2, boots);
    }

    @Override
    public ItemStack getItemInMainHand() {
        GetPlayerInventoryResponse resp = fetchInventory();
        int selected = resp.getSelectedSlot();
        if (selected >= 0 && selected < resp.getMainInventoryCount()) {
            return mirror(fromProto(resp.getMainInventory(selected)), selected);
        }
        return ItemStack.empty();
    }

    @Override
    public void setItemInMainHand(ItemStack item) {
        GetPlayerInventoryResponse resp = fetchInventory();
        int selected = resp.getSelectedSlot();
        setItem(selected, item);
    }

    @Override
    public ItemStack getItemInOffHand() {
        return mirror(fromProto(fetchInventory().getOffHand()), 40);
    }

    @Override
    public void setItemInOffHand(ItemStack item) {
        setEquipment(1, item);
    }

    @Override
    public ItemStack getItemInHand() {
        return getItemInMainHand();
    }

    @Override
    public void setItemInHand(ItemStack stack) {
        setItemInMainHand(stack);
    }

    @Override
    public int getHeldItemSlot() {
        return fetchInventory().getSelectedSlot();
    }

    @Override
    public void setHeldItemSlot(int slot) {
        if (slot >= 0 && slot < 9) {
            NativeBridgeFfi.setPlayerSelectedSlot(
                SetPlayerSelectedSlotRequest.newBuilder()
                    .setUuid(BridgeUtils.convertUuid(holder.getUniqueId()))
                    .setSlot(slot)
                    .build()
            );
        }
    }

    private void setEquipment(int slotType, ItemStack item) {
        NativeBridgeFfi.setPlayerEquipment(
            SetPlayerEquipmentRequest.newBuilder()
                .setUuid(BridgeUtils.convertUuid(holder.getUniqueId()))
                .setSlotType(slotType)
                .setItem(toProto(item))
                .build()
        );
    }

    @Override
    public HumanEntity getHolder() {
        return this.holder;
    }

    @Override
    public HumanEntity getHolder(boolean useSnapshot) {
        return this.holder;
    }

    @Override
    public int getSize() {
        return 41;
    }

    @Override
    public int getMaxStackSize() {
        return 64;
    }

    @Override
    public void setMaxStackSize(int size) {
    }

    @Override
    public ItemStack getItem(int index) {
        return mirror(getItemCopy(index), index);
    }

    /** Ties a stack read from {@code slot} to that slot (see {@link PatchBukkitItemStack#mirrorTo}). */
    private ItemStack mirror(ItemStack stack, int slot) {
        if (stack instanceof PatchBukkitItemStack pb && !stack.getType().isAir()) {
            pb.mirrorTo(updated -> setItem(slot, updated));
        }
        return stack;
    }

    private ItemStack getItemCopy(int index) {
        GetPlayerInventoryResponse resp = fetchInventory();
        if (index >= 0 && index < resp.getMainInventoryCount()) {
            return fromProto(resp.getMainInventory(index));
        } else if (index == 36) {
            return fromProto(resp.getBoots());
        } else if (index == 37) {
            return fromProto(resp.getLeggings());
        } else if (index == 38) {
            return fromProto(resp.getChestplate());
        } else if (index == 39) {
            return fromProto(resp.getHelmet());
        } else if (index == 40) {
            return fromProto(resp.getOffHand());
        }
        return ItemStack.empty();
    }

    @Override
    public void setItem(int index, ItemStack item) {
        NativeBridgeFfi.setPlayerInventorySlot(
            SetPlayerInventorySlotRequest.newBuilder()
                .setUuid(BridgeUtils.convertUuid(holder.getUniqueId()))
                .setSlot(index)
                .setItem(toProto(item))
                .build()
        );
    }

    @Override
    public void setItem(EquipmentSlot slot, ItemStack item) {
        if (slot == null) return;
        switch (slot) {
            case HAND -> setItemInMainHand(item);
            case OFF_HAND -> setItemInOffHand(item);
            case FEET -> setBoots(item);
            case LEGS -> setLeggings(item);
            case CHEST -> setChestplate(item);
            case HEAD -> setHelmet(item);
            default -> {}
        }
    }

    @Override
    public ItemStack getItem(EquipmentSlot slot) {
        if (slot == null) return ItemStack.empty();
        return switch (slot) {
            case HAND -> getItemInMainHand();
            case OFF_HAND -> getItemInOffHand();
            case FEET -> getBoots();
            case LEGS -> getLeggings();
            case CHEST -> getChestplate();
            case HEAD -> getHelmet();
            default -> ItemStack.empty();
        };
    }

    @Override
    public HashMap<Integer, ItemStack> addItem(ItemStack... items) throws IllegalArgumentException {
        HashMap<Integer, ItemStack> leftover = new HashMap<>();
        if (items == null) return leftover;
        for (int i = 0; i < items.length; i++) {
            ItemStack item = items[i];
            if (isItemEmpty(item)) continue;
            int firstEmpty = firstEmpty();
            if (firstEmpty != -1) {
                setItem(firstEmpty, item);
            } else {
                leftover.put(i, item);
            }
        }
        return leftover;
    }

    @Override
    public HashMap<Integer, ItemStack> removeItemAnySlot(ItemStack... items) throws IllegalArgumentException {
        return removeItem(items);
    }

    @Override
    public HashMap<Integer, ItemStack> removeItem(ItemStack... items) throws IllegalArgumentException {
        HashMap<Integer, ItemStack> leftover = new HashMap<>();
        if (items == null) return leftover;
        for (int i = 0; i < items.length; i++) {
            ItemStack item = items[i];
            if (isItemEmpty(item)) continue;
            int slot = first(item.getType());
            if (slot != -1) {
                setItem(slot, ItemStack.empty());
            } else {
                leftover.put(i, item);
            }
        }
        return leftover;
    }

    @Override
    public ItemStack[] getContents() {
        GetPlayerInventoryResponse resp = fetchInventory();
        ItemStack[] contents = new ItemStack[41];
        for (int i = 0; i < 36 && i < resp.getMainInventoryCount(); i++) {
            contents[i] = fromProto(resp.getMainInventory(i));
        }
        for (int i = resp.getMainInventoryCount(); i < 36; i++) {
            contents[i] = ItemStack.empty();
        }
        contents[36] = fromProto(resp.getBoots());
        contents[37] = fromProto(resp.getLeggings());
        contents[38] = fromProto(resp.getChestplate());
        contents[39] = fromProto(resp.getHelmet());
        contents[40] = fromProto(resp.getOffHand());
        return contents;
    }

    @Override
    public void setContents(ItemStack[] items) throws IllegalArgumentException {
        if (items == null) return;
        for (int i = 0; i < items.length && i < 41; i++) {
            setItem(i, items[i]);
        }
    }

    @Override
    public ItemStack[] getStorageContents() {
        GetPlayerInventoryResponse resp = fetchInventory();
        ItemStack[] contents = new ItemStack[36];
        for (int i = 0; i < 36 && i < resp.getMainInventoryCount(); i++) {
            contents[i] = fromProto(resp.getMainInventory(i));
        }
        for (int i = resp.getMainInventoryCount(); i < 36; i++) {
            contents[i] = ItemStack.empty();
        }
        return contents;
    }

    @Override
    public void setStorageContents(ItemStack[] items) throws IllegalArgumentException {
        if (items == null) return;
        for (int i = 0; i < items.length && i < 36; i++) {
            setItem(i, items[i]);
        }
    }

    @Override
    public boolean contains(Material material) throws IllegalArgumentException {
        return first(material) != -1;
    }

    @Override
    public boolean contains(ItemStack item) {
        return first(item) != -1;
    }

    @Override
    public boolean contains(Material material, int amount) throws IllegalArgumentException {
        if (amount <= 0) return true;
        int count = 0;
        for (ItemStack is : getStorageContents()) {
            if (is != null && !is.isEmpty() && is.getType() == material) {
                count += is.getAmount();
                if (count >= amount) return true;
            }
        }
        return false;
    }

    @Override
    public boolean contains(ItemStack item, int amount) {
        if (item == null) return false;
        return contains(item.getType(), amount);
    }

    @Override
    public boolean containsAtLeast(ItemStack item, int amount) {
        if (item == null) return false;
        return contains(item.getType(), amount);
    }

    @Override
    public HashMap<Integer, ? extends ItemStack> all(Material material) throws IllegalArgumentException {
        HashMap<Integer, ItemStack> slots = new HashMap<>();
        ItemStack[] contents = getContents();
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] != null && !contents[i].isEmpty() && contents[i].getType() == material) {
                slots.put(i, contents[i]);
            }
        }
        return slots;
    }

    @Override
    public HashMap<Integer, ? extends ItemStack> all(ItemStack item) {
        if (item == null) return new HashMap<>();
        return all(item.getType());
    }

    @Override
    public int first(Material material) throws IllegalArgumentException {
        if (material == null) return -1;
        ItemStack[] contents = getContents();
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] != null && !contents[i].isEmpty() && contents[i].getType() == material) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public int first(ItemStack item) {
        if (item == null) return -1;
        return first(item.getType());
    }

    @Override
    public int firstEmpty() {
        ItemStack[] contents = getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            if (isItemEmpty(contents[i])) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack item : getContents()) {
            if (!isItemEmpty(item)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void remove(Material material) throws IllegalArgumentException {
        if (material == null) return;
        ItemStack[] contents = getContents();
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] != null && contents[i].getType() == material) {
                setItem(i, ItemStack.empty());
            }
        }
    }

    @Override
    public void remove(ItemStack item) {
        if (item != null) {
            remove(item.getType());
        }
    }

    @Override
    public void clear(int index) {
        setItem(index, ItemStack.empty());
    }

    @Override
    public void clear() {
        NativeBridgeFfi.clearPlayerInventory(BridgeUtils.convertUuid(holder.getUniqueId()));
    }

    @Override
    public List<HumanEntity> getViewers() {
        return Collections.singletonList(holder);
    }

    @Override
    public InventoryType getType() {
        return InventoryType.PLAYER;
    }

    @Override
    public int close() {
        return 0;
    }

    @Override
    public Location getLocation() {
        return holder.getLocation();
    }
}
