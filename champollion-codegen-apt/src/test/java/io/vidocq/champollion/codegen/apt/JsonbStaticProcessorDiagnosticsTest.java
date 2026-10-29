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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the processor does with the records it cannot bind, the names it must escape, and the services file of an
 * incremental build: a compile error with a message, never an exception out of javac, nor a source that does not
 * compile, nor a stale service entry.
 */
@DisplayName("JsonbStaticProcessor — diagnostics and services file")
class JsonbStaticProcessorDiagnosticsTest {

    private static final String SERVICES = "META-INF/services/io.vidocq.champollion.jsonb.spi.JsonbBinding";

    private record Compilation(boolean success, List<String> errors) {}

    private static Compilation compile(Path out, String... fqnAndSources) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Files.createDirectories(out);
        String cp = System.getProperty("java.class.path") + File.pathSeparator + out
                + File.pathSeparator + System.getProperty("jdk.module.path", "");
        List<JavaFileObject> sources = new ArrayList<>();
        for (int i = 0; i < fqnAndSources.length; i += 2) {
            String fqn = fqnAndSources[i];
            String content = fqnAndSources[i + 1];
            sources.add(new SimpleJavaFileObject(URI.create("string:///" + fqn.replace('.', '/') + ".java"),
                    JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return content; }
            });
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (var files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            boolean ok = compiler.getTask(null, files, diagnostics,
                    List.of("-d", out.toString(), "--release", "25", "-cp", cp,
                            "-processor", JsonbStaticProcessor.class.getName()),
                    null, sources).call();
            List<String> errors = diagnostics.getDiagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .map(d -> d.getMessage(Locale.ROOT)).toList();
            return new Compilation(ok, errors);
        }
    }

    @Test
    void a_nested_record_is_a_compile_error(@TempDir Path tmp) throws IOException {
        Compilation result = compile(tmp.resolve("out"), "demo.Outer", """
                package demo;
                import io.vidocq.champollion.jsonb.spi.JsonbStatic;
                public class Outer {
                    @JsonbStatic
                    public record Inner(int x) {}
                }
                """);

        assertFalse(result.success());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("top-level")), result.errors()::toString);
    }

    @Test
    void a_property_name_with_a_quote_is_escaped(@TempDir Path tmp) throws IOException {
        Compilation result = compile(tmp.resolve("out"), "demo.Quoted", """
                package demo;
                import io.vidocq.champollion.jsonb.spi.JsonbStatic;
                import jakarta.json.bind.annotation.JsonbProperty;
                @JsonbStatic
                public record Quoted(@JsonbProperty("a\\"b") String value) {}
                """);

        assertTrue(result.success(), result.errors()::toString);
    }

    @Test
    void two_components_with_the_same_json_name_are_a_compile_error(@TempDir Path tmp) throws IOException {
        Compilation result = compile(tmp.resolve("out"), "demo.Twice", """
                package demo;
                import io.vidocq.champollion.jsonb.spi.JsonbStatic;
                import jakarta.json.bind.annotation.JsonbProperty;
                @JsonbStatic
                public record Twice(@JsonbProperty("x") String a, @JsonbProperty("x") String b) {}
                """);

        assertFalse(result.success());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("\"x\"")), result.errors()::toString);
    }

    /** An incremental build keeps the entries of the bindings it did not regenerate, but not those of a record gone static-less. */
    @Test
    void an_incremental_build_drops_the_entry_of_a_record_no_longer_static(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("out");
        assertTrue(compile(out, "demo.Kept", """
                package demo;
                @io.vidocq.champollion.jsonb.spi.JsonbStatic
                public record Kept(int x) {}
                """, "demo.Dropped", """
                package demo;
                @io.vidocq.champollion.jsonb.spi.JsonbStatic
                public record Dropped(int x) {}
                """).success());

        assertTrue(compile(out, "demo.Dropped", """
                package demo;
                public record Dropped(int x) {}
                """, "demo.Added", """
                package demo;
                @io.vidocq.champollion.jsonb.spi.JsonbStatic
                public record Added(int x) {}
                """).success());

        List<String> entries = Files.readAllLines(out.resolve(SERVICES)).stream()
                .filter(l -> !l.isBlank() && !l.startsWith("#")).sorted().toList();
        assertEquals(List.of("demo.Added$$Binding", "demo.Kept$$Binding"), entries);
    }
}
