package org.patchbukkit.loader;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandlerFactory;
import java.security.CodeSigner;
import java.security.CodeSource;

/**
 * Superclass swapped in by {@link BytecodeTransformer} for plugin classes that extend
 * {@link URLClassLoader} directly, so classes loaded through a plugin's own loader
 * (jar-in-jar bootstraps such as LuckPerms, isolated dependency loaders) get the same
 * bytecode remapping as classes loaded by the plugin class loader itself.
 */
public class TransformingURLClassLoader extends URLClassLoader {

    static {
        ClassLoader.registerAsParallelCapable();
    }

    public TransformingURLClassLoader(URL[] urls) {
        super(urls);
    }

    public TransformingURLClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    public TransformingURLClassLoader(URL[] urls, ClassLoader parent, URLStreamHandlerFactory factory) {
        super(urls, parent, factory);
    }

    public TransformingURLClassLoader(String name, URL[] urls, ClassLoader parent) {
        super(name, urls, parent);
    }

    public TransformingURLClassLoader(String name, URL[] urls, ClassLoader parent, URLStreamHandlerFactory factory) {
        super(name, urls, parent, factory);
    }

    // Transformed bytecode references PatchBukkit classes, so they must resolve even when the
    // plugin gave this loader an isolated parent (e.g. the platform class loader).
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith("org.patchbukkit.")) {
            return Class.forName(name, false, TransformingURLClassLoader.class.getClassLoader());
        }
        return super.loadClass(name, resolve);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        URL resource = findResource(name.replace('.', '/').concat(".class"));
        if (resource == null) {
            throw new ClassNotFoundException(name);
        }

        byte[] bytes;
        URL codeSourceUrl = resource;
        try {
            URLConnection connection = resource.openConnection();
            if (connection instanceof JarURLConnection jarConnection) {
                codeSourceUrl = jarConnection.getJarFileURL();
            }
            try (InputStream in = connection.getInputStream()) {
                bytes = in.readAllBytes();
            }
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        }

        int lastDot = name.lastIndexOf('.');
        if (lastDot != -1) {
            String packageName = name.substring(0, lastDot);
            if (getDefinedPackage(packageName) == null) {
                try {
                    definePackage(packageName, null, null, null, null, null, null, null);
                } catch (IllegalArgumentException ignored) {
                    // Defined concurrently by another thread.
                }
            }
        }

        byte[] transformed = BytecodeTransformer.transform(bytes);
        return defineClass(name, transformed, 0, transformed.length, new CodeSource(codeSourceUrl, (CodeSigner[]) null));
    }
}
