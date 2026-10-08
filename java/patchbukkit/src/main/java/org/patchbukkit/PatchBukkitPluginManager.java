package org.patchbukkit;

import io.papermc.paper.plugin.PermissionManager;
import io.papermc.paper.plugin.configuration.PluginMeta;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.bukkit.Server;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.permissions.Permissible;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;
import org.patchbukkit.events.PatchBukkitEventManager;
import org.patchbukkit.loader.PatchBukkitPluginClassLoader;
import org.patchbukkit.permissions.PatchBukkitPermissionManager;

@SuppressWarnings("removal")
public class PatchBukkitPluginManager implements PluginManager {
    private final Server server;
    private final PatchBukkitEventManager eventManager;
    private final Map<String, Plugin> plugins = new ConcurrentHashMap<>();
    // Registration (load) order, used for getPlugins() and reversed for disablePlugins() as in Bukkit.
    private final java.util.concurrent.CopyOnWriteArrayList<Plugin> loadOrder = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final SimplePluginManager simplePluginManager;
    PermissionManager permissionManager;

    public PatchBukkitPluginManager(Server server, SimpleCommandMap commandMap) {
        this.server = server;
        this.eventManager = new PatchBukkitEventManager(server);
        // Same layout as Paper: Server#getPluginManager() is a SimplePluginManager that delegates
        // here, and permission state lives in its public maps so plugins can inject into them.
        this.simplePluginManager = new SimplePluginManager(server, commandMap);
        this.simplePluginManager.paperPluginManager = this;
        this.permissionManager = new PatchBukkitPermissionManager(this.simplePluginManager);
    }

    /** The {@link SimplePluginManager} handed out by {@link Server#getPluginManager()}. */
    public SimplePluginManager getSimplePluginManager() {
        return simplePluginManager;
    }

    /** Unwraps the server's plugin manager to the PatchBukkit implementation, or returns null. */
    public static @Nullable PatchBukkitPluginManager unwrap(@Nullable PluginManager pluginManager) {
        if (pluginManager instanceof PatchBukkitPluginManager patchBukkit) {
            return patchBukkit;
        }
        if (pluginManager instanceof SimplePluginManager simple
            && simple.paperPluginManager instanceof PatchBukkitPluginManager patchBukkit) {
            return patchBukkit;
        }
        return null;
    }

    public PatchBukkitEventManager getEventManager() {
        return eventManager;
    }

    @Override
    public void registerInterface(@NotNull Class<? extends PluginLoader> loader)
        throws IllegalArgumentException {}

    public void registerPlugin(@NotNull Plugin plugin) {
        if (plugin == null || plugin.getName() == null) {
            return;
        }
        plugins.put(plugin.getName().toLowerCase(java.util.Locale.ENGLISH), plugin);
        loadOrder.addIfAbsent(plugin);
        if (plugin.getDescription() != null && plugin.getDescription().getProvides() != null) {
            for (String provided : plugin.getDescription().getProvides()) {
                if (provided != null && !provided.isBlank()) {
                    plugins.putIfAbsent(provided.toLowerCase(java.util.Locale.ENGLISH), plugin);
                }
            }
        }
    }

    @Override
    public @Nullable Plugin getPlugin(@NotNull String name) {
        if (name == null) {
            return null;
        }
        return plugins.get(name.replace(' ', '_').toLowerCase(java.util.Locale.ENGLISH));
    }

    @Override
    public @NotNull Plugin[] getPlugins() {
        return loadOrder.toArray(Plugin[]::new);
    }

    @Override
    public boolean isPluginEnabled(@NotNull String name) {
        Plugin plugin = getPlugin(name);
        return isPluginEnabled(plugin);
    }

    @Override
    public boolean isPluginEnabled(@Nullable Plugin plugin) {
        return plugin != null && plugins.containsValue(plugin) && plugin.isEnabled();
    }

    @Override
    public @Nullable Plugin loadPlugin(@NotNull File file)
        throws InvalidPluginException, InvalidDescriptionException, UnknownDependencyException {
        if (file == null || !file.exists()) {
            throw new InvalidPluginException("File does not exist: " + file);
        }

        PatchBukkitPluginClassLoader loader;
        try {
            loader = new PatchBukkitPluginClassLoader(
                getClass().getClassLoader(),
                file
            );
        } catch (Exception e) {
            throw new InvalidPluginException("Failed to load plugin description or classloader: " + file.getName(), e);
        }

        PluginDescriptionFile description = loader.getDescription();
        String mainClass = description.getMain();
        try {
            Class<?> jarClass = Class.forName(mainClass, true, loader);
            Class<? extends Plugin> pluginClass = jarClass.asSubclass(Plugin.class);
            Plugin plugin = pluginClass.getDeclaredConstructor().newInstance();
            loader.init((org.bukkit.plugin.java.JavaPlugin) plugin);
            try {
                org.patchbukkit.loader.PatchBukkitBootstrap.registerPluginCommands(plugin, description);
            } catch (Throwable ignored) {}
            try {
                plugin.onLoad();
            } catch (Throwable t) {
                server.getLogger().log(Level.SEVERE, "Error loading " + plugin.getName(), t);
                t.printStackTrace();
            }
            registerPlugin(plugin);
            return plugin;
        } catch (Throwable e) {
            throw new InvalidPluginException("Failed to instantiate main class " + mainClass + " for plugin " + description.getName(), e);
        }
    }

    @Override
    public @NotNull Plugin[] loadPlugins(@NotNull File directory) {
        if (directory == null || !directory.isDirectory()) {
            return new Plugin[0];
        }

        File[] files = directory.listFiles((dir, name) -> name.endsWith(".jar"));
        if (files == null) {
            return new Plugin[0];
        }

        return loadPlugins(files);
    }

    @Override
    public @NotNull Plugin[] loadPlugins(@NonNull @NotNull File[] files) {
        java.util.List<Plugin> result = new java.util.ArrayList<>();
        for (File file : files) {
            if (file != null && file.isFile() && file.getName().endsWith(".jar")) {
                try {
                    Plugin plugin = loadPlugin(file);
                    if (plugin != null) {
                        result.add(plugin);
                    }
                } catch (Exception e) {
                    server.getLogger().log(Level.SEVERE, "Could not load plugin '" + file.getName() + "' in folder '" + file.getParent() + "'", e);
                }
            }
        }
        return result.toArray(new Plugin[0]);
    }

    @Override
    public void disablePlugins() {
        Plugin[] plugins = getPlugins();
        for (int i = plugins.length - 1; i >= 0; i--) {
            disablePlugin(plugins[i]);
        }
    }

    @Override
    public void clearPlugins() {
        disablePlugins();
        plugins.clear();
        loadOrder.clear();
    }

    @Override
    public void callEvent(@NotNull Event event) throws IllegalStateException {
        this.eventManager.callEvent(event);
    }

    @Override
    public void registerEvents(@NotNull Listener listener, @NotNull Plugin plugin) {
        this.eventManager.registerEvents(listener, plugin);
    }

    @Override
    public void registerEvent(
        @NotNull Class<? extends Event> event,
        @NotNull Listener listener,
        @NotNull EventPriority priority,
        @NotNull EventExecutor executor,
        @NotNull Plugin plugin
    ) {
        this.eventManager.registerEvent(
            event,
            listener,
            priority,
            executor,
            plugin
        );
    }

    @Override
    public void registerEvent(
        @NotNull Class<? extends Event> event,
        @NotNull Listener listener,
        @NotNull EventPriority priority,
        @NotNull EventExecutor executor,
        @NotNull Plugin plugin,
        boolean ignoreCancelled
    ) {
        this.eventManager.registerEvent(
            event,
            listener,
            priority,
            executor,
            plugin,
            ignoreCancelled
        );
    }

    @Override
    public void enablePlugin(@NotNull Plugin plugin) {
        if (plugin == null) {
            return;
        }
        if (!plugin.isEnabled()) {
            registerDescriptionPermissions(plugin);
            try {
                if (plugin instanceof org.bukkit.plugin.java.JavaPlugin javaPlugin) {
                    javaPlugin.setEnabled(true);
                } else {
                    plugin.getPluginLoader().enablePlugin(plugin);
                }
            } catch (Throwable ex) {
                server.getLogger().log(Level.SEVERE, "Error enabling " + plugin.getName() + " (Is it up to date?)", ex);
            }
        }
    }

    // Mirrors CraftServer#enablePlugin: plugin.yml permissions are registered just before enabling.
    private void registerDescriptionPermissions(@NotNull Plugin plugin) {
        for (Permission perm : plugin.getDescription().getPermissions()) {
            try {
                this.permissionManager.addPermission(perm);
            } catch (IllegalArgumentException ex) {
                server.getLogger().log(Level.WARNING, "Plugin " + plugin.getDescription().getFullName()
                    + " tried to register permission '" + perm.getName() + "' but it's already registered", ex);
            }
        }
    }

    @Override
    public void disablePlugin(@NotNull Plugin plugin) {
        if (plugin == null) {
            return;
        }
        if (plugin.isEnabled()) {
            try {
                if (plugin instanceof org.bukkit.plugin.java.JavaPlugin javaPlugin) {
                    javaPlugin.setEnabled(false);
                } else {
                    plugin.getPluginLoader().disablePlugin(plugin);
                }
            } catch (Throwable ex) {
                server.getLogger().log(Level.SEVERE, "Error disabling " + plugin.getName(), ex);
            }
        }
    }

    @Override
    public @Nullable Permission getPermission(@NotNull String name) {
        return this.permissionManager.getPermission(name);
    }

    @Override
    public void addPermission(@NotNull Permission perm) {
        this.permissionManager.addPermission(perm);
    }

    @Override
    public void removePermission(@NotNull Permission perm) {
        this.permissionManager.removePermission(perm);
    }

    @Override
    public void removePermission(@NotNull String name) {
        this.permissionManager.removePermission(name);
    }

    @Override
    public @NotNull Set<Permission> getDefaultPermissions(boolean op) {
        return this.permissionManager.getDefaultPermissions(op);
    }

    @Override
    public void recalculatePermissionDefaults(@NotNull Permission perm) {
        this.permissionManager.recalculatePermissionDefaults(perm);
    }

    @Override
    public void subscribeToPermission(@NotNull String permission, @NotNull Permissible permissible) {
        this.permissionManager.subscribeToPermission(permission, permissible);
    }

    @Override
    public void unsubscribeFromPermission(@NotNull String permission, @NotNull Permissible permissible) {
        this.permissionManager.unsubscribeFromPermission(permission, permissible);
    }

    @Override
    public @NotNull Set<Permissible> getPermissionSubscriptions(@NotNull String permission) {
        return this.permissionManager.getPermissionSubscriptions(permission);
    }

    @Override
    public void subscribeToDefaultPerms(boolean op, @NotNull Permissible permissible) {
        this.permissionManager.subscribeToDefaultPerms(op, permissible);
    }

    @Override
    public void unsubscribeFromDefaultPerms(boolean op, @NotNull Permissible permissible) {
        this.permissionManager.unsubscribeFromDefaultPerms(op, permissible);
    }

    @Override
    public @NotNull Set<Permissible> getDefaultPermSubscriptions(boolean op) {
        return this.permissionManager.getDefaultPermSubscriptions(op);
    }

    @Override
    public @NotNull Set<Permission> getPermissions() {
        return this.permissionManager.getPermissions();
    }

    @Override
    public void addPermissions(@NotNull List<Permission> perm) {
        this.permissionManager.addPermissions(perm);
    }

    @Override
    public void clearPermissions() {
        this.permissionManager.clearPermissions();
    }

    @Override
    public void overridePermissionManager(@NotNull Plugin plugin, @Nullable PermissionManager permissionManager) {
        this.permissionManager = permissionManager != null
            ? permissionManager
            : new PatchBukkitPermissionManager(this.simplePluginManager);
    }

    @Override
    public boolean useTimings() {
        return false;
    }

    @Override
    public boolean isTransitiveDependency(
        PluginMeta pluginMeta,
        PluginMeta dependencyConfig
    ) {
        if (pluginMeta == null || dependencyConfig == null) {
            return false;
        }
        return pluginMeta.getPluginDependencies().contains(dependencyConfig.getName());
    }
}
