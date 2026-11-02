/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.champollion.jsonb.internal;

import io.vidocq.champollion.jsonb.spi.JsonbBinding;
import jakarta.json.stream.JsonGenerator;
import jakarta.json.stream.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-20261102-01: a {@code Jsonb} outlives the application layer it was built for. In {@code vidocq dev}, a hot
 * reload boots a fresh application layer under the same library classes: the {@code Jsonb} Cassini built for the
 * first layer keeps serving requests, and the entities of the second layer, new {@link Class} objects defined by a
 * new loader, found no generated binding in an index made of the first layer's classes. They fell back to the
 * reflective mapping, which fails on a package the application does not open.
 */
@DisplayName("Static bindings of an application layer booted after the Jsonb (BUG-20261102-01)")
class StaticBindingsLaterLayerTest {

    /** The application's type, defined again by every layer. */
    public record Marked(int x) {}

    /**
     * Writes a marker the reflective mapping never writes, so that a test tells the generated binding from the
     * runtime mapping.
     */
    public static final class MarkedBinding implements JsonbBinding<Marked> {
        @Override public Class<Marked> type() { return Marked.class; }
        @Override public void write(JsonGenerator g, Marked value) {
            g.writeStartObject();
            g.write("generated", value.x());
            g.writeEnd();
        }
        @Override public Marked read(JsonParser p) { throw new UnsupportedOperationException(); }
    }

    /** A second type of the application, to read the services files of a layer once for all its types. */
    public record Other(int y) {}

    public static final class OtherBinding implements JsonbBinding<Other> {
        @Override public Class<Other> type() { return Other.class; }
        @Override public void write(JsonGenerator g, Other value) {
            g.writeStartObject();
            g.write("other", value.y());
            g.writeEnd();
        }
        @Override public Other read(JsonParser p) { throw new UnsupportedOperationException(); }
    }

    /** An application layer: a child loader of the library's that defines the application's classes itself. */
    private static final class ApplicationLayer extends URLClassLoader {
        private static final String APPLICATION = StaticBindingsLaterLayerTest.class.getName();

        ApplicationLayer(Path services) throws IOException {
            super(new URL[] {services.toUri().toURL(), testClasses()},
                    StaticBindingsLaterLayerTest.class.getClassLoader());
        }

        private static URL testClasses() {
            return StaticBindingsLaterLayerTest.class.getProtectionDomain().getCodeSource().getLocation();
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(APPLICATION)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                return loaded != null ? loaded : findClass(name);
            }
        }

        Object other(int y) throws ReflectiveOperationException {
            return loadClass(Other.class.getName()).getConstructor(int.class).newInstance(y);
        }

        Object marked(int x) throws ReflectiveOperationException {
            return loadClass(Marked.class.getName()).getConstructor(int.class).newInstance(x);
        }
    }

    private static ApplicationLayer applicationLayer(Path dir) throws IOException {
        Path services = dir.resolve("META-INF/services");
        Files.createDirectories(services);
        Files.writeString(services.resolve(JsonbBinding.class.getName()),
                MarkedBinding.class.getName() + "\n" + OtherBinding.class.getName() + "\n");
        return new ApplicationLayer(dir);
    }

    @Test
    void aLayerBootedAfterTheJsonbIsServedByItsOwnBindings(@TempDir Path first, @TempDir Path second) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try (ApplicationLayer one = applicationLayer(first); ApplicationLayer two = applicationLayer(second)) {
            thread.setContextClassLoader(one);
            ChampollionJsonb jsonb = new ChampollionJsonbBuilder().buildChampollion();
            assertTrue(jsonb.toJson(one.marked(1)).contains("generated"), "the first layer's binding is found");

            // The hot reload: the second layer's entity is another Class, the Jsonb is the same, the context loader
            // of the threads that serve requests is not necessarily the new layer's.
            Object reloaded = two.marked(2);
            assertNotSame(one.marked(1).getClass(), reloaded.getClass());
            assertEquals("{\"generated\":2}", jsonb.toJson(reloaded),
                    "the binding of the layer booted after the Jsonb must be used, not the reflective mapping");
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    void aTypeWithoutBindingKeepsTheRuntimeMapping(@TempDir Path dir) throws Exception {
        try (ApplicationLayer layer = applicationLayer(dir)) {
            ChampollionJsonb jsonb = new ChampollionJsonbBuilder().buildChampollion();
            record Plain(int y) {}
            assertEquals("{\"y\":3}", jsonb.toJson(new Plain(3)));
            assertNull(jsonb.staticBindingsView().get(Plain.class));
            assertEquals("{\"generated\":4}", jsonb.toJson(layer.marked(4)));
        }
    }

    /** The index must not keep a discarded layer alive: a hot reload would otherwise leak one layer per cycle. */
    @Test
    void aDiscardedLayerCanBeCollected(@TempDir Path first, @TempDir Path second) throws Exception {
        ChampollionJsonb jsonb = new ChampollionJsonbBuilder().buildChampollion();
        ReferenceQueue<ClassLoader> collected = new ReferenceQueue<>();
        WeakReference<ClassLoader> discarded;
        try (ApplicationLayer one = applicationLayer(first)) {
            assertEquals("{\"generated\":1}", jsonb.toJson(one.marked(1)));
            discarded = new WeakReference<>(one, collected);
        }
        // The queue hands the reference over once the loader is collected: no sleeping, a bounded wait for the collector.
        java.lang.ref.Reference<? extends ClassLoader> handedOver = null;
        for (int i = 0; i < 20 && handedOver == null; i++) {
            System.gc();
            handedOver = collected.remove(100);
        }
        assertNull(discarded.get(), "the Jsonb must not hold the discarded layer's classes");
        try (ApplicationLayer two = applicationLayer(second)) {
            assertEquals("{\"generated\":2}", jsonb.toJson(two.marked(2)));
        }
    }

    /** The services files of a layer are read once: its other types are answered from the same index. */
    @Test
    void theOtherTypesOfALayerAreServedToo(@TempDir Path dir) throws Exception {
        try (ApplicationLayer layer = applicationLayer(dir)) {
            ChampollionJsonb jsonb = new ChampollionJsonbBuilder().buildChampollion();
            assertEquals("{\"generated\":5}", jsonb.toJson(layer.marked(5)));
            assertEquals("{\"other\":6}", jsonb.toJson(layer.other(6)));
        }
    }

    /**
     * The bindings may be listed by a loader that is not the type's: the generated bindings of a dependency's types
     * are listed in the application's services files, while the dependency's classes come from a parent loader.
     */
    @Test
    void aBindingListedByTheContextClassLoaderServesATypeOfItsParent(@TempDir Path parent, @TempDir Path child)
            throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try (ApplicationLayer owner = new ApplicationLayer(parent);
             URLClassLoader listing = new URLClassLoader(new URL[] {servicesOnly(child)}, owner)) {
            ChampollionJsonb jsonb = new ChampollionJsonbBuilder().buildChampollion();
            thread.setContextClassLoader(listing);
            assertEquals("{\"generated\":7}", jsonb.toJson(owner.marked(7)));
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static URL servicesOnly(Path dir) throws IOException {
        Path services = dir.resolve("META-INF/services");
        Files.createDirectories(services);
        Files.writeString(services.resolve(JsonbBinding.class.getName()), MarkedBinding.class.getName() + "\n");
        return dir.toUri().toURL();
    }
}
