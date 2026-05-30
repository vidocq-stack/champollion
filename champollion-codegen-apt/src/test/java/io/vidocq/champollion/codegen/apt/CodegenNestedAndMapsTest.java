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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("M5.5 — APT codegen Map<String,X> + nested @JsonbStatic records")
class CodegenNestedAndMapsTest {

    @Test
    void map_string_to_integer_roundtrip(@TempDir Path tmp) throws Exception {
        String src = """
                package generated.m55;
                import io.vidocq.champollion.jsonb.spi.JsonbStatic;
                import java.util.Map;
                @JsonbStatic
                public record Counters(String label, Map<String, Integer> values) {}
                """;
        Setup s = setup(tmp, "generated.m55.Counters", src);

        var values = new LinkedHashMap<String, Integer>();
        values.put("a", 1);
        values.put("b", 2);
        Object inst = s.targetClass.getDeclaredConstructor(String.class, Map.class)
                .newInstance("scores", values);

        var sw = new StringWriter();
        try (JsonGenerator g = Json.createGenerator(sw)) {
            s.staticBinding.write(g, inst);
        }
        assertEquals("{\"label\":\"scores\",\"values\":{\"a\":1,\"b\":2}}", sw.toString());

        try (JsonParser p = Json.createParser(new StringReader(sw.toString()))) {
            Object back = s.staticBinding.read(p);
            assertEquals(inst, back);
        }
    }

    @Test
    void map_string_to_string_static_equals_runtime(@TempDir Path tmp) throws Exception {
        String src = """
                package generated.m55;
                import io.vidocq.champollion.jsonb.spi.JsonbStatic;
                import java.util.Map;
                @JsonbStatic
                public record Headers(Map<String, String> entries) {}
                """;
        Setup s = setup(tmp, "generated.m55.Headers", src);
        var entries = new LinkedHashMap<String, String>();
        entries.put("Content-Type", "application/json");
        entries.put("Accept", "*/*");
        Object inst = s.targetClass.getDeclaredConstructor(Map.class).newInstance(entries);
        assertWriteEquals(s, inst);
    }

    @Test
    void nested_static_record_field(@TempDir Path tmp) throws Exception {
        // Two records in the same APT round — A contains a B annotated @JsonbStatic.
        // APT must generate A$$Binding that calls new B$$Binding() for delegation.
        String src = """
                package generated.m55;
                import io.vidocq.champollion.jsonb.spi.JsonbStatic;
                @JsonbStatic
                public record Inner(int x, int y) {}
                """;
        String src2 = """
                package generated.m55;
                import io.vidocq.champollion.jsonb.spi.JsonbStatic;
                @JsonbStatic
                public record Outer(String name, Inner pos) {}
                """;
        ClassLoader cl = compileBoth(tmp,
                "generated.m55.Inner", src,
                "generated.m55.Outer", src2);

        Class<?> innerClass = cl.loadClass("generated.m55.Inner");
        Class<?> outerClass = cl.loadClass("generated.m55.Outer");
        Object inner = innerClass.getDeclaredConstructor(int.class, int.class).newInstance(3, 4);
        Object outer = outerClass.getDeclaredConstructor(String.class, innerClass).newInstance("Alice", inner);

        @SuppressWarnings("unchecked")
        JsonbBinding<Object> binding = (JsonbBinding<Object>)
                cl.loadClass("generated.m55.Outer$$Binding").getDeclaredConstructor().newInstance();

        var sw = new StringWriter();
        try (JsonGenerator g = Json.createGenerator(sw)) {
            binding.write(g, outer);
        }
        assertEquals("{\"name\":\"Alice\",\"pos\":{\"x\":3,\"y\":4}}", sw.toString());

        try (JsonParser p = Json.createParser(new StringReader(sw.toString()))) {
            Object back = binding.read(p);
            assertEquals(outer, back);
        }
    }

    @Test
    void list_of_nested_static_records(@TempDir Path tmp) throws Exception {
        String src = """
                package generated.m55;
                import io.vidocq.champollion.jsonb.spi.JsonbStatic;
                @JsonbStatic
                public record Point(int x, int y) {}
                """;
        String src2 = """
                package generated.m55;
                import io.vidocq.champollion.jsonb.spi.JsonbStatic;
                import java.util.List;
                @JsonbStatic
                public record Path2D(String name, List<Point> points) {}
                """;
        ClassLoader cl = compileBoth(tmp,
                "generated.m55.Point", src,
                "generated.m55.Path2D", src2);

        Class<?> pointClass = cl.loadClass("generated.m55.Point");
        Class<?> pathClass = cl.loadClass("generated.m55.Path2D");
        Object p1 = pointClass.getDeclaredConstructor(int.class, int.class).newInstance(0, 0);
        Object p2 = pointClass.getDeclaredConstructor(int.class, int.class).newInstance(1, 1);
        Object path = pathClass.getDeclaredConstructor(String.class, List.class).newInstance("L", List.of(p1, p2));

        @SuppressWarnings("unchecked")
        JsonbBinding<Object> binding = (JsonbBinding<Object>)
                cl.loadClass("generated.m55.Path2D$$Binding").getDeclaredConstructor().newInstance();

        var sw = new StringWriter();
        try (JsonGenerator g = Json.createGenerator(sw)) {
            binding.write(g, path);
        }
        assertEquals("{\"name\":\"L\",\"points\":[{\"x\":0,\"y\":0},{\"x\":1,\"y\":1}]}", sw.toString());

        try (JsonParser p = Json.createParser(new StringReader(sw.toString()))) {
            Object back = binding.read(p);
            assertEquals(path, back);
        }
    }

    // ============================================================
    // Plumbing
    // ============================================================

    private record Setup(Class<?> targetClass, JsonbBinding<Object> staticBinding, Jsonb runtimeJsonb) {}

    @SuppressWarnings("unchecked")
    private Setup setup(Path tmp, String fqn, String source) throws Exception {
        ClassLoader cl = compile(tmp, List.of(new Source(fqn, source)));
        Class<?> target = cl.loadClass(fqn);
        var binding = (JsonbBinding<Object>) cl.loadClass(fqn + "$$Binding")
                .getDeclaredConstructor().newInstance();
        return new Setup(target, binding, JsonbBuilder.create());
    }

    private ClassLoader compileBoth(Path tmp, String fqn1, String src1, String fqn2, String src2) throws IOException {
        return compile(tmp, List.of(new Source(fqn1, src1), new Source(fqn2, src2)));
    }

    private void assertWriteEquals(Setup s, Object instance) {
        var sw1 = new StringWriter();
        try (JsonGenerator g = Json.createGenerator(sw1)) { s.staticBinding.write(g, instance); }
        String runtimeJson = s.runtimeJsonb.toJson(instance, (Type) s.targetClass);
        assertEquals(runtimeJson, sw1.toString());
    }

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
