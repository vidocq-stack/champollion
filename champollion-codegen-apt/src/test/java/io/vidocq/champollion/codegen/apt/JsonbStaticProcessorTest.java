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
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("JsonbStaticProcessor — generates JsonbBinding for records (M5.2)")
class JsonbStaticProcessorTest {

    /**
     * Compile {@code source} avec {@link JsonbStaticProcessor} actif, dans un
     * répertoire temporaire. Renvoie un {@link ClassLoader} pointant vers la sortie.
     */
    private ClassLoader compileWithProcessor(Path tmp, String fqn, String source) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler — run on a JDK, not a JRE.");
        }
        Path out = tmp.resolve("out");
        Files.createDirectories(out);

        // Classpath of the current process (so the annotated record can import JsonbStatic).
        String cp = System.getProperty("java.class.path");
        // Also add module path: our modules are loaded via --module-path by surefire.
        String mp = System.getProperty("jdk.module.path", "");

        var fileManager = compiler.getStandardFileManager(null, null, java.nio.charset.StandardCharsets.UTF_8);

        JavaFileObject src = new InMemorySource(fqn, source);
        var task = compiler.getTask(
                null,
                fileManager,
                null,
                List.of(
                        "-d", out.toString(),
                        "--release", "25",
                        "-cp", cp + (mp.isEmpty() ? "" : java.io.File.pathSeparator + mp),
                        "-processor", JsonbStaticProcessor.class.getName()
                ),
                null,
                List.of(src)
        );
        boolean ok = task.call();
        assertTrue(ok, "compilation must succeed");
        fileManager.close();

        return new URLClassLoader(new URL[] { out.toUri().toURL() }, getClass().getClassLoader());
    }

    private static final class InMemorySource extends SimpleJavaFileObject {
        private final String content;
        InMemorySource(String fqn, String content) {
            super(URI.create("string:///" + fqn.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.content = content;
        }
        @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return content; }
    }

    @Test
    void generates_binding_for_simple_record(@TempDir Path tmp) throws Exception {
        String src = """
                package generated.test;
                import io.vidocq.champollion.jsonb.spi.JsonbStatic;
                @JsonbStatic
                public record Coord(int x, int y) {}
                """;
        ClassLoader cl = compileWithProcessor(tmp, "generated.test.Coord", src);

        // Le binding généré doit être chargeable.
        Class<?> bindingClass = cl.loadClass("generated.test.Coord$$Binding");
        @SuppressWarnings("unchecked")
        JsonbBinding<Object> binding = (JsonbBinding<Object>) bindingClass.getDeclaredConstructor().newInstance();

        // Construire un Coord(3,4) via réflexion (chargé dans le ClassLoader temporaire).
        Class<?> coordClass = cl.loadClass("generated.test.Coord");
        Object coord = coordClass.getDeclaredConstructor(int.class, int.class).newInstance(3, 4);

        // write
        var sw = new StringWriter();
        try (JsonGenerator g = Json.createGenerator(sw)) {
            binding.write(g, coord);
        }
        assertEquals("{\"x\":3,\"y\":4}", sw.toString());

        // read
        try (JsonParser p = Json.createParser(new StringReader("{\"x\":7,\"y\":8}"))) {
            Object back = binding.read(p);
            assertEquals(7, coordClass.getMethod("x").invoke(back));
            assertEquals(8, coordClass.getMethod("y").invoke(back));
        }
    }

    @Test
    void generates_services_file_with_binding_fqn(@TempDir Path tmp) throws Exception {
        String src = """
                package generated.test;
                import io.vidocq.champollion.jsonb.spi.JsonbStatic;
                @JsonbStatic
                public record Pair(String a, String b) {}
                """;
        compileWithProcessor(tmp, "generated.test.Pair", src);
        Path services = tmp.resolve("out/META-INF/services/io.vidocq.champollion.jsonb.spi.JsonbBinding");
        assertTrue(Files.exists(services), "ServiceLoader file must be created");
        try (Stream<String> lines = Files.lines(services)) {
            String content = lines.collect(Collectors.joining("\n"));
            assertTrue(content.contains("generated.test.Pair$$Binding"),
                    "Generated binding FQN must be registered in META-INF/services");
        }
    }

    @Test
    void supports_string_and_primitives(@TempDir Path tmp) throws Exception {
        String src = """
                package generated.test;
                import io.vidocq.champollion.jsonb.spi.JsonbStatic;
                @JsonbStatic
                public record Mixed(String name, int age, boolean active, long id, double score) {}
                """;
        ClassLoader cl = compileWithProcessor(tmp, "generated.test.Mixed", src);
        Class<?> mixed = cl.loadClass("generated.test.Mixed");
        Object m = mixed.getDeclaredConstructor(String.class, int.class, boolean.class, long.class, double.class)
                .newInstance("Alice", 30, true, 12345L, 9.5);

        @SuppressWarnings("unchecked")
        JsonbBinding<Object> binding = (JsonbBinding<Object>)
                cl.loadClass("generated.test.Mixed$$Binding").getDeclaredConstructor().newInstance();

        var sw = new StringWriter();
        try (JsonGenerator g = Json.createGenerator(sw)) {
            binding.write(g, m);
        }
        // Ordre des composants record préservé
        assertEquals("{\"name\":\"Alice\",\"age\":30,\"active\":true,\"id\":12345,\"score\":9.5}", sw.toString());

        try (JsonParser p = Json.createParser(new StringReader(sw.toString()))) {
            Object back = binding.read(p);
            assertEquals("Alice", mixed.getMethod("name").invoke(back));
            assertEquals(30, mixed.getMethod("age").invoke(back));
            assertEquals(true, mixed.getMethod("active").invoke(back));
            assertEquals(12345L, mixed.getMethod("id").invoke(back));
            assertEquals(9.5, mixed.getMethod("score").invoke(back));
        }
    }

    @Test
    void rejects_non_record(@TempDir Path tmp) throws Exception {
        String src = """
                package generated.test;
                import io.vidocq.champollion.jsonb.spi.JsonbStatic;
                @JsonbStatic
                public class NotARecord { public int x; }
                """;
        // Compilation must fail (printMessage ERROR) — capture without crashing the test runner.
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Path out = tmp.resolve("out");
        Files.createDirectories(out);
        var fm = compiler.getStandardFileManager(null, null, java.nio.charset.StandardCharsets.UTF_8);
        var task = compiler.getTask(
                null, fm, null,
                List.of("-d", out.toString(), "--release", "25",
                        "-cp", System.getProperty("java.class.path"),
                        "-processor", JsonbStaticProcessor.class.getName()),
                null,
                List.of(new InMemorySource("generated.test.NotARecord", src))
        );
        boolean ok = task.call();
        fm.close();
        assertTrue(!ok, "compilation should fail when @JsonbStatic is on a non-record");
    }
}
