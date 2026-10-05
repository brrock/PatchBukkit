package org.patchbukkit.command;

import java.lang.reflect.Constructor;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;

public class CommandFactory {
    public static PluginCommand create(String name, Plugin plugin) {
        try {
            Constructor<PluginCommand> constructor = PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
            constructor.setAccessible(true);
            return constructor.newInstance(name, plugin);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static PluginCommand create(String name, String pluginName) {
        try {
            Plugin plugin = null;
            if (org.patchbukkit.PatchBukkitPluginManager.unwrap(org.bukkit.Bukkit.getPluginManager()) instanceof org.patchbukkit.PatchBukkitPluginManager pm) {
                plugin = pm.getPlugin(pluginName);
                if (plugin == null) {
                    for (Plugin p : pm.getPlugins()) {
                        if (p.getName().equalsIgnoreCase(pluginName)) {
                            plugin = p;
                            break;
                        }
                    }
                }
            }
            if (plugin == null) {
                plugin = org.bukkit.Bukkit.getPluginManager().getPlugin(pluginName);
            }
            if (plugin == null) {
                System.err.println("[PatchBukkit] CommandFactory: Plugin not found by name '" + pluginName + "' for command '" + name + "'");
            }
            return create(name, plugin);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}