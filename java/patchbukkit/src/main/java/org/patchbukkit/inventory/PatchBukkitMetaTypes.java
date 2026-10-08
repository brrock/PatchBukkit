package org.patchbukkit.inventory;

import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.FireworkEffectMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.meta.SuspiciousStewMeta;
import org.bukkit.inventory.meta.WritableBookMeta;

/**
 * Interfaces that {@link PatchBukkitItemMeta} proxies implement.
 *
 * <p>{@link java.lang.reflect.Proxy} refuses to implement two interfaces that declare
 * {@code clone()} with unrelated return types, and Bukkit's {@link Damageable} and
 * {@link Repairable} do exactly that, as does every meta sub-interface that redeclares
 * {@code clone()}. Each proxy therefore implements one of the merged interfaces below, which
 * extend the Bukkit interface(s) for a material and redeclare {@code clone()} with a return type
 * that is a subtype of all of them.
 */
public final class PatchBukkitMetaTypes {

    private PatchBukkitMetaTypes() {}

    public interface DamageableRepairable extends Damageable, Repairable {
        @Override
        DamageableRepairable clone();
    }

    public interface Skull extends SkullMeta, DamageableRepairable {
        @Override
        Skull clone();
    }

    public interface Book extends BookMeta, DamageableRepairable {
        @Override
        Book clone();
    }

    public interface WritableBook extends WritableBookMeta, DamageableRepairable {
        @Override
        WritableBook clone();
    }

    public interface EnchantmentStorage extends EnchantmentStorageMeta, DamageableRepairable {
        @Override
        EnchantmentStorage clone();
    }

    public interface Firework extends FireworkMeta, DamageableRepairable {
        @Override
        Firework clone();
    }

    public interface FireworkEffect extends FireworkEffectMeta, DamageableRepairable {
        @Override
        FireworkEffect clone();
    }

    public interface Potion extends PotionMeta, DamageableRepairable {
        @Override
        Potion clone();
    }

    public interface Map extends MapMeta, DamageableRepairable {
        @Override
        Map clone();
    }

    public interface Compass extends CompassMeta, DamageableRepairable {
        @Override
        Compass clone();
    }

    public interface Crossbow extends CrossbowMeta, DamageableRepairable {
        @Override
        Crossbow clone();
    }

    public interface SuspiciousStew extends SuspiciousStewMeta, DamageableRepairable {
        @Override
        SuspiciousStew clone();
    }

    public interface Bundle extends BundleMeta, DamageableRepairable {
        @Override
        Bundle clone();
    }

    public interface Banner extends BannerMeta, DamageableRepairable {
        @Override
        Banner clone();
    }

    public interface BlockState extends BlockStateMeta, DamageableRepairable {
        @Override
        BlockState clone();
    }

    public interface Armor extends ArmorMeta, DamageableRepairable {
        @Override
        Armor clone();
    }

    public interface LeatherArmor extends LeatherArmorMeta, DamageableRepairable {
        @Override
        LeatherArmor clone();
    }
}
