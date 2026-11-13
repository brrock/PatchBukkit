package org.patchbukkit.entity;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.StubMethod;
import net.bytebuddy.matcher.ElementMatchers;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;

/**
 * Entity objects of the Bukkit interface a plugin expects (Zombie, Cow, ...). Each type gets a
 * generated subclass of PatchBukkitEntity / PatchBukkitLivingEntity that also implements the
 * type's interface; methods neither base class has return a default value. Without it a cast
 * such as {@code (Zombie) world.spawn(loc, Zombie.class)} fails.
 */
public final class TypedEntityClasses {

    private static final Logger LOGGER = Logger.getLogger("PatchBukkit");
    private static final Map<Class<?>, Constructor<? extends PatchBukkitEntity>> CONSTRUCTORS = new ConcurrentHashMap<>();

    private TypedEntityClasses() {}

    /** Returns an entity implementing the type's interface, or null when the plain classes do. */
    public static PatchBukkitEntity create(EntityType type, UUID uuid, int entityId) {
        Class<?> api = type == null ? null : type.getEntityClass();
        if (api == null || !api.isInterface()
            || api.isAssignableFrom(PatchBukkitLivingEntity.class)) {
            return null;
        }
        Constructor<? extends PatchBukkitEntity> ctor = CONSTRUCTORS.computeIfAbsent(api, TypedEntityClasses::define);
        if (ctor == null) {
            return null;
        }
        try {
            return ctor.newInstance(uuid, type.name(), entityId);
        } catch (ReflectiveOperationException e) {
            LOGGER.log(Level.WARNING, "[PatchBukkit] Could not instantiate entity for " + type, e);
            return null;
        }
    }

    private static Constructor<? extends PatchBukkitEntity> define(Class<?> api) {
        Class<? extends PatchBukkitEntity> base = LivingEntity.class.isAssignableFrom(api)
            ? PatchBukkitLivingEntity.class
            : PatchBukkitEntity.class;
        try {
            System.setProperty("net.bytebuddy.experimental", "true");
            Class<? extends PatchBukkitEntity> generated = new ByteBuddy()
                .subclass(base)
                .implement(api)
                .name("org.patchbukkit.entity.PatchBukkitGenerated" + api.getSimpleName())
                .method(ElementMatchers.isAbstract())
                .intercept(StubMethod.INSTANCE)
                .make()
                .load(base.getClassLoader(), ClassLoadingStrategy.UsingLookup.of(java.lang.invoke.MethodHandles.lookup()))
                .getLoaded();
            Constructor<? extends PatchBukkitEntity> ctor = generated.getConstructor(UUID.class, String.class, int.class);
            if (Modifier.isAbstract(generated.getModifiers())) {
                return null;
            }
            return ctor;
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[PatchBukkit] Could not generate entity class for " + api.getName(), t);
            return null;
        }
    }
}
