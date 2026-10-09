package org.patchbukkit.inventory;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class PatchBukkitInventory implements Inventory {
    private final InventoryHolder holder;
    private final int size;
    private final String title;
    private final InventoryType type;
    private final ItemStack[] contents;
    private int maxStackSize = 64;

    public PatchBukkitInventory(@Nullable InventoryHolder holder, int size, @NotNull String title) {
        this(holder, size, title, InventoryType.CHEST);
    }

    public PatchBukkitInventory(@Nullable InventoryHolder holder, @NotNull InventoryType type) {
        this(holder, type.getDefaultSize(), type.getDefaultTitle(), type);
    }

    public PatchBukkitInventory(@Nullable InventoryHolder holder, @NotNull InventoryType type, @NotNull String title) {
        this(holder, type.getDefaultSize(), title, type);
    }

    public PatchBukkitInventory(@Nullable InventoryHolder holder, @NotNull InventoryType type, @NotNull net.kyori.adventure.text.Component title) {
        this(holder, type.getDefaultSize(), net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(title), type);
    }

    public PatchBukkitInventory(@Nullable InventoryHolder holder, int size, @NotNull net.kyori.adventure.text.Component title) {
        this(holder, size, net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(title), InventoryType.CHEST);
    }

    public PatchBukkitInventory(@Nullable InventoryHolder holder, int size) {
        this(holder, size, "Inventory", InventoryType.CHEST);
    }

    public PatchBukkitInventory(@Nullable InventoryHolder holder, int size, @NotNull String title, @NotNull InventoryType type) {
        this.holder = holder;
        this.size = size;
        this.title = title;
        this.type = type;
        this.contents = new ItemStack[size];
        Arrays.fill(this.contents, ItemStack.empty());
    }

    private static final java.util.concurrent.atomic.AtomicLong NEXT_NATIVE_ID = new java.util.concurrent.atomic.AtomicLong(1);
    private final long nativeId = NEXT_NATIVE_ID.getAndIncrement();
    /** True while Pumpkin shows this inventory and holds its live contents. */
    private volatile boolean nativeBacked;

    /**
     * Shows this inventory to a player as a chest screen. Pumpkin then holds the live
     * contents until every viewer has closed it.
     */
    public boolean openNative(@NotNull HumanEntity viewer) {
        if (this.size % 9 != 0 || this.size < 9 || this.size > 54) return false;
        var req = patchbukkit.itemstack.CustomInventoryRequest.newBuilder()
            .setId(nativeId)
            .setViewer(org.patchbukkit.bridge.BridgeUtils.convertUuid(viewer.getUniqueId()))
            .setRows(this.size / 9)
            .setTitle(this.title == null ? "" : this.title);
        pullContents();
        for (ItemStack item : this.contents) req.addItems(PatchBukkitPlayerInventory.toProto(item));
        var resp = patchbukkit.bridge.NativeBridgeFfi.openCustomInventory(req.build());
        if (resp != null && resp.getFound()) {
            this.nativeBacked = true;
            return true;
        }
        return false;
    }

    /** Refreshes {@link #contents} from a backing store. */
    protected void pullContents() {
        if (!nativeBacked) return;
        try {
            var resp = patchbukkit.bridge.NativeBridgeFfi.getCustomInventory(
                patchbukkit.itemstack.CustomInventoryRequest.newBuilder().setId(nativeId).build());
            if (resp == null || !resp.getFound()) {
                nativeBacked = false; // closed by every viewer: the local copy is current
                return;
            }
            for (int i = 0; i < this.size && i < resp.getItemsCount(); i++) {
                this.contents[i] = PatchBukkitPlayerInventory.fromProto(resp.getItems(i));
            }
        } catch (Throwable t) {
            org.patchbukkit.bridge.BridgeUtils.logBridgeFailure("getCustomInventory", t);
        }
    }

    /** Writes {@link #contents} back to a backing store. */
    protected void pushContents() {
        if (!nativeBacked) return;
        try {
            var req = patchbukkit.itemstack.CustomInventoryRequest.newBuilder().setId(nativeId);
            for (ItemStack item : this.contents) req.addItems(PatchBukkitPlayerInventory.toProto(item));
            var resp = patchbukkit.bridge.NativeBridgeFfi.setCustomInventory(req.build());
            if (resp == null || !resp.getFound()) nativeBacked = false;
        } catch (Throwable t) {
            org.patchbukkit.bridge.BridgeUtils.logBridgeFailure("setCustomInventory", t);
        }
    }

    /** Direct access for subclasses that sync with a backing store. */
    protected ItemStack[] rawContents() {
        return this.contents;
    }

    @Override
    public int getSize() {
        return this.size;
    }

    @Override
    public int getMaxStackSize() {
        return this.maxStackSize;
    }

    @Override
    public void setMaxStackSize(int size) {
        this.maxStackSize = size;
    }

    @Override
    public @Nullable ItemStack getItem(int index) {
        pullContents();
        if (index < 0 || index >= this.size) return null;
        return this.contents[index];
    }

    @Override
    public void setItem(int index, @Nullable ItemStack item) {
        pullContents();
        try {
            if (index >= 0 && index < this.size) {
                this.contents[index] = (item != null) ? item.clone() : ItemStack.empty();
            }
        } finally {
            pushContents();
        }
    }

    @Override
    public @NotNull HashMap<Integer, ItemStack> addItem(@NotNull ItemStack... items) throws IllegalArgumentException {
        pullContents();
        try {
            HashMap<Integer, ItemStack> leftover = new HashMap<>();
            if (items == null) return leftover;
    
            for (int i = 0; i < items.length; i++) {
                ItemStack item = items[i];
                if (item == null || item.isEmpty()) continue;
                ItemStack remaining = item.clone();
    
                for (int slot = 0; slot < this.size; slot++) {
                    ItemStack current = this.contents[slot];
                    if (current != null && !current.isEmpty() && current.isSimilar(remaining)) {
                        int canAdd = Math.min(remaining.getAmount(), current.getMaxStackSize() - current.getAmount());
                        if (canAdd > 0) {
                            current.setAmount(current.getAmount() + canAdd);
                            remaining.setAmount(remaining.getAmount() - canAdd);
                            if (remaining.getAmount() <= 0) break;
                        }
                    }
                }
    
                if (remaining.getAmount() > 0) {
                    for (int slot = 0; slot < this.size; slot++) {
                        ItemStack current = this.contents[slot];
                        if (current == null || current.isEmpty()) {
                            this.contents[slot] = remaining.clone();
                            remaining.setAmount(0);
                            break;
                        }
                    }
                }
    
                if (remaining.getAmount() > 0) {
                    leftover.put(i, remaining);
                }
            }
            return leftover;
        } finally {
            pushContents();
        }
    }

    @Override
    public @NotNull HashMap<Integer, ItemStack> removeItem(@NotNull ItemStack... items) throws IllegalArgumentException {
        pullContents();
        try {
            HashMap<Integer, ItemStack> leftover = new HashMap<>();
            if (items == null) return leftover;
    
            for (int i = 0; i < items.length; i++) {
                ItemStack item = items[i];
                if (item == null || item.isEmpty()) continue;
                int toRemove = item.getAmount();
    
                for (int slot = 0; slot < this.size; slot++) {
                    ItemStack current = this.contents[slot];
                    if (current != null && current.isSimilar(item)) {
                        int remove = Math.min(toRemove, current.getAmount());
                        current.setAmount(current.getAmount() - remove);
                        toRemove -= remove;
                        if (current.getAmount() <= 0) {
                            this.contents[slot] = ItemStack.empty();
                        }
                        if (toRemove <= 0) break;
                    }
                }
    
                if (toRemove > 0) {
                    ItemStack rem = item.clone();
                    rem.setAmount(toRemove);
                    leftover.put(i, rem);
                }
            }
            return leftover;
        } finally {
            pushContents();
        }
    }

    @Override
    public @NotNull HashMap<Integer, ItemStack> removeItemAnySlot(@NotNull ItemStack... items) throws IllegalArgumentException {
        return removeItem(items);
    }

    @Override
    public @NotNull ItemStack[] getContents() {
        pullContents();
        return this.contents.clone();
    }

    @Override
    public void setContents(@NotNull ItemStack[] items) throws IllegalArgumentException {
        pullContents();
        try {
            if (items == null) return;
            for (int i = 0; i < this.size; i++) {
                if (i < items.length && items[i] != null) {
                    this.contents[i] = items[i].clone();
                } else {
                    this.contents[i] = ItemStack.empty();
                }
            }
        } finally {
            pushContents();
        }
    }

    @Override
    public @NotNull ItemStack[] getStorageContents() {
        return getContents();
    }

    @Override
    public void setStorageContents(@NotNull ItemStack[] items) throws IllegalArgumentException {
        setContents(items);
    }

    @Override
    public boolean contains(@NotNull Material material) throws IllegalArgumentException {
        return first(material) != -1;
    }

    @Override
    public boolean contains(@Nullable ItemStack item) {
        return first(item) != -1;
    }

    @Override
    public boolean contains(@NotNull Material material, int amount) throws IllegalArgumentException {
        pullContents();
        int count = 0;
        for (ItemStack is : this.contents) {
            if (is != null && is.getType() == material) {
                count += is.getAmount();
                if (count >= amount) return true;
            }
        }
        return false;
    }

    @Override
    public boolean contains(@Nullable ItemStack item, int amount) {
        pullContents();
        if (item == null) return false;
        int count = 0;
        for (ItemStack is : this.contents) {
            if (is != null && is.isSimilar(item)) {
                count += is.getAmount();
                if (count >= amount) return true;
            }
        }
        return false;
    }

    @Override
    public boolean containsAtLeast(@Nullable ItemStack item, int amount) {
        return contains(item, amount);
    }

    @Override
    public @NotNull HashMap<Integer, ? extends ItemStack> all(@NotNull Material material) throws IllegalArgumentException {
        pullContents();
        HashMap<Integer, ItemStack> map = new HashMap<>();
        for (int i = 0; i < this.size; i++) {
            if (this.contents[i] != null && this.contents[i].getType() == material) {
                map.put(i, this.contents[i]);
            }
        }
        return map;
    }

    @Override
    public @NotNull HashMap<Integer, ? extends ItemStack> all(@Nullable ItemStack item) {
        pullContents();
        HashMap<Integer, ItemStack> map = new HashMap<>();
        if (item == null) return map;
        for (int i = 0; i < this.size; i++) {
            if (this.contents[i] != null && this.contents[i].isSimilar(item)) {
                map.put(i, this.contents[i]);
            }
        }
        return map;
    }

    @Override
    public int first(@NotNull Material material) throws IllegalArgumentException {
        pullContents();
        for (int i = 0; i < this.size; i++) {
            if (this.contents[i] != null && this.contents[i].getType() == material) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public int first(@NotNull ItemStack item) {
        pullContents();
        if (item == null) return -1;
        for (int i = 0; i < this.size; i++) {
            if (this.contents[i] != null && this.contents[i].isSimilar(item)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public int firstEmpty() {
        pullContents();
        for (int i = 0; i < this.size; i++) {
            if (this.contents[i] == null || this.contents[i].isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean isEmpty() {
        pullContents();
        for (ItemStack is : this.contents) {
            if (is != null && !is.isEmpty()) return false;
        }
        return true;
    }

    @Override
    public void remove(@NotNull Material material) throws IllegalArgumentException {
        pullContents();
        try {
            for (int i = 0; i < this.size; i++) {
                if (this.contents[i] != null && this.contents[i].getType() == material) {
                    this.contents[i] = ItemStack.empty();
                }
            }
        } finally {
            pushContents();
        }
    }

    @Override
    public void remove(@NotNull ItemStack item) {
        pullContents();
        try {
            if (item == null) return;
            for (int i = 0; i < this.size; i++) {
                if (this.contents[i] != null && this.contents[i].isSimilar(item)) {
                    this.contents[i] = ItemStack.empty();
                }
            }
        } finally {
            pushContents();
        }
    }

    @Override
    public void clear(int index) {
        pullContents();
        setItem(index, ItemStack.empty());
    }

    @Override
    public void clear() {
        pullContents();
        try {
            Arrays.fill(this.contents, ItemStack.empty());
        } finally {
            pushContents();
        }
    }

    @Override
    public int close() {
        return 0;
    }

    @Override
    public @NotNull List<HumanEntity> getViewers() {
        return Collections.emptyList();
    }

    @Override
    public @NotNull InventoryType getType() {
        return this.type;
    }

    @Override
    public @Nullable InventoryHolder getHolder() {
        return this.holder;
    }

    @Override
    public @Nullable InventoryHolder getHolder(boolean useSnapshot) {
        return this.holder;
    }

    @Override
    public @NotNull ListIterator<ItemStack> iterator() {
        pullContents();
        return Arrays.asList(this.contents).listIterator();
    }

    @Override
    public @NotNull ListIterator<ItemStack> iterator(int index) {
        pullContents();
        return Arrays.asList(this.contents).listIterator(index);
    }

    @Override
    public @Nullable Location getLocation() {
        return null;
    }
}
