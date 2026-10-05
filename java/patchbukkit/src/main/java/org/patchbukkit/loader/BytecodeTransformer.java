package org.patchbukkit.loader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.util.logging.Level;
import java.util.logging.Logger;

public final class BytecodeTransformer {

    private static final Logger LOGGER = Logger.getLogger("BytecodeTransformer");

    private static Remapper createRemapper(String currentClassName) {
        final boolean isCraftBukkitReflection = currentClassName != null && currentClassName.contains("CraftBukkitReflection");

        return new Remapper() {
            @Override
            public String map(String internalName) {
                if (internalName == null) return null;

                // Missing dot after craftbukkit (e.g. Cloud command framework: org/bukkit/craftbukkitcommand -> org/bukkit/craftbukkit/command)
                if (internalName.startsWith("org/bukkit/craftbukkit") && !internalName.startsWith("org/bukkit/craftbukkit/")) {
                    return "org/bukkit/craftbukkit/" + internalName.substring("org/bukkit/craftbukkit".length());
                }

                // Remap legacy versioned CraftBukkit: org/bukkit/craftbukkit/v1_20_R3/... -> org/bukkit/craftbukkit/...
                if (internalName.startsWith("org/bukkit/craftbukkit/v")) {
                    String unversioned = internalName.replaceFirst("^org/bukkit/craftbukkit/v[0-9_]+R[0-9]+/", "org/bukkit/craftbukkit/");
                    return map(unversioned);
                }

                // Keep CraftServer as is inside CraftBukkitReflection to prevent breaking its reflection logic
                if (isCraftBukkitReflection && internalName.equals("org/bukkit/craftbukkit/CraftServer")) {
                    return internalName;
                }

                // Remap CraftServer -> PatchBukkitServer for seamless native casts and method calls
                if (internalName.equals("org/bukkit/craftbukkit/CraftServer")) {
                    return "org/patchbukkit/PatchBukkitServer";
                }

                // Remap CraftPlayer -> PatchBukkitPlayer for seamless native casts and getHandle()
                if (internalName.equals("org/bukkit/craftbukkit/entity/CraftPlayer")) {
                    return "org/patchbukkit/entity/PatchBukkitPlayer";
                }

                // Remap CraftHumanEntity -> PatchBukkitHumanEntity, the base of every Player we hand out
                if (internalName.equals("org/bukkit/craftbukkit/entity/CraftHumanEntity")) {
                    return "org/patchbukkit/entity/PatchBukkitHumanEntity";
                }

                // Remap CraftScheduler -> BukkitScheduler
                if (internalName.equals("org/bukkit/craftbukkit/scheduler/CraftScheduler")) {
                    return "org/bukkit/scheduler/BukkitScheduler";
                }

                return super.map(internalName);
            }
        };
    }

    private static final String URL_CLASS_LOADER = "java/net/URLClassLoader";
    private static final String TRANSFORMING_URL_CLASS_LOADER = "org/patchbukkit/loader/TransformingURLClassLoader";
    private static final String REFLECTION_REDIRECTS = "org/patchbukkit/loader/ReflectionRedirects";

    /**
     * Routes {@code Class.forName} through {@link ReflectionRedirects} and makes plugin class
     * loaders that extend {@code URLClassLoader} directly extend {@link TransformingURLClassLoader},
     * so the classes they load are transformed too.
     */
    private static final class PluginReflectionVisitor extends ClassVisitor {
        private boolean swapLoaderSuperclass;

        PluginReflectionVisitor(ClassVisitor cv) {
            super(Opcodes.ASM9, cv);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            if (URL_CLASS_LOADER.equals(superName)) {
                swapLoaderSuperclass = true;
                superName = TRANSFORMING_URL_CLASS_LOADER;
            }
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            return new MethodVisitor(Opcodes.ASM9, mv) {
                @Override
                public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean isInterface) {
                    if (opcode == Opcodes.INVOKESTATIC && "java/lang/Class".equals(owner) && "forName".equals(methodName)
                        && ("(Ljava/lang/String;)Ljava/lang/Class;".equals(methodDesc)
                            || "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;".equals(methodDesc))) {
                        owner = REFLECTION_REDIRECTS;
                    } else if (swapLoaderSuperclass && opcode == Opcodes.INVOKESPECIAL && URL_CLASS_LOADER.equals(owner)) {
                        owner = TRANSFORMING_URL_CLASS_LOADER;
                    }
                    super.visitMethodInsn(opcode, owner, methodName, methodDesc, isInterface);
                }
            };
        }
    }

    private BytecodeTransformer() {}

    public static byte[] transform(byte[] classBytes) {
        if (classBytes == null || classBytes.length == 0) {
            return classBytes;
        }
        try {
            ClassReader reader = new ClassReader(classBytes);
            String className = reader.getClassName();
            ClassWriter writer = new ClassWriter(reader, 0);
            Remapper remapper = createRemapper(className);
            ClassVisitor cv = new PluginReflectionVisitor(writer);
            cv = new ClassRemapper(cv, remapper);

            if (className != null && className.contains("CraftBukkitReflection")) {
                cv = new ClassVisitor(Opcodes.ASM9, cv) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                        if ("<clinit>".equals(name)) {
                            return new MethodVisitor(Opcodes.ASM9, mv) {
                                @Override
                                public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDesc) {
                                    if (opcode == Opcodes.PUTSTATIC && "CB_PKG_VERSION".equals(fieldName)) {
                                        // Pop whatever was calculated and push "."
                                        super.visitInsn(Opcodes.POP);
                                        super.visitLdcInsn(".");
                                    }
                                    super.visitFieldInsn(opcode, owner, fieldName, fieldDesc);
                                }
                            };
                        }
                        return mv;
                    }
                };
            }

            reader.accept(cv, ClassReader.EXPAND_FRAMES);
            return writer.toByteArray();
        } catch (Throwable t) {
            LOGGER.log(Level.FINE, "[BytecodeTransformer] Failed to transform class bytecode, returning original", t);
            return classBytes;
        }
    }
}
