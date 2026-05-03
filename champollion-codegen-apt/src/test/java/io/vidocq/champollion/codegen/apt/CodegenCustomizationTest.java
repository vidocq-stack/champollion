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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("M4.4b — APT customization (@JsonbProperty + @JsonbTransient)")
class CodegenCustomizationTest {

    @Test
    void jsonb_property_renames_member_in_static_binding(@TempDir Path tmp) throws Exception {
        String src = """
                package generated.cust;
                import io.vidocq.champollion.jsonb.spi.JsonbStatic;
                import jakarta.json.bind.annotation.JsonbProperty;
                @JsonbStatic
                public record Snake(@JsonbProperty("user_name") String userName,
                                    @JsonbProperty("age_years") int age) {}
                """;
        Setup s = setup(tmp, "generated.cust.Snake", src);

        Object inst = s.targetClass.getDeclaredConstructor(String.class, int.class)
                .newInstance("alice", 30);

        var sw = new StringWriter();
        try (JsonGenerator g = Json.createGenerator(sw)) {
            s.staticBinding.write(g, inst);
        }
        assertEquals("{\"user_name\":\"alice\",\"age_years\":30}", sw.toString());

        try (JsonParser p = Json.createParser(new StringReader(sw.toString()))) {
            Object back = s.staticBinding.read(p);
            assertEquals(inst, back);
        }
    }

    @Test
    void jsonb_transient_excludes_component_from_write_and_read(@TempDir Path tmp) throws Exception {
        String src = """
                package generated.cust;
                import io.vidocq.champollion.jsonb.spi.JsonbStatic;
                import jakarta.json.bind.annotation.JsonbTransient;
                @JsonbStatic
                public record User(String name, @JsonbTransient String secret) {}
                """;
        Setup s = setup(tmp, "generated.cust.User", src);

        Object u = s.targetClass.getDeclaredConstructor(String.class, String.class)
                .newInstance("alice", "hidden");

        // write : secret absent
        var sw = new StringWriter();
        try (JsonGenerator g = Json.createGenerator(sw)) {
            s.staticBinding.write(g, u);
        }
        assertEquals("{\"name\":\"alice\"}", sw.toString());

        // read : secret reste null même s'il est dans le JSON
        try (JsonParser p = Json.createParser(new StringReader("{\"name\":\"bob\",\"secret\":\"leaked\"}"))) {
            Object back = s.staticBinding.read(p);
            assertEquals("bob", s.targetClass.getMethod("name").invoke(back));
            assertNull(s.targetClass.getMethod("secret").invoke(back));
        }
    }

    @Test
    void mixed_renamed_and_transient(@TempDir Path tmp) throws Exception {
        String src = """
                package generated.cust;
                import io.vidocq.champollion.jsonb.spi.JsonbStatic;
                import jakarta.json.bind.annotation.JsonbProperty;
                import jakarta.json.bind.annotation.JsonbTransient;
                @JsonbStatic
                public record Account(
                        @JsonbProperty("user_id") String id,
                        String name,
                        @JsonbTransient String password) {}
                """;
        Setup s = setup(tmp, "generated.cust.Account", src);

        Object inst = s.targetClass.getDeclaredConstructor(String.class, String.class, String.class)
                .newInstance("u1", "Alice", "s3cret");
        var sw = new StringWriter();
        try (JsonGenerator g = Json.createGenerator(sw)) {
            s.staticBinding.write(g, inst);
        }
        assertEquals("{\"user_id\":\"u1\",\"name\":\"Alice\"}", sw.toString());

        // Differential : runtime doit produire le même JSON
        String runtimeJson = JsonbBuilder.create().toJson(inst, (Type) s.targetClass);
        assertEquals(runtimeJson, sw.toString());
    }

    // ============================================================
    // Plumbing
    // ============================================================

    private record Setup(Class<?> targetClass, JsonbBinding<Object> staticBinding) {}

    @SuppressWarnings("unchecked")
    private Setup setup(Path tmp, String fqn, String source) throws Exception {
        ClassLoader cl = compile(tmp, fqn, source);
        Class<?> target = cl.loadClass(fqn);
        var binding = (JsonbBinding<Object>) cl.loadClass(fqn + "$$Binding")
                .getDeclaredConstructor().newInstance();
        return new Setup(target, binding);
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
