package org.patchbukkit.entity;

import java.util.UUID;
import org.bukkit.entity.ExperienceOrb;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An experience orb. Plugins spawn one and then call setExperience, so the orb is handed to the
 * server once the spawning task has finished (see PatchBukkitWorld#spawnExperienceOrbLater).
 */
public class PatchBukkitExperienceOrb extends PatchBukkitEntity implements ExperienceOrb {

    private int experience;
    private int count = 1;

    public PatchBukkitExperienceOrb(UUID uuid) {
        super(uuid, "experience_orb", -1);
    }

    @Override public int getExperience() { return experience; }
    @Override public void setExperience(int value) { this.experience = value; }
    @Override public int getCount() { return count; }
    @Override public void setCount(int count) { this.count = count; }
    @Override public @NotNull SpawnReason getSpawnReason() { return SpawnReason.CUSTOM; }
    @Override public @Nullable UUID getTriggerEntityId() { return null; }
    @Override public @Nullable UUID getSourceEntityId() { return null; }
}
