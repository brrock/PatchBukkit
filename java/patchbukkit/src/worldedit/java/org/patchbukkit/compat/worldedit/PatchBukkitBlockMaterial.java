package org.patchbukkit.compat.worldedit;

import com.sk89q.worldedit.world.registry.BlockMaterial;

/** Block material facts WorldEdit asks for, taken from the Bukkit {@code Material}. */
record PatchBukkitBlockMaterial(
    boolean isAir,
    boolean isFullCube,
    boolean isOpaque,
    boolean isLiquid,
    boolean isSolid,
    float getHardness,
    float getResistance,
    float getSlipperiness,
    boolean isBurnable,
    boolean isTranslucent
) implements BlockMaterial {

    @Override
    public boolean isPowerSource() {
        return false;
    }

    @Override
    public int getLightValue() {
        return 0;
    }

    @Override
    public boolean isFragileWhenPushed() {
        return false;
    }

    @Override
    public boolean isUnpushable() {
        return false;
    }

    @Override
    public boolean isTicksRandomly() {
        return false;
    }

    @Override
    public boolean isMovementBlocker() {
        return this.isSolid;
    }

    @Override
    public boolean isToolRequired() {
        return false;
    }

    @Override
    public boolean isReplacedDuringPlacement() {
        return this.isAir || this.isLiquid;
    }

    @Override
    public boolean hasContainer() {
        return false;
    }
}
