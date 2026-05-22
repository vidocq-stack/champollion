package io.vidocq.champollion.protobuf.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.vidocq.champollion.protobuf.Protobuf;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
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
    }

    // ============================================================ Compile harness

    record CompileResult(Path sourcesOutput, Path classesOutput) {}

    private static CompileResult compile(Path tmp, Map<String, String> sources) throws Exception {
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
        if (!ok) {
            StringBuilder sb = new StringBuilder("Compilation failed:\n");
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                sb.append("  ").append(d.getKind()).append(": ")
                        .append(d.getMessage(null)).append('\n');
            }
            try (Stream<Path> walk = Files.walk(gen)) {
                for (Path p : walk.filter(Files::isRegularFile).toList()) {
                    sb.append("\n--- ").append(gen.relativize(p)).append(" ---\n")
                            .append(Files.readString(p));
                }
            }
            fail(sb.toString());
        }
        return new CompileResult(gen, classes);
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
