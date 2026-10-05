package org.patchbukkit.registry;

import org.bukkit.Material;
import patchbukkit.bridge.NativeBridgeFfi;
import patchbukkit.registry.BlockTypeData;
import patchbukkit.registry.GetBlockTypeDataRequest;
import patchbukkit.registry.GetItemTypeDataRequest;
import patchbukkit.registry.ItemTypeData;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches the static item and block properties Pumpkin reports for each {@link Material}.
 */
final class PatchBukkitTypeData {

    private static final Map<Material, ItemTypeData> ITEMS = new ConcurrentHashMap<>();
    private static final Map<Material, BlockTypeData> BLOCKS = new ConcurrentHashMap<>();

    private PatchBukkitTypeData() {}

    static ItemTypeData item(Material material) {
        return ITEMS.computeIfAbsent(material, m -> {
            try {
                ItemTypeData data = NativeBridgeFfi.getItemTypeData(
                    GetItemTypeDataRequest.newBuilder().setKey(m.getKey().toString()).build());
                return data != null ? data : ItemTypeData.getDefaultInstance();
            } catch (Throwable t) {
                return ItemTypeData.getDefaultInstance();
            }
        });
    }

    static BlockTypeData block(Material material) {
        return BLOCKS.computeIfAbsent(material, m -> {
            try {
                BlockTypeData data = NativeBridgeFfi.getBlockTypeData(
                    GetBlockTypeDataRequest.newBuilder().setKey(m.getKey().toString()).build());
                return data != null ? data : BlockTypeData.getDefaultInstance();
            } catch (Throwable t) {
                return BlockTypeData.getDefaultInstance();
            }
        });
    }
}
