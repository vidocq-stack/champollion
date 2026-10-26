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
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vidocq/vidocq#382: in a Vidocq application, Champollion sits in the parent layer and the application's
 * {@code META-INF/services/...JsonbBinding} in the application layer, a child loader of it. Champollion looked the
 * bindings up through its own class loader only, so it never saw the {@code @JsonbStatic} bindings the application
 * generated, and every type went through the runtime registry.
 */
@DisplayName("Static bindings of the application's class loader (Vidocq/vidocq#382)")
class StaticBindingsClassLoaderTest {

    /** The application's type. */
    public record Point(int x, int y) {}

    /** A provider of the application layer: public, with a public no-argument constructor, as ServiceLoader requires. */
    public static final class PointBinding implements JsonbBinding<Point> {
        @Override public Class<Point> type() { return Point.class; }
        @Override public void write(JsonGenerator g, Point value) {
            g.writeStartObject();
            g.write("x", value.x());
            g.write("y", value.y());
            g.writeEnd();
        }
        @Override public Point read(JsonParser p) { throw new UnsupportedOperationException(); }
    }

    /**
     * The application layer's loader: a child of the library's, which defines the application's own classes itself.
     * The bindings must be defined here, in an unnamed module: ServiceLoader ignores a provider that a services file
     * names when its class belongs to a named module, as the test classes do.
     */
    private static final class ApplicationLayer extends URLClassLoader {
        private static final String APPLICATION = StaticBindingsClassLoaderTest.class.getName();

        ApplicationLayer(Path services) throws IOException {
            super(new URL[] {services.toUri().toURL(), testClasses()},
                    StaticBindingsClassLoaderTest.class.getClassLoader());
        }

        private static URL testClasses() {
            return StaticBindingsClassLoaderTest.class.getProtectionDomain().getCodeSource().getLocation();
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
    }

    private static ApplicationLayer applicationLayer(Path dir) throws IOException {
        Path services = dir.resolve("META-INF/services");
        Files.createDirectories(services);
        Files.writeString(services.resolve(JsonbBinding.class.getName()), PointBinding.class.getName() + "\n");
        return new ApplicationLayer(dir);
    }

    @Test
    void bindingsOfTheContextClassLoaderAreUsed(@TempDir Path dir) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try (ApplicationLayer app = applicationLayer(dir)) {
            thread.setContextClassLoader(app);
            ChampollionJsonb jsonb = new ChampollionJsonbBuilder().buildChampollion();
            Class<?> point = app.loadClass(Point.class.getName());
            assertNotNull(jsonb.staticBindingsView().get(point),
                    "the binding listed in the application's services file must be found");
            Object value = point.getConstructor(int.class, int.class).newInstance(1, 2);
            assertTrue(jsonb.toJson(value).contains("\"x\":1"));
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
