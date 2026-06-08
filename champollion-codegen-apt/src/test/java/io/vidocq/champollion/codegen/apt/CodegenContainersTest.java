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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("M5.3 — APT codegen containers (List/Optional/Arrays)")
class CodegenContainersTest {

    @Test
    void list_of_string_static_equals_runtime(@TempDir Path tmp) throws Exception {
        String src = """
                package generated.cont;
                import io.vidocq.champollion.jsonb.spi.JsonbStatic;
                import java.util.List;
                @JsonbStatic
                public record Book(String title, List<String> tags) {}
                """;
        Setup s = setup(tmp, "generated.cont.Book", src);
        Object inst = s.targetClass.getDeclaredConstructor(String.class, List.class)
                .newInstance("J", List.of("scifi", "drama"));
        assertWriteEquals(s, inst);
    }

    @Test
    void list_of_int_roundtrip(@TempDir Path tmp) throws Exception {
        String src = """
                package generated.cont;
                import io.vidocq.champollion.jsonb.spi.JsonbStatic;
                import java.util.List;
                @JsonbStatic
                public record Counts(String label, List<Integer> values) {}
                """;
        Setup s = setup(tmp, "generated.cont.Counts", src);
        Object inst = s.targetClass.getDeclaredConstructor(String.class, List.class)
                .newInstance("scores", List.of(10, 20, 30));

        var sw = new StringWriter();
        try (JsonGenerator g = Json.createGenerator(sw)) {
            s.staticBinding.write(g, inst);
        }
        String json = sw.toString();
        assertEquals("{\"label\":\"scores\",\"values\":[10,20,30]}", json);

        try (JsonParser p = Json.createParser(new StringReader(json))) {
            Object back = s.staticBinding.read(p);
            assertEquals(inst, back);
        }
    }

    @Test
    void optional_string_present_and_empty(@TempDir Path tmp) throws Exception {
        String src = """
                package generated.cont;
                import io.vidocq.champollion.jsonb.spi.JsonbStatic;
                import java.util.Optional;
                @JsonbStatic
                public record User(String name, Optional<String> email) {}
                """;
        Setup s = setup(tmp, "generated.cont.User", src);

        Object withEmail = s.targetClass.getDeclaredConstructor(String.class, Optional.class)
                .newInstance("Alice", Optional.of("a@b.c"));
        var sw1 = new StringWriter();
        try (JsonGenerator g = Json.createGenerator(sw1)) { s.staticBinding.write(g, withEmail); }
        assertEquals("{\"name\":\"Alice\",\"email\":\"a@b.c\"}", sw1.toString());

        Object empty = s.targetClass.getDeclaredConstructor(String.class, Optional.class)
                .newInstance("Bob", Optional.empty());
        var sw2 = new StringWriter();
        try (JsonGenerator g = Json.createGenerator(sw2)) { s.staticBinding.write(g, empty); }
        // Optional.empty() omitted from JSON by static binding (consistent with §3.14.2 + runtime semantics).
        assertEquals("{\"name\":\"Bob\"}", sw2.toString());

        // Read on JSON without email → Optional.empty()
        try (JsonParser p = Json.createParser(new StringReader("{\"name\":\"Bob\"}"))) {
            Object back = s.staticBinding.read(p);
            Optional<?> emailField = (Optional<?>) s.targetClass.getMethod("email").invoke(back);
            assertTrue(emailField.isEmpty());
        }
    }

    @Test
    void int_array_and_string_array(@TempDir Path tmp) throws Exception {
        String src = """
                package generated.cont;
                import io.vidocq.champollion.jsonb.spi.JsonbStatic;
                @JsonbStatic
                public record Buffers(int[] values, String[] tags) {}
                """;
        Setup s = setup(tmp, "generated.cont.Buffers", src);
        Object inst = s.targetClass.getDeclaredConstructor(int[].class, String[].class)
                .newInstance(new int[] {1, 2, 3}, new String[] {"a", "b"});

        var sw = new StringWriter();
        try (JsonGenerator g = Json.createGenerator(sw)) { s.staticBinding.write(g, inst); }
        assertEquals("{\"values\":[1,2,3],\"tags\":[\"a\",\"b\"]}", sw.toString());

        try (JsonParser p = Json.createParser(new StringReader(sw.toString()))) {
            Object back = s.staticBinding.read(p);
            int[] valuesBack = (int[]) s.targetClass.getMethod("values").invoke(back);
            String[] tagsBack = (String[]) s.targetClass.getMethod("tags").invoke(back);
            assertEquals(3, valuesBack.length);
            assertEquals(1, valuesBack[0]); assertEquals(2, valuesBack[1]); assertEquals(3, valuesBack[2]);
            assertEquals(2, tagsBack.length);
            assertEquals("a", tagsBack[0]); assertEquals("b", tagsBack[1]);
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
        Jsonb runtimeJsonb = JsonbBuilder.create();
        return new Setup(target, staticBinding, runtimeJsonb);
    }

    private void assertWriteEquals(Setup s, Object instance) {
        var sw1 = new StringWriter();
        try (JsonGenerator g = Json.createGenerator(sw1)) { s.staticBinding.write(g, instance); }
        String staticJson = sw1.toString();
        String runtimeJson = s.runtimeJsonb.toJson(instance, (Type) s.targetClass);
        assertEquals(runtimeJson, staticJson,
                "Static and runtime serialization must produce identical JSON for type " + s.targetClass);
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
