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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * BUG-20261102-02: a {@code Jsonb} that has written a collection stays reachable from the JDK class of that
 * collection ({@code ImmutableCollections$ListN}, ...), which never dies. The per-instance {@link ClassValue} of its
 * registry keeps its entry in that class, and the value of the entry (the writer of the collection, a lambda over the
 * registry) holds the registry back: the registry is reachable from the {@link ClassValue} that is the weak key of its
 * own entry. The registry holds the generated bindings of the application layer it was built for, hence the
 * layer: every layer a hot reload discarded stayed reachable through the {@code Jsonb} that served it.
 */
@DisplayName("A Jsonb and the layer it served are collectable (BUG-20261102-02)")
class JsonbLayerLeakTest {

    /** The application's type, defined again by every layer. */
    public record Bag(List<String> names, Map<String, Integer> counts) {}

    /** The generated binding of the application's type, listed in the services files of the layer. */
    public static final class BagBinding implements JsonbBinding<Bag> {
        @Override public Class<Bag> type() { return Bag.class; }
        @Override public void write(JsonGenerator g, Bag value) {
            g.writeStartObject();
            g.write("size", value.names().size());
            g.writeEnd();
        }
        @Override public Bag read(JsonParser p) { throw new UnsupportedOperationException(); }
    }

    /** An application layer: a child loader of the library's that defines the application's classes itself. */
    private static final class ApplicationLayer extends URLClassLoader {
        private static final String APPLICATION = JsonbLayerLeakTest.class.getName();

        ApplicationLayer(Path services) throws IOException {
            super(new URL[] {services.toUri().toURL(),
                    JsonbLayerLeakTest.class.getProtectionDomain().getCodeSource().getLocation()},
                    JsonbLayerLeakTest.class.getClassLoader());
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

        Object bag(int n) throws ReflectiveOperationException {
            return loadClass(Bag.class.getName()).getConstructor(List.class, Map.class)
                    .newInstance(List.of("a", "b", "c"), Map.of("n", n, "m", n));
        }
    }

    private static ApplicationLayer applicationLayer(Path dir) throws IOException {
        Path services = dir.resolve("META-INF/services");
        Files.createDirectories(services);
        Files.writeString(services.resolve(JsonbBinding.class.getName()), BagBinding.class.getName() + "\n");
        return new ApplicationLayer(dir);
    }

    /** Asks the collector until it hands a reference over: no sleeping, a bounded wait. */
    private static void collect(ReferenceQueue<Object> queue) throws InterruptedException {
        for (int i = 0; i < 30; i++) {
            System.gc();
            if (queue.remove(100) != null) return;
        }
    }

    /**
     * Serves a layer with {@code jsonb} (built here when null, under the layer, as Cassini does for the first one) and
     * returns the layer, closed and only weakly referenced.
     */
    private static WeakReference<Object> serve(Path dir, ChampollionJsonb jsonb, ReferenceQueue<Object> queue)
            throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        ApplicationLayer layer = applicationLayer(dir);
        try {
            thread.setContextClassLoader(layer);
            ChampollionJsonb served = jsonb != null ? jsonb : new ChampollionJsonbBuilder().buildChampollion();
            Object value = layer.bag(1);
            assertEquals("{\"size\":3}", served.toJson(value));
            // What a REST resource returns: a collection of the application's entities, whose runtime class is a JDK
            // class (ListN, MapN) that outlives every layer.
            assertEquals("[{\"size\":3},{\"size\":3},{\"size\":3}]", served.toJson(List.of(value, value, value)));
            assertEquals("{\"n\":[{\"size\":3},{\"size\":3},{\"size\":3}]}",
                    served.toJson(Map.of("n", List.of(value, value, value))));
        } finally {
            thread.setContextClassLoader(previous);
            layer.close();
        }
        return new WeakReference<>(layer, queue);
    }

    @Test
    void aDroppedJsonbDoesNotKeepTheLayerItWasBuiltForAlive(@TempDir Path dir) throws Exception {
        ReferenceQueue<Object> queue = new ReferenceQueue<>();
        WeakReference<Object> layerRef = serve(dir, null, queue);
        collect(queue);
        assertNull(layerRef.get(), "the discarded layer must not stay reachable from a JDK class");
    }

    @Test
    void aJsonbKeptAcrossLayersDoesNotHoldTheDiscardedOnes(@TempDir Path first, @TempDir Path second)
            throws Exception {
        ChampollionJsonb jsonb = new ChampollionJsonbBuilder().buildChampollion();
        ReferenceQueue<Object> queue = new ReferenceQueue<>();
        WeakReference<Object> layerRef = serve(first, jsonb, queue);
        collect(queue);
        assertNull(layerRef.get(), "a long-lived Jsonb must not hold the classes of a discarded layer");
        // The Jsonb keeps serving the layer that follows.
        WeakReference<Object> next = serve(second, jsonb, queue);
        collect(queue);
        assertNull(next.get(), "nor those of the next one");
    }
}
