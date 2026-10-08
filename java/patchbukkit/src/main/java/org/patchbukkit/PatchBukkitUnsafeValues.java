package org.patchbukkit;

import com.google.common.collect.Multimap;
import com.google.gson.JsonObject;
import io.papermc.paper.entity.EntitySerializationFlag;
import io.papermc.paper.inventory.tooltip.TooltipContext;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.registry.RegistryKey;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.event.HoverEvent.ShowItem;
import net.kyori.adventure.text.flattener.ComponentFlattener;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import patchbukkit.bridge.NativeBridgeFfi;

import org.bukkit.*;
import org.bukkit.advancement.Advancement;
import org.bukkit.attribute.Attributable;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageSource.Builder;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.CreativeCategory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.material.MaterialData;
import org.bukkit.plugin.InvalidPluginException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionType;
import org.bukkit.potion.PotionType.InternalPotionData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;
import org.patchbukkit.events.PatchBukkitLifecycleEventManager;
import org.patchbukkit.versioning.ApiVersion;
import org.patchbukkit.versioning.Versioning;
import patchbukkit.common.EmptyRequest;

public class PatchBukkitUnsafeValues implements UnsafeValues {

    public static final PatchBukkitUnsafeValues INSTANCE =
        new PatchBukkitUnsafeValues();

    public @NotNull List<net.kyori.adventure.text.Component> computeTooltipLines(@NotNull ItemStack itemStack, @NotNull TooltipContext tooltipContext, @Nullable Player player) {
        return java.util.Collections.emptyList();
    }

    public io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager<org.bukkit.plugin.Plugin> createPluginLifecycleEventManager(org.bukkit.plugin.java.JavaPlugin plugin, java.util.function.BooleanSupplier registrationCheck) {
        return null;
    }

    public @Nullable Color getSpawnEggLayerColor(EntityType entityType, int layer) {
        return null;
    }

    @Override
    public boolean isSupportedApiVersion(String apiVersion) {
        if (apiVersion == null) return false;
        final ApiVersion toCheck = ApiVersion.getOrCreateVersion(apiVersion);
        String minimumApi = null;
        try {
            minimumApi = NativeBridgeFfi.getPatchBukkitConfig(EmptyRequest.newBuilder().build()).getMinimumSupportedPluginApi();
        } catch (Throwable ignored) {}
        final ApiVersion minimumVersion = (minimumApi == null || minimumApi.isEmpty() || "0.0.0".equals(minimumApi))
                ? ApiVersion.getOrCreateVersion("1.13")
                : ApiVersion.getOrCreateVersion(minimumApi);

        return !toCheck.isNewerThan(ApiVersion.CURRENT) && !toCheck.isOlderThan(minimumVersion);
    }

    @Override
    public void checkSupported(PluginDescriptionFile pdf)
        throws InvalidPluginException {
        String api = pdf.getAPIVersion();
        if (api != null && !isSupportedApiVersion(api)) {
            throw new InvalidPluginException("Unsupported API: " + api);
        }
    }



	@Override
	public Material toLegacy(Material material) {
		return material;
	}

	@Override
	public Material fromLegacy(Material material) {
	    return PatchBukkitLegacy.fromLegacy(material);
	}

	@Override
	public Material fromLegacy(MaterialData material) {
	    return PatchBukkitLegacy.fromLegacy(material);
	}

	@Override
	public Material fromLegacy(MaterialData material, boolean itemPriority) {
	    return PatchBukkitLegacy.fromLegacy(material, itemPriority);
	}

	@Override
	public BlockData fromLegacy(Material material, byte data) {
		return material != null ? material.createBlockData() : null;
	}

	@Override
	public Material getMaterial(String material, int version) {
		try {
			return Material.valueOf(material.toUpperCase(java.util.Locale.ROOT));
		} catch (Exception e) {
			return Material.getMaterial(material);
		}
	}

	@Override
	public int getDataVersion() {
		return 3953;
	}

	@Override
	public ItemStack modifyItemStack(ItemStack stack, String arguments) {
		return stack;
	}

	public @NotNull ItemStack createEmptyStack() {
		return new ItemStack(Material.AIR);
	}

	@Override
	public byte[] processClass(PluginDescriptionFile pdf, String path, byte[] clazz) {
		return clazz;
	}

	public Advancement loadAdvancement(net.kyori.adventure.key.Key key, String advancement, boolean checkKey) {
		return null;
	}

	public List<Advancement> loadAdvancements(Map<net.kyori.adventure.key.Key, String> advancements, boolean checkKey) {
		return java.util.Collections.emptyList();
	}

	@Override
	public boolean removeAdvancement(NamespacedKey key) {
		return false;
	}

	@Override
	public String get(Class<?> aClass, String value) {
		return null;
	}

	public <B extends Keyed> B get(RegistryKey<B> registry, NamespacedKey key) {
		if (registry == null || key == null) return null;
		org.bukkit.Registry<B> reg = io.papermc.paper.registry.RegistryAccess.registryAccess().getRegistry(registry);
		return reg != null ? reg.get(key) : null;
	}

	@Override
	public @NotNull JsonObject serializeItemAsJson(@NotNull ItemStack itemStack) {
		return new JsonObject();
	}

	@Override
	public @NotNull ItemStack deserializeItemFromJson(@NotNull JsonObject data) throws IllegalArgumentException {
		return new ItemStack(Material.STONE);
	}

	public byte @NotNull [] serializeEntity(@NotNull Entity entity,
			@NotNull EntitySerializationFlag... serializationFlags) {
		return new byte[0];
	}

	public @NotNull Entity deserializeEntity(byte @NotNull [] data, @NotNull World world, boolean preserveUUID,
			boolean preservePassengers) {
		return null;
	}


	@Override
	public @NotNull String getMainLevelName() {
		return "world";
	}

	@Override
	public int getProtocolVersion() {
		return 769;
	}

	public @NotNull ItemStack deserializeStack(@NotNull Map<String, Object> args) {
		// Reads the format written by PatchBukkitItemStack#serialize (Bukkit's legacy layout).
		Object typeName = args.containsKey("type") ? args.get("type") : args.get("id");
		Material type = typeName == null ? null : Material.matchMaterial(String.valueOf(typeName));
		if (type == null || type.isAir()) {
			return ItemStack.empty();
		}
		int amount = args.get("amount") instanceof Number n ? n.intValue()
			: args.get("count") instanceof Number c ? c.intValue() : 1;
		org.patchbukkit.inventory.PatchBukkitItemStack stack = new org.patchbukkit.inventory.PatchBukkitItemStack(type, amount);
		if (args.get("meta") instanceof org.bukkit.inventory.meta.ItemMeta meta) {
			stack.setItemMeta(meta);
		}
		return stack;
	}

	public @NotNull ItemStack deserializeItemHover(@NotNull ShowItem itemHover) {
		return new ItemStack(Material.STONE);
	}

	public InternalPotionData getInternalPotionData(NamespacedKey key) {
		return null;
	}

	public int nextEntityId(World world) {
		return (int) (System.currentTimeMillis() & 0x7FFFFFFF);
	}

	
}
