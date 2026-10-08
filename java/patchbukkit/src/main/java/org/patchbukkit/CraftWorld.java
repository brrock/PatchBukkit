package org.patchbukkit;

import java.util.UUID;
import org.patchbukkit.world.PatchBukkitWorld;

/**
 * The world type plugins get for CraftWorld casts. getHandle() is inherited from PatchBukkitWorld,
 * which owns the detached level with its random source; allocating a second bare level here left
 * its random field null for vanilla block code such as experience drops.
 */
public class CraftWorld extends PatchBukkitWorld {

    public CraftWorld(UUID uuid) {
        super(uuid);
    }
}
