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

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.Instruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * M5.12 — Validation AOT : scrute le bytecode du binding généré et vérifie
 * qu'aucune instruction n'utilise une API réflexive interdite par GraalVM
 * native-image (sans configuration manuelle).
 *
 * <p>Le test n'exige pas GraalVM installé : il analyse directement les
 * {@code .class} via {@link ClassFile}. Si un binding n'utilise aucune des API
 * listées ci-dessous, il est par construction compilable en native-image sans
 * fichier de configuration {@code reflect-config.json}.</p>
 *
 * <p>API interdites :</p>
 * <ul>
 *   <li>tout type dans {@code java/lang/reflect/}</li>
 *   <li>{@code java/lang/invoke/MethodHandle*}</li>
 *   <li>{@code java.lang.Class} : {@code forName}, {@code getDeclaredMethod*},
 *       {@code getDeclaredField*}, {@code getMethods}, {@code getFields},
 *       {@code getDeclaredConstructor*}, {@code getRecordComponents}</li>
 * </ul>
 */
@DisplayName("M5.12 — AOT readiness (no reflection in generated binding)")
class AotReadinessTest {

    private static final Set<String> FORBIDDEN_OWNER_PREFIXES = Set.of(
            "java/lang/reflect/",
            "java/lang/invoke/MethodHandle",
            "java/lang/invoke/MethodHandles",
            "java/lang/invoke/VarHandle"
    );

    private static final Set<String> FORBIDDEN_CLASS_METHODS = Set.of(
            "forName",
            "getDeclaredMethod", "getDeclaredMethods",
            "getDeclaredField", "getDeclaredFields",
            "getMethod", "getMethods",
            "getField", "getFields",
            "getDeclaredConstructor", "getDeclaredConstructors",
            "getConstructor", "getConstructors",
            "getRecordComponents",
            "getEnumConstants"
    );

    @Test
    void simple_record_binding_is_reflection_free(@TempDir Path tmp) throws Exception {
        compileAndCheck(tmp, "generated.aot.Coord", """
                package generated.aot;
                import io.vidocq.champollion.jsonb.spi.JsonbStatic;
                @JsonbStatic
                public record Coord(int x, int y) {}
                """);
    }

    @Test
    void mixed_record_with_string_and_enum_is_reflection_free(@TempDir Path tmp) throws Exception {
        compileAndCheck(tmp, "generated.aot.Account",
                """
                package generated.aot;
                public enum Status { ACTIVE, INACTIVE }
                """,
                """
                package generated.aot;
                import io.vidocq.champollion.jsonb.spi.JsonbStatic;
                @JsonbStatic
                public record Account(String id, int balance, Status status) {}
                """);
    }

    @Test
    void containers_record_is_reflection_free(@TempDir Path tmp) throws Exception {
        compileAndCheck(tmp, "generated.aot.Bag",
                """
                package generated.aot;
                import io.vidocq.champollion.jsonb.spi.JsonbStatic;
                import java.util.List;
                import java.util.Map;
                import java.util.Optional;
                @JsonbStatic
                public record Bag(
                    String name,
                    List<String> tags,
                    Map<String, Integer> counts,
                    Optional<String> note,
                    int[] arr
                ) {}
                """);
    }

    @Test
    void nested_static_record_is_reflection_free(@TempDir Path tmp) throws Exception {
        compileAndCheck(tmp, "generated.aot.Outer",
                """
                package generated.aot;
                import io.vidocq.champollion.jsonb.spi.JsonbStatic;
                @JsonbStatic
                public record Inner(int x, int y) {}
                """,
                """
                package generated.aot;
                import io.vidocq.champollion.jsonb.spi.JsonbStatic;
                import java.util.List;
                @JsonbStatic
                public record Outer(String name, Inner pos, List<Inner> path) {}
                """);
    }

    // ============================================================
    // Plumbing
    // ============================================================

    private void compileAndCheck(Path tmp, String mainFqn, String... sources) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Path out = tmp.resolve("out");
        Files.createDirectories(out);
        var fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);

        var srcs = new ArrayList<JavaFileObject>();
        for (int i = 0; i < sources.length; i++) {
            String src = sources[i];
            // déduit FQN : pour le main, on utilise mainFqn ; pour les autres, on extrait via package + premier type
            String fqn = (i == sources.length - 1) ? mainFqn : extractFqn(src);
            srcs.add(new SimpleJavaFileObject(
                    URI.create("string:///" + fqn.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension),
                    JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignore) { return src; }
            });
        }

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

        // Trouve le binding du type principal
        Path bindingClass = out.resolve(mainFqn.replace('.', '/') + "$$Binding.class");
        assertTrue(Files.exists(bindingClass), "Binding class file must exist: " + bindingClass);

        byte[] bytes = Files.readAllBytes(bindingClass);
        var cm = ClassFile.of().parse(bytes);

        var violations = new ArrayList<String>();
        for (var method : cm.methods()) {
            method.code().ifPresent(code -> {
                for (var element : code) {
                    if (element instanceof Instruction instr && instr instanceof InvokeInstruction inv) {
                        String owner = inv.owner().asInternalName();
                        String name = inv.name().stringValue();
                        if (FORBIDDEN_OWNER_PREFIXES.stream().anyMatch(owner::startsWith)) {
                            violations.add(method.methodName().stringValue() + " uses " + owner + "." + name);
                        }
                        if ("java/lang/Class".equals(owner) && FORBIDDEN_CLASS_METHODS.contains(name)) {
                            violations.add(method.methodName().stringValue() + " uses Class." + name);
                        }
                    }
                }
            });
        }

        if (!violations.isEmpty()) {
            fail("AOT readiness violations in " + mainFqn + "$$Binding:\n  - "
                    + String.join("\n  - ", violations));
        }
    }

    /** Extrait le FQN du premier type déclaré dans un source Java. */
    private static String extractFqn(String src) {
        String pkg = "";
        int pkgIdx = src.indexOf("package ");
        if (pkgIdx >= 0) {
            int semi = src.indexOf(';', pkgIdx);
            pkg = src.substring(pkgIdx + 8, semi).trim();
        }
        String[] keywords = {"public enum ", "public record ", "public class ", "public interface ",
                             "enum ", "record ", "class ", "interface "};
        for (String kw : keywords) {
            int idx = src.indexOf(kw);
            if (idx >= 0) {
                int from = idx + kw.length();
                int end = from;
                while (end < src.length() && (Character.isJavaIdentifierPart(src.charAt(end)))) end++;
                String simple = src.substring(from, end);
                return pkg.isEmpty() ? simple : pkg + "." + simple;
            }
        }
        throw new IllegalArgumentException("No type found in source");
    }

    @Test
    void extracts_fqn_correctly() {
        assertEquals("a.b.C", extractFqn("package a.b; public class C {}"));
        assertEquals("a.b.E", extractFqn("package a.b; public enum E { A, B }"));
        assertEquals("a.b.R", extractFqn("package a.b; public record R(int x) {}"));
    }
}
