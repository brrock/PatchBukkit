package org.patchbukkit.loader;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.InvalidPluginException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLoader;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.UnknownDependencyException;
import org.bukkit.plugin.java.JavaPlugin;
import org.patchbukkit.PatchBukkitPluginManager;

@SuppressWarnings({ "deprecation", "removal" })
public class PatchBukkitPluginLoader implements PluginLoader {

    private static final Logger LOGGER = Logger.getLogger("PatchBukkitPluginLoader");

    public static Plugin createPlugin(
        String jarPath,
        String mainClass,
        String extraClasspath,
        String libraryCoordinates
    ) {
        try {
            File jarFile = new File(jarPath);
            if (!jarFile.exists()) {
                LOGGER.severe(
                    "[PatchBukkit] Plugin file does not exist: " + jarPath
                );
                return null;
            }

            LinkedHashSet<URL> extraUrls = new LinkedHashSet<>();
            if (extraClasspath != null && !extraClasspath.isBlank()) {
                String[] paths = extraClasspath.split("[;:]");
                for (String path : paths) {
                    if (path == null || path.isBlank()) {
                        continue;
                    }
                    File extraFile = new File(path.trim());
                    if (extraFile.exists()) {
                        extraUrls.add(extraFile.toURI().toURL());
                    }
                }
            }

            if (libraryCoordinates != null && !libraryCoordinates.isBlank()) {
                File libsDir = new File(jarFile.getParentFile(), "patchbukkit-libs");
                if (!libsDir.exists()) {
                    libsDir.mkdirs();
                }
                List<File> libraries = LibraryResolver.resolveLibraries(
                    libraryCoordinates,
                    libsDir
                );
                for (File lib : libraries) {
                    if (lib != null && lib.exists()) {
                        extraUrls.add(lib.toURI().toURL());
                    }
                }
            }

            PatchBukkitPluginClassLoader classLoader =
                new PatchBukkitPluginClassLoader(
                    PatchBukkitPluginLoader.class.getClassLoader(),
                    jarFile,
                    extraUrls.toArray(new URL[0])
                );

            Class<?> jarClass = Class.forName(mainClass, true, classLoader);
            Class<? extends Plugin> pluginClass = jarClass.asSubclass(Plugin.class);
            Plugin plugin = pluginClass.getDeclaredConstructor().newInstance();
            if (plugin instanceof JavaPlugin javaPlugin) {
                classLoader.init(javaPlugin);
            }
            try {
                if (PatchBukkitPluginManager.unwrap(org.bukkit.Bukkit.getPluginManager()) instanceof PatchBukkitPluginManager pm) {
                    pm.registerPlugin(plugin);
                }
            } catch (Throwable ignored) {}
            try {
                PatchBukkitBootstrap.registerPluginCommands(plugin, classLoader.getDescription());
            } catch (Throwable ignored) {}
            try {
                plugin.onLoad();
            } catch (Throwable t) {
                LOGGER.log(Level.SEVERE, "[PatchBukkit] Error during onLoad() for " + mainClass, t);
            }
            return plugin;
        } catch (Throwable e) {
            LOGGER.log(Level.SEVERE, "[PatchBukkit] Failed to instantiate plugin class " + mainClass, e);
            return null;
        }
    }

    public static boolean enablePlugin(String pluginName) {
        try {
            if (PatchBukkitPluginManager.unwrap(org.bukkit.Bukkit.getPluginManager()) instanceof PatchBukkitPluginManager pm) {
                Plugin p = pm.getPlugin(pluginName);
                if (p != null) {
                    pm.enablePlugin(p);
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            LOGGER.log(Level.SEVERE, "[PatchBukkit] Failed to enable plugin " + pluginName, t);
            return false;
        }
    }

    /** Called from Rust on shutdown: disables every plugin in reverse load order. */
    public static void disableAllPlugins() {
        try {
            if (PatchBukkitPluginManager.unwrap(org.bukkit.Bukkit.getPluginManager()) instanceof PatchBukkitPluginManager pm) {
                pm.disablePlugins();
            }
        } catch (Throwable t) {
            LOGGER.log(Level.SEVERE, "[PatchBukkit] Failed to disable plugins", t);
        }
    }

    public static boolean disablePlugin(String pluginName) {
        try {
            if (PatchBukkitPluginManager.unwrap(org.bukkit.Bukkit.getPluginManager()) instanceof PatchBukkitPluginManager pm) {
                Plugin p = pm.getPlugin(pluginName);
                if (p != null) {
                    pm.disablePlugin(p);
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            LOGGER.log(Level.SEVERE, "[PatchBukkit] Failed to disable plugin " + pluginName, t);
            return false;
        }
    }

    @Override
    public Plugin loadPlugin(File file)
        throws InvalidPluginException, UnknownDependencyException {
        throw new UnsupportedOperationException("Use createPlugin() instead");
    }

    @Override
    public PluginDescriptionFile getPluginDescription(File file)
        throws InvalidDescriptionException {
        throw new UnsupportedOperationException(
            "Use PatchBukkitPluginClassLoader.getDescription() instead"
        );
    }

    @Override
    public Pattern[] getPluginFileFilters() {
        return new Pattern[] { Pattern.compile("\\.jar$") };
    }

    @Override
    public void enablePlugin(Plugin plugin) {
        if (plugin instanceof JavaPlugin javaPlugin) {
            try {
                javaPlugin.setEnabled(true);
                org.bukkit.Bukkit.getPluginManager().callEvent(new org.bukkit.event.server.PluginEnableEvent(plugin));
            } catch (Throwable ex) {
                org.bukkit.Bukkit.getLogger().log(
                    java.util.logging.Level.SEVERE,
                    "Error occurred while enabling " + plugin.getDescription().getFullName() + " (Is it up to date?)",
                    ex
                );
            }
        }
    }

    @Override
    public void disablePlugin(Plugin plugin) {
        if (plugin instanceof JavaPlugin javaPlugin) {
            try {
                javaPlugin.setEnabled(false);
                org.bukkit.Bukkit.getPluginManager().callEvent(new org.bukkit.event.server.PluginDisableEvent(plugin));
            } catch (Throwable ex) {
                org.bukkit.Bukkit.getLogger().log(
                    java.util.logging.Level.SEVERE,
                    "Error occurred while disabling " + plugin.getDescription().getFullName(),
                    ex
                );
            }
        }
    }

    @Override
    public Map<
        Class<? extends Event>,
        Set<RegisteredListener>
    > createRegisteredListeners(Listener listener, Plugin plugin) {
        Map<Class<? extends Event>, Set<RegisteredListener>> ret = new java.util.HashMap<>();
        Set<java.lang.reflect.Method> methods;
        try {
            Method[] publicMethods = listener.getClass().getMethods();
            Method[] declaredMethods = listener.getClass().getDeclaredMethods();
            methods = new java.util.HashSet<>(publicMethods.length + declaredMethods.length);
            for (Method method : publicMethods) {
                methods.add(method);
            }
            for (Method method : declaredMethods) {
                methods.add(method);
            }
        } catch (NoClassDefFoundError e) {
            plugin.getLogger().severe("Plugin " + plugin.getDescription().getFullName() + " has failed to register events for " + listener.getClass() + " because " + e.getMessage() + " does not exist.");
            return ret;
        }

        for (final Method method : methods) {
            final org.bukkit.event.EventHandler eh = method.getAnnotation(org.bukkit.event.EventHandler.class);
            if (eh == null) continue;

            if (method.isBridge() || method.isSynthetic()) {
                continue;
            }

            final Class<?> checkClass;
            if (method.getParameterTypes().length != 1 || !Event.class.isAssignableFrom(checkClass = method.getParameterTypes()[0])) {
                plugin.getLogger().severe(plugin.getDescription().getFullName() + " attempted to register an invalid EventHandler method signature \"" + method.toGenericString() + "\" in " + listener.getClass());
                continue;
            }

            final Class<? extends Event> eventClass = checkClass.asSubclass(Event.class);
            method.setAccessible(true);
            Set<RegisteredListener> eventSet = ret.computeIfAbsent(eventClass, k -> new java.util.HashSet<>());

            EventExecutor executor = (l, event) -> {
                try {
                    if (!eventClass.isAssignableFrom(event.getClass())) {
                        return;
                    }
                    method.invoke(l, event);
                } catch (java.lang.reflect.InvocationTargetException ex) {
                    throw new org.bukkit.event.EventException(ex.getCause());
                } catch (Throwable t) {
                    throw new org.bukkit.event.EventException(t);
                }
            };

            eventSet.add(new RegisteredListener(listener, executor, eh.priority(), plugin, eh.ignoreCancelled()));
        }
        return ret;
    }
}
