package org.patchbukkit.registry;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PatchBukkitBlockTypeTest {

    @Test
    public void interactableBlocks() {
        for (Material m : new Material[] {Material.CHEST, Material.OAK_DOOR, Material.SPRUCE_TRAPDOOR,
                Material.STONE_BUTTON, Material.LEVER, Material.RED_BED, Material.CRAFTING_TABLE,
                Material.OAK_FENCE_GATE, Material.SHULKER_BOX, Material.BLUE_SHULKER_BOX,
                Material.POTTED_POPPY, Material.REPEATER}) {
            assertTrue(PatchBukkitBlockType.isInteractable(m), m.name());
        }
    }

    @Test
    public void plainBlocks() {
        for (Material m : new Material[] {Material.STONE, Material.DIRT, Material.OAK_LOG,
                Material.IRON_DOOR, Material.IRON_TRAPDOOR, Material.GLASS, Material.OAK_STAIRS}) {
            assertFalse(PatchBukkitBlockType.isInteractable(m), m.name());
        }
    }
}
