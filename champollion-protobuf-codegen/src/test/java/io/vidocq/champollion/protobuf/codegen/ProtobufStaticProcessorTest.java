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
package io.vidocq.champollion.protobuf.codegen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.vidocq.champollion.protobuf.Parser;
import io.vidocq.champollion.protobuf.ParserProvider;
import io.vidocq.champollion.protobuf.Protobuf;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link ProtobufStaticProcessor} via une compilation sur disque temporaire
 * (TempDir JUnit) — l'APT écrit ses sources générées et le service file dans
 * le {@code SOURCE_OUTPUT} / {@code CLASS_OUTPUT} qu'on examine ensuite.
 */
class ProtobufStaticProcessorTest {

    private static final String PERSON_SRC = """
            package io.test;

            import io.vidocq.champollion.protobuf.FieldType;
            import io.vidocq.champollion.protobuf.Message;
            import io.vidocq.champollion.protobuf.ProtobufField;
            import io.vidocq.champollion.protobuf.ProtobufMessage;
            import io.vidocq.champollion.protobuf.ProtobufStatic;
            import java.util.List;

            @ProtobufStatic
            @ProtobufMessage("io.test.Person")
            public record Person(
                @ProtobufField(number = 1, type = FieldType.STRING) String name,
                @ProtobufField(number = 2, type = FieldType.INT32) int age,
                @ProtobufField(number = 3, type = FieldType.STRING) List<String> tags
            ) implements Message {}
            """;

    @Nested
    @DisplayName("APT @ProtobufStatic — emission sources + service file")
    class Generation {

        @Test
        void generates_parser_and_provider_sources(@TempDir Path tmp) throws Exception {
            CompileResult r = compile(tmp, Map.of("io.test.Person", PERSON_SRC));
            // Sources générées dans tmp/sources-gen
            assertTrue(Files.exists(r.sourcesOutput.resolve("io/test/Person$$Parser.java")),
                    "Parser source missing");
            assertTrue(Files.exists(r.sourcesOutput.resolve("io/test/Person$$ParserProvider.java")),
                    "ParserProvider source missing");
        }

        @Test
        void service_file_lists_provider(@TempDir Path tmp) throws Exception {
            CompileResult r = compile(tmp, Map.of("io.test.Person", PERSON_SRC));
            Path svc = r.classesOutput.resolve("META-INF/services/io.vidocq.champollion.protobuf.ParserProvider");
            assertTrue(Files.exists(svc), "service file missing at " + svc);
            String content = Files.readString(svc);
            assertTrue(content.contains("io.test.Person$$ParserProvider"), content);
        }

        @Test
        void generated_parser_compiles_and_roundtrips(@TempDir Path tmp) throws Exception {
            CompileResult r = compile(tmp, Map.of("io.test.Person", PERSON_SRC));

            try (URLClassLoader cl = new URLClassLoader(
                    new URL[]{r.classesOutput.toUri().toURL()},
                    ProtobufStaticProcessorTest.class.getClassLoader())) {
                Class<?> personClass = cl.loadClass("io.test.Person");
                Object person = personClass.getDeclaredConstructors()[0]
                        .newInstance("alice", 30, List.of("a", "b"));

                byte[] bytes = Protobuf.toByteArray(person);
                Object back = Protobuf.parser(personClass).parseFrom(bytes);
                assertEquals(person, back);
            }
        }

        @Test
        void generated_parser_reads_packed_repeated_scalars(@TempDir Path tmp) throws Exception {
            // Encoding §packed: repeated scalars arrive as one length-delimited payload.
            CompileResult r = compile(tmp, Map.of("io.test.Scores", SCORES_SRC));
            String parser = Files.readString(r.sourcesOutput.resolve("io/test/Scores$$Parser.java"));
            assertTrue(parser.contains("in.pushLimit(sz)"), parser);

            try (URLClassLoader cl = new URLClassLoader(
                    new URL[]{r.classesOutput.toUri().toURL()},
                    ProtobufStaticProcessorTest.class.getClassLoader())) {
                Class<?> scoresClass = cl.loadClass("io.test.Scores");
                Object scores = scoresClass.getDeclaredConstructors()[0].newInstance(List.of(3, 270, 86942));
                Object back = Protobuf.parser(scoresClass).parseFrom(Protobuf.toByteArray(scores));
                assertEquals(scores, back);
            }
        }
    }

    private static final String ALL_TYPES_SRC = """
            package io.test;

            import io.vidocq.champollion.protobuf.FieldType;
            import io.vidocq.champollion.protobuf.Message;
            import io.vidocq.champollion.protobuf.ProtobufField;
            import io.vidocq.champollion.protobuf.ProtobufMessage;
            import io.vidocq.champollion.protobuf.ProtobufStatic;
            import java.util.List;

            @ProtobufStatic
            @ProtobufMessage("io.test.AllTypes")
            public record AllTypes(
                @ProtobufField(number = 1, type = FieldType.INT64) long i64,
                @ProtobufField(number = 2, type = FieldType.UINT32) int u32,
                @ProtobufField(number = 3, type = FieldType.UINT64) long u64,
                @ProtobufField(number = 4, type = FieldType.SINT32) int s32,
                @ProtobufField(number = 5, type = FieldType.SINT64) long s64,
                @ProtobufField(number = 6, type = FieldType.FIXED32) int f32,
                @ProtobufField(number = 7, type = FieldType.SFIXED32) int sf32,
                @ProtobufField(number = 8, type = FieldType.FLOAT) float flt,
                @ProtobufField(number = 9, type = FieldType.FIXED64) long f64,
                @ProtobufField(number = 10, type = FieldType.SFIXED64) long sf64,
                @ProtobufField(number = 11, type = FieldType.DOUBLE) double dbl,
                @ProtobufField(number = 12, type = FieldType.BOOL) boolean flag,
                @ProtobufField(number = 13, type = FieldType.BYTES) byte[] blob,
                @ProtobufField(number = 14, type = FieldType.ENUM) Level level,
                @ProtobufField(number = 15, type = FieldType.MESSAGE) Point point,
                @ProtobufField(number = 16, type = FieldType.INT32, explicitPresence = true) Integer maybe,
                @ProtobufField(number = 17, type = FieldType.INT64) Long boxed,
                @ProtobufField(number = 18, type = FieldType.DOUBLE) List<Double> doubles,
                @ProtobufField(number = 19, type = FieldType.BOOL) List<Boolean> flags,
                @ProtobufField(number = 20, type = FieldType.MESSAGE) List<Point> points,
                @ProtobufField(number = 21, type = FieldType.FLOAT) List<Float> floats,
                @ProtobufField(number = 22, type = FieldType.INT64) List<Long> longs
            ) implements Message {}
            """;

    private static final String LEVEL_SRC = """
            package io.test;

            public enum Level { LOW, HIGH }
            """;

    private static final String POINT_SRC = """
            package io.test;

            import io.vidocq.champollion.protobuf.FieldType;
            import io.vidocq.champollion.protobuf.Message;
            import io.vidocq.champollion.protobuf.ProtobufField;
            import io.vidocq.champollion.protobuf.ProtobufMessage;

            @ProtobufMessage("io.test.Point")
            public record Point(@ProtobufField(number = 1, type = FieldType.INT32) int x) implements Message {}
            """;

    @Nested
    @DisplayName("APT @ProtobufStatic — every field type, and the elements it refuses")
    class FieldTypes {

        @Test
        void generated_parser_reads_every_field_type(@TempDir Path tmp) throws Exception {
            CompileResult r = compile(tmp, Map.of("io.test.AllTypes", ALL_TYPES_SRC, "io.test.Level", LEVEL_SRC,
                    "io.test.Point", POINT_SRC));
            try (URLClassLoader cl = new URLClassLoader(
                    new URL[]{r.classesOutput.toUri().toURL()},
                    ProtobufStaticProcessorTest.class.getClassLoader())) {
                Class<?> type = cl.loadClass("io.test.AllTypes");
                Class<?> point = cl.loadClass("io.test.Point");
                Object p1 = point.getDeclaredConstructors()[0].newInstance(1);
                Object p2 = point.getDeclaredConstructors()[0].newInstance(2);
                Object high = cl.loadClass("io.test.Level").getEnumConstants()[1];
                Object value = type.getDeclaredConstructors()[0].newInstance(
                        -1L, -1, -1L, -2, -3L, -4, -5, 1.5f, -6L, -7L, 2.5, true, new byte[] {9}, high, p1, 0, 8L,
                        List.of(0.5, -0.5), List.of(true, false), List.of(p1, p2), List.of(3.5f), List.of(-9L, 9L));
                byte[] bytes = Protobuf.toByteArray(value);

                Parser<?> generated = (Parser<?>) cl.loadClass("io.test.AllTypes$$Parser")
                        .getDeclaredConstructor().newInstance();
                Object back = generated.parseFrom(bytes);
                assertArrayEquals(bytes, Protobuf.toByteArray(back));
                assertEquals(high, type.getMethod("level").invoke(back));
                assertEquals(0, type.getMethod("maybe").invoke(back));
                assertEquals(List.of(p1, p2), type.getMethod("points").invoke(back));

                Object empty = generated.parseFrom(new byte[0]);
                assertNull(type.getMethod("maybe").invoke(empty));
                assertNull(type.getMethod("boxed").invoke(empty));
                assertEquals(cl.loadClass("io.test.Level").getEnumConstants()[0], type.getMethod("level").invoke(empty));
            }
        }

        @Test
        void the_provider_answers_only_for_its_own_type(@TempDir Path tmp) throws Exception {
            CompileResult r = compile(tmp, Map.of("io.test.Person", PERSON_SRC));
            try (URLClassLoader cl = new URLClassLoader(
                    new URL[]{r.classesOutput.toUri().toURL()},
                    ProtobufStaticProcessorTest.class.getClassLoader())) {
                ParserProvider provider = (ParserProvider) cl.loadClass("io.test.Person$$ParserProvider")
                        .getDeclaredConstructor().newInstance();
                assertNotNull(provider.parserFor(cl.loadClass("io.test.Person")));
                assertNull(provider.parserFor(String.class));
            }
        }

        @Test
        void a_record_in_the_unnamed_package_gets_unqualified_classes(@TempDir Path tmp) throws Exception {
            String src = PERSON_SRC.replace("package io.test;", "").replace("io.test.Person", "Person");
            CompileResult r = compile(tmp, Map.of("Person", src));
            assertTrue(Files.exists(r.sourcesOutput.resolve("Person$$Parser.java")));
            String services = Files.readString(
                    r.classesOutput.resolve("META-INF/services/io.vidocq.champollion.protobuf.ParserProvider"));
            assertEquals("Person$$ParserProvider\n", services);
        }

        @Test
        void only_records_are_accepted(@TempDir Path tmp) throws Exception {
            String src = """
                    package io.test;

                    @io.vidocq.champollion.protobuf.ProtobufStatic
                    public class NotARecord {}
                    """;
            Attempt attempt = attempt(tmp, Map.of("io.test.NotARecord", src));
            assertFalse(attempt.ok());
            assertTrue(attempt.diagnostics().contains("@ProtobufStatic only supports records."), attempt.diagnostics());
        }

        @Test
        void the_record_must_be_a_protobuf_message(@TempDir Path tmp) throws Exception {
            String src = """
                    package io.test;

                    @io.vidocq.champollion.protobuf.ProtobufStatic
                    public record Plain(int x) {}
                    """;
            Attempt attempt = attempt(tmp, Map.of("io.test.Plain", src));
            assertFalse(attempt.ok());
            assertTrue(attempt.diagnostics().contains("must also be @ProtobufMessage"), attempt.diagnostics());
        }
    }

    private static final String SCORES_SRC = """
            package io.test;

            import io.vidocq.champollion.protobuf.FieldType;
            import io.vidocq.champollion.protobuf.Message;
            import io.vidocq.champollion.protobuf.ProtobufField;
            import io.vidocq.champollion.protobuf.ProtobufMessage;
            import io.vidocq.champollion.protobuf.ProtobufStatic;
            import java.util.List;

            @ProtobufStatic
            @ProtobufMessage("io.test.Scores")
            public record Scores(
                @ProtobufField(number = 1, type = FieldType.INT32) List<Integer> values
            ) implements Message {}
            """;

    // ============================================================ Compile harness

    record CompileResult(Path sourcesOutput, Path classesOutput) {}

    private static CompileResult compile(Path tmp, Map<String, String> sources) throws Exception {
        Attempt attempt = attempt(tmp, sources);
        if (!attempt.ok()) {
            StringBuilder sb = new StringBuilder("Compilation failed:\n").append(attempt.diagnostics());
            try (Stream<Path> walk = Files.walk(attempt.result().sourcesOutput())) {
                for (Path p : walk.filter(Files::isRegularFile).toList()) {
                    sb.append("\n--- ").append(attempt.result().sourcesOutput().relativize(p)).append(" ---\n")
                            .append(Files.readString(p));
                }
            }
            fail(sb.toString());
        }
        return attempt.result();
    }

    record Attempt(boolean ok, String diagnostics, CompileResult result) {}

    private static Attempt attempt(Path tmp, Map<String, String> sources) throws Exception {
        Path src = tmp.resolve("src");
        Path classes = tmp.resolve("classes");
        Path gen = tmp.resolve("sources-gen");
        Files.createDirectories(src);
        Files.createDirectories(classes);
        Files.createDirectories(gen);

        // Écrit les sources d'entrée sur disque
        List<JavaFileObject> units = new ArrayList<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            Path file = src.resolve(e.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, e.getValue());
            units.add(new InMemorySource(e.getKey(), e.getValue()));
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) fail("No JDK compiler available");

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null);
        fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
        fm.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(gen));

        List<String> opts = List.of(
                "--add-modules", "io.vidocq.champollion.protobuf",
                "--module-path", System.getProperty("jdk.module.path", ""));

        JavaCompiler.CompilationTask task = compiler.getTask(null, fm, diagnostics, opts, null, units);
        task.setProcessors(List.of(new ProtobufStaticProcessor()));
        boolean ok = task.call();
        StringBuilder sb = new StringBuilder();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            sb.append("  ").append(d.getKind()).append(": ")
                    .append(d.getMessage(null)).append('\n');
        }
        return new Attempt(ok, sb.toString(), new CompileResult(gen, classes));
    }

    static final class InMemorySource extends SimpleJavaFileObject {
        private final String code;
        InMemorySource(String fqn, String code) {
            super(URI.create("string:///" + fqn.replace('.', '/') + ".java"), Kind.SOURCE);
            this.code = code;
        }
        @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return code; }
    }
}
