package io.vidocq.champollion.codegen.apt;

import io.vidocq.champollion.jsonb.spi.JsonbBinding;
import jakarta.json.Json;
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

@DisplayName("M5.6 — APT codegen enums")
class CodegenEnumsTest {

    @Test
    void enum_field_serializes_as_name(@TempDir Path tmp) throws Exception {
        String enumSrc = """
                package generated.enums;
                public enum Status { ACTIVE, INACTIVE, PENDING }
                """;
        String recSrc = """
                package generated.enums;
                import io.vidocq.champollion.jsonb.spi.JsonbStatic;
                @JsonbStatic
                public record Account(String id, Status status) {}
                """;
        ClassLoader cl = compile(tmp, List.of(
                new Source("generated.enums.Status", enumSrc),
                new Source("generated.enums.Account", recSrc)
        ));

        Class<?> statusClass = cl.loadClass("generated.enums.Status");
        Class<?> accClass = cl.loadClass("generated.enums.Account");
        Object active = statusClass.getEnumConstants()[0]; // ACTIVE
        Object acc = accClass.getDeclaredConstructor(String.class, statusClass).newInstance("U1", active);

        @SuppressWarnings("unchecked")
        JsonbBinding<Object> binding = (JsonbBinding<Object>)
                cl.loadClass("generated.enums.Account$$Binding").getDeclaredConstructor().newInstance();

        var sw = new StringWriter();
        try (JsonGenerator g = Json.createGenerator(sw)) {
            binding.write(g, acc);
        }
        assertEquals("{\"id\":\"U1\",\"status\":\"ACTIVE\"}", sw.toString());

        try (JsonParser p = Json.createParser(new StringReader(sw.toString()))) {
            Object back = binding.read(p);
            assertEquals(acc, back);
        }
    }

    @Test
    void list_of_enums(@TempDir Path tmp) throws Exception {
        String enumSrc = """
                package generated.enums;
                public enum Color { RED, GREEN, BLUE }
                """;
        String recSrc = """
                package generated.enums;
                import io.vidocq.champollion.jsonb.spi.JsonbStatic;
                import java.util.List;
                @JsonbStatic
                public record Palette(List<Color> colors) {}
                """;
        ClassLoader cl = compile(tmp, List.of(
                new Source("generated.enums.Color", enumSrc),
                new Source("generated.enums.Palette", recSrc)
        ));

        Class<?> colorClass = cl.loadClass("generated.enums.Color");
        Class<?> palClass = cl.loadClass("generated.enums.Palette");
        Object red = colorClass.getEnumConstants()[0];
        Object blue = colorClass.getEnumConstants()[2];
        Object pal = palClass.getDeclaredConstructor(List.class).newInstance(List.of(red, blue));

        @SuppressWarnings("unchecked")
        JsonbBinding<Object> binding = (JsonbBinding<Object>)
                cl.loadClass("generated.enums.Palette$$Binding").getDeclaredConstructor().newInstance();

        var sw = new StringWriter();
        try (JsonGenerator g = Json.createGenerator(sw)) {
            binding.write(g, pal);
        }
        assertEquals("{\"colors\":[\"RED\",\"BLUE\"]}", sw.toString());

        try (JsonParser p = Json.createParser(new StringReader(sw.toString()))) {
            Object back = binding.read(p);
            assertEquals(pal, back);
        }
    }

    @Test
    void enum_static_equals_runtime(@TempDir Path tmp) throws Exception {
        String enumSrc = """
                package generated.enums;
                public enum Priority { LOW, MEDIUM, HIGH }
                """;
        String recSrc = """
                package generated.enums;
                import io.vidocq.champollion.jsonb.spi.JsonbStatic;
                @JsonbStatic
                public record Task(String name, Priority priority) {}
                """;
        ClassLoader cl = compile(tmp, List.of(
                new Source("generated.enums.Priority", enumSrc),
                new Source("generated.enums.Task", recSrc)
        ));

        Class<?> prioClass = cl.loadClass("generated.enums.Priority");
        Class<?> taskClass = cl.loadClass("generated.enums.Task");
        Object high = prioClass.getEnumConstants()[2];
        Object task = taskClass.getDeclaredConstructor(String.class, prioClass).newInstance("ship", high);

        @SuppressWarnings("unchecked")
        JsonbBinding<Object> binding = (JsonbBinding<Object>)
                cl.loadClass("generated.enums.Task$$Binding").getDeclaredConstructor().newInstance();

        var sw = new StringWriter();
        try (JsonGenerator g = Json.createGenerator(sw)) { binding.write(g, task); }
        String runtimeJson = JsonbBuilder.create().toJson(task, (Type) taskClass);
        assertEquals(runtimeJson, sw.toString());
    }

    // ============================================================
    // Plumbing
    // ============================================================

    private record Source(String fqn, String content) {}

    private ClassLoader compile(Path tmp, List<Source> sources) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Path out = tmp.resolve("out");
        Files.createDirectories(out);
        var fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
        var srcs = sources.stream().<JavaFileObject>map(s -> new SimpleJavaFileObject(
                URI.create("string:///" + s.fqn.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension),
                JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return s.content; }
        }).toList();
        String cp = System.getProperty("java.class.path");
        String mp = System.getProperty("jdk.module.path", "");
        String fullCp = mp.isEmpty() ? cp : cp + java.io.File.pathSeparator + mp;
        var task = compiler.getTask(null, fm, null,
                List.of("-d", out.toString(), "--release", "25",
                        "-cp", fullCp,
                        "-processor", JsonbStaticProcessor.class.getName()),
                null, srcs);
        boolean ok = task.call();
        fm.close();
        assertTrue(ok, "compilation must succeed");
        return new URLClassLoader(new URL[] { out.toUri().toURL() }, getClass().getClassLoader());
    }
}
