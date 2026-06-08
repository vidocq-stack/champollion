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
package io.vidocq.champollion.codegen.apt;

import io.vidocq.champollion.jsonb.spi.JsonbBinding;
import jakarta.json.Json;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.stream.JsonGenerator;
import jakarta.json.stream.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5.4 — vérifie que le binding statique généré par l'APT produit le <em>même</em>
 * comportement observable que le runtime introspectif :
 * <ul>
 *   <li>{@code toJson(o)} : caractère pour caractère identique</li>
 *   <li>{@code fromJson(json)} : objets equals</li>
 * </ul>
 *
 * <p>Méthode :</p>
 * <ol>
 *   <li>compiler un record {@code @JsonbStatic} avec le {@link JsonbStaticProcessor}</li>
 *   <li>charger le binding statique généré dans un {@link URLClassLoader}</li>
 *   <li>construire un {@link Jsonb} runtime avec {@code withStaticBindings(List.of())}
 *       pour forcer l'introspection (court-circuit du ServiceLoader)</li>
 *   <li>comparer pour chaque instance fournie</li>
 * </ol>
 */
@DisplayName("M5.4 — differential testing static binding vs runtime")
class DifferentialBindingTest {

    @Test
    void simple_record_static_equals_runtime(@TempDir Path tmp) throws Exception {
        String src = """
                package generated.diff;
                import io.vidocq.champollion.jsonb.spi.JsonbStatic;
                @JsonbStatic
                public record Coord(int x, int y) {}
                """;
        Setup s = setup(tmp, "generated.diff.Coord", src);

        Object instance = s.targetClass.getDeclaredConstructor(int.class, int.class).newInstance(3, 4);
        assertWriteEquals(s, instance);
        assertReadEquals(s, "{\"x\":7,\"y\":8}");
    }

    @Test
    void mixed_record_string_and_primitives(@TempDir Path tmp) throws Exception {
        String src = """
                package generated.diff;
                import io.vidocq.champollion.jsonb.spi.JsonbStatic;
                @JsonbStatic
                public record Mixed(String name, int age, boolean active, long id, double score) {}
                """;
        Setup s = setup(tmp, "generated.diff.Mixed", src);
        Object instance = s.targetClass.getDeclaredConstructor(
                String.class, int.class, boolean.class, long.class, double.class)
                .newInstance("Alice", 30, true, 12345L, 9.5);
        assertWriteEquals(s, instance);
        assertReadEquals(s, "{\"name\":\"Bob\",\"age\":40,\"active\":false,\"id\":99,\"score\":1.5}");
    }

    @Test
    void roundtrip_static_then_runtime_recovers_value(@TempDir Path tmp) throws Exception {
        String src = """
                package generated.diff;
                import io.vidocq.champollion.jsonb.spi.JsonbStatic;
                @JsonbStatic
                public record Pair(String a, String b) {}
                """;
        Setup s = setup(tmp, "generated.diff.Pair", src);
        Object original = s.targetClass.getDeclaredConstructor(String.class, String.class)
                .newInstance("hello", "world");

        // STATIC.write → runtime.read doit redonner un objet equals à original.
        var sw = new StringWriter();
        try (JsonGenerator g = Json.createGenerator(sw)) {
            s.staticBinding.write(g, original);
        }
        Object viaRuntime = s.runtimeJsonb.fromJson(sw.toString(), s.targetClass);
        assertEquals(original, viaRuntime);

        // runtime.write → STATIC.read doit aussi redonner un objet equals.
        String runtimeJson = s.runtimeJsonb.toJson(original);
        try (JsonParser p = Json.createParser(new StringReader(runtimeJson))) {
            Object viaStatic = s.staticBinding.read(p);
            assertEquals(original, viaStatic);
        }
    }

    // ============================================================
    // Plumbing
    // ============================================================

    private record Setup(Class<?> targetClass, JsonbBinding<Object> staticBinding, Jsonb runtimeJsonb) {}

    @SuppressWarnings("unchecked")
    private Setup setup(Path tmp, String fqn, String source) throws Exception {
        ClassLoader cl = compile(tmp, fqn, source);
        Class<?> target = cl.loadClass(fqn);
        Class<?> bindingClass = cl.loadClass(fqn + "$$Binding");
        var staticBinding = (JsonbBinding<Object>) bindingClass.getDeclaredConstructor().newInstance();

        // The compiled static binding lives in 'cl' (temporary URLClassLoader);
        // the JsonbBuilder.create() of the test, on the other hand, uses the test's ClassLoader.
        // These two ClassLoaders are disjoint: the runtime's ServiceLoader does not see
        // the static binding → introspection guaranteed. This is exactly what
        // we want for differential testing.
        Jsonb runtimeJsonb = JsonbBuilder.create();

        return new Setup(target, staticBinding, runtimeJsonb);
    }

    private void assertWriteEquals(Setup s, Object instance) {
        // STATIC
        var sw1 = new StringWriter();
        try (JsonGenerator g = Json.createGenerator(sw1)) {
            s.staticBinding.write(g, instance);
        }
        String staticJson = sw1.toString();

        // RUNTIME
        String runtimeJson = s.runtimeJsonb.toJson(instance, (Type) s.targetClass);

        assertEquals(runtimeJson, staticJson,
                "Static and runtime serialization must produce identical JSON for type " + s.targetClass);
    }

    private void assertReadEquals(Setup s, String json) {
        Object viaRuntime = s.runtimeJsonb.fromJson(json, s.targetClass);
        Object viaStatic;
        try (JsonParser p = Json.createParser(new StringReader(json))) {
            viaStatic = s.staticBinding.read(p);
        }
        assertEquals(viaRuntime, viaStatic,
                "Static and runtime deserialization must produce equal objects for type " + s.targetClass);
    }

    private ClassLoader compile(Path tmp, String fqn, String source) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Path out = tmp.resolve("out");
        Files.createDirectories(out);
        var fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
        var src = new SimpleJavaFileObject(
                URI.create("string:///" + fqn.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension),
                JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return source; }
        };
        // Surefire en JPMS pose les dépendances sur le module path, pas le classpath.
        // On concatène les deux pour garantir que javac voit JsonbStatic / JsonbBinding.
        String cp = System.getProperty("java.class.path");
        String mp = System.getProperty("jdk.module.path", "");
        String fullCp = mp.isEmpty() ? cp : cp + java.io.File.pathSeparator + mp;
        var task = compiler.getTask(null, fm, null,
                List.of("-d", out.toString(), "--release", "25",
                        "-cp", fullCp,
                        "-processor", JsonbStaticProcessor.class.getName()),
                null, List.of(src));
        boolean ok = task.call();
        fm.close();
        assertTrue(ok, "compilation must succeed");
        return new URLClassLoader(new URL[] { out.toUri().toURL() }, getClass().getClassLoader());
    }
}
