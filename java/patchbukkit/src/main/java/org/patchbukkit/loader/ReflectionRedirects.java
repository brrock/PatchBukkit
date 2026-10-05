package org.patchbukkit.loader;

import java.util.Map;

/**
 * Targets for the {@code Class.forName} calls that {@link BytecodeTransformer} rewrites in
 * plugin bytecode. A CraftBukkit class whose role is played by a PatchBukkit class is looked
 * up under the PatchBukkit name instead, so reflective access (for example LuckPerms reading
 * {@code CraftHumanEntity.perm}) reaches the objects the server actually hands out.
 */
public final class ReflectionRedirects {

    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private static final Map<String, String> CLASS_ALIASES = Map.of(
        "org.bukkit.craftbukkit.entity.CraftHumanEntity", "org.patchbukkit.entity.PatchBukkitHumanEntity"
    );

    private ReflectionRedirects() {}

    /** Returns the PatchBukkit class name that stands in for {@code name}, or {@code name} itself. */
    public static String alias(String name) {
        if (name == null) {
            return null;
        }
        return CLASS_ALIASES.getOrDefault(name, name);
    }

    /** Replacement for {@link Class#forName(String)}, resolved against the caller's loader. */
    public static Class<?> forName(String name) throws ClassNotFoundException {
        ClassLoader loader = WALKER.getCallerClass().getClassLoader();
        return Class.forName(alias(name), true, loader);
    }

    /** Replacement for {@link Class#forName(String, boolean, ClassLoader)}. */
    public static Class<?> forName(String name, boolean initialize, ClassLoader loader) throws ClassNotFoundException {
        return Class.forName(alias(name), initialize, loader);
    }
}
