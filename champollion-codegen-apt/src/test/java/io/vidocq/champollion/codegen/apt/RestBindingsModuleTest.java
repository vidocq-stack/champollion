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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** In a named module, the bindings compile against {@code io.vidocq.champollion.jsonb}: the module must read it. */
class RestBindingsModuleTest {

    private static final Map<String, String> SOURCES = Map.of(
            "jakarta/ws/rs/HttpMethod.java", """
                    package jakarta.ws.rs;
                    import java.lang.annotation.*;
                    @Target(ElementType.ANNOTATION_TYPE) @Retention(RetentionPolicy.RUNTIME)
                    public @interface HttpMethod { String value(); }
                    """,
            "jakarta/ws/rs/Path.java", """
                    package jakarta.ws.rs;
                    import java.lang.annotation.*;
                    @Target({ElementType.TYPE, ElementType.METHOD}) @Retention(RetentionPolicy.RUNTIME)
                    public @interface Path { String value(); }
                    """,
            "jakarta/ws/rs/GET.java", """
                    package jakarta.ws.rs;
                    import java.lang.annotation.*;
                    @Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME) @HttpMethod("GET")
                    public @interface GET {}
                    """,
            "shop/BookResource.java", """
                    package shop;
                    @jakarta.ws.rs.Path("/books")
                    public class BookResource {
                        @jakarta.ws.rs.GET public Book one() { return null; }
                    }
                    """,
            "shop/Book.java", """
                    package shop;
                    public record Book(String title, java.time.LocalDate published) {}
                    """);

    private record Result(boolean ok, List<String> notes, Path out) {}

    private static Result compile(Path tmp, String moduleInfo) throws Exception {
        Path src = tmp.resolve("src");
        Path out = tmp.resolve("out");
        Files.createDirectories(out);
        List<File> files = new ArrayList<>();
        for (var e : SOURCES.entrySet()) {
            Path f = src.resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue());
            files.add(f.toFile());
        }
        Path mi = src.resolve("module-info.java");
        Files.writeString(mi, moduleInfo);
        files.add(mi.toFile());
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var fm = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            String cp = System.getProperty("java.class.path");
            String mp = System.getProperty("jdk.module.path", "");
            boolean ok = compiler.getTask(null, fm, diagnostics,
                    List.of("-d", out.toString(), "--release", "25", "--module-path", mp.isEmpty() ? cp : mp,
                            "-processorpath", mp.isEmpty() ? cp : cp + File.pathSeparator + mp,
                            "-processor", JsonbStaticProcessor.class.getName()),
                    null, fm.getJavaFileObjectsFromFiles(files)).call();
            List<String> notes = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.NOTE) notes.add(d.getMessage(null));
            }
            assertTrue(ok, () -> "compilation must succeed: " + diagnostics.getDiagnostics());
            return new Result(ok, notes, out);
        }
    }

    @Test
    void aModuleThatReadsChampollionGetsTheBindings(@TempDir Path tmp) throws Exception {
        Result r = compile(tmp, "module shop { requires jakarta.json.bind; requires io.vidocq.champollion.jsonb; }");
        assertTrue(Files.exists(r.out().resolve("shop/Book$$Binding.class")));
    }

    @Test
    void aModuleThatDoesNotIsLeftToTheRuntimeAndCompiles(@TempDir Path tmp) throws Exception {
        Result r = compile(tmp, "module shop { requires jakarta.json.bind; }");
        assertFalse(Files.exists(r.out().resolve("shop/Book$$Binding.class")));
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("does not read io.vidocq.champollion.jsonb")),
                r.notes()::toString);
    }
}
