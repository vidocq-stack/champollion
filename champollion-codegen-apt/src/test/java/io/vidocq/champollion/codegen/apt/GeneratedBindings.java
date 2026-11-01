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

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sources compiled with the processor, for the differential tests: the bindings generated for them, and a runtime
 * {@link Jsonb} that maps the same types by introspection (its loader does not see the generated services file).
 */
final class GeneratedBindings implements AutoCloseable {

    /** The JAX-RS annotations the processor looks for, as sources: Champollion does not depend on the API. */
    static final Map<String, String> JAXRS = Map.of(
            "jakarta.ws.rs.HttpMethod", """
                    package jakarta.ws.rs;
                    import java.lang.annotation.*;
                    @Target(ElementType.ANNOTATION_TYPE) @Retention(RetentionPolicy.RUNTIME)
                    public @interface HttpMethod { String value(); }
                    """,
            "jakarta.ws.rs.Path", """
                    package jakarta.ws.rs;
                    import java.lang.annotation.*;
                    @Target({ElementType.TYPE, ElementType.METHOD}) @Retention(RetentionPolicy.RUNTIME)
                    public @interface Path { String value(); }
                    """,
            "jakarta.ws.rs.GET", """
                    package jakarta.ws.rs;
                    import java.lang.annotation.*;
                    @Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME) @HttpMethod("GET")
                    public @interface GET {}
                    """,
            "jakarta.ws.rs.POST", """
                    package jakarta.ws.rs;
                    import java.lang.annotation.*;
                    @Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME) @HttpMethod("POST")
                    public @interface POST {}
                    """,
            "jakarta.ws.rs.PathParam", """
                    package jakarta.ws.rs;
                    import java.lang.annotation.*;
                    @Target(ElementType.PARAMETER) @Retention(RetentionPolicy.RUNTIME)
                    public @interface PathParam { String value(); }
                    """);

    private final Path out;
    private final URLClassLoader loader;
    private final List<String> notes;
    private final Jsonb runtime;

    private GeneratedBindings(Path out, URLClassLoader loader, List<String> notes, Jsonb runtime) {
        this.out = out;
        this.loader = loader;
        this.notes = notes;
        this.runtime = runtime;
    }

    /** Compiles the JAX-RS annotations and {@code sources} (by qualified name, {@code package-info} included). */
    static GeneratedBindings compile(Path tmp, Map<String, String> sources) throws Exception {
        Path out = tmp.resolve("out");
        Files.createDirectories(out);
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var fm = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
        List<JavaFileObject> files = new ArrayList<>();
        Map<String, String> all = new LinkedHashMap<>(JAXRS);
        all.putAll(sources);
        all.forEach((fqn, source) -> files.add(new SimpleJavaFileObject(
                URI.create("string:///" + fqn.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension),
                JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return source; }
        }));
        String cp = System.getProperty("java.class.path");
        String mp = System.getProperty("jdk.module.path", "");
        boolean ok = compiler.getTask(null, fm, diagnostics,
                List.of("-d", out.toString(), "--release", "25",
                        "-cp", mp.isEmpty() ? cp : cp + java.io.File.pathSeparator + mp,
                        "-processor", JsonbStaticProcessor.class.getName()),
                null, files).call();
        fm.close();
        List<String> notes = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.NOTE) notes.add(d.getMessage(null));
        }
        assertTrue(ok, () -> "compilation must succeed: " + diagnostics.getDiagnostics());
        var loader = new URLClassLoader(new URL[] {out.toUri().toURL()}, GeneratedBindings.class.getClassLoader());
        return new GeneratedBindings(out, loader, notes, JsonbBuilder.create());
    }

    @Override
    public void close() throws Exception {
        runtime.close();
        loader.close();
    }

    Path out() {
        return out;
    }

    List<String> notes() {
        return notes;
    }

    Jsonb runtime() {
        return runtime;
    }

    Class<?> type(String name) throws ClassNotFoundException {
        return loader.loadClass(name);
    }

    @SuppressWarnings("unchecked")
    JsonbBinding<Object> binding(String type) throws Exception {
        return (JsonbBinding<Object>) loader.loadClass(type + "$$Binding").getDeclaredConstructor().newInstance();
    }

    boolean hasBinding(String type) {
        return Files.exists(out.resolve(type.replace('.', '/') + "$$Binding.class"));
    }

    /** A note of the processor about {@code type} that contains {@code text}. */
    boolean noted(String type, String text) {
        return notes.stream().anyMatch(n -> n.contains(type) && n.contains(text));
    }

    Object newInstance(String type, Object... args) throws Exception {
        for (var c : loader.loadClass(type).getDeclaredConstructors()) {
            if (c.getParameterCount() == args.length) return c.newInstance(args);
        }
        throw new AssertionError("no constructor");
    }

    Object enumConstant(String type, String name) throws Exception {
        for (Object c : loader.loadClass(type).getEnumConstants()) {
            if (((Enum<?>) c).name().equals(name)) return c;
        }
        throw new AssertionError(name);
    }

    String writeStatic(String type, Object value) throws Exception {
        var sw = new StringWriter();
        try (JsonGenerator g = Json.createGenerator(sw)) {
            binding(type).write(g, value);
        }
        return sw.toString();
    }

    Object readStatic(String type, String json) throws Exception {
        try (JsonParser p = Json.createParser(new StringReader(json))) {
            return binding(type).read(p);
        }
    }

    /**
     * A {@code Jsonb} that uses the generated bindings, as an application's does: it finds their services file
     * through the context class loader.
     */
    Jsonb jsonbWithBindings() {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return JsonbBuilder.create();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /** What the runtime writes for {@code value} as {@code type}. */
    String writeRuntime(String type, Object value) throws Exception {
        return runtime.toJson(value, (Type) type(type));
    }

    /** What the runtime reads from {@code json} as {@code type}. */
    Object readRuntime(String type, String json) throws Exception {
        return runtime.fromJson(json, type(type));
    }
}
