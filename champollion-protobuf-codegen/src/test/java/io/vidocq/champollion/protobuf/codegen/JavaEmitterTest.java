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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.vidocq.champollion.protobuf.Descriptors;
import io.vidocq.champollion.protobuf.Protobuf;
import io.vidocq.champollion.protobuf.codegen.internal.ProtoParser;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link JavaEmitter} — émission de records Java depuis un FileDescriptor,
 * suivi (test bonus) d'une compilation in-memory + différentiel testing
 * runtime M1.3.
 */
class JavaEmitterTest {

    private Descriptors.FileDescriptor descriptorFrom(String src) {
        return SchemaResolver.resolve(ProtoParser.parse("inline.proto", src));
    }

    private Map<String, String> emit(String javaPackage, String src) {
        return new JavaEmitter(javaPackage).emit(descriptorFrom(src));
    }

    @Nested
    @DisplayName("Émission texte — patterns attendus")
    class Textual {

        @Test
        void person_record_with_string_int_repeated() {
            Map<String, String> out = emit("io.test", """
                    syntax = "proto3";
                    package test;
                    message Person {
                      string name = 1;
                      int32 age = 2;
                      repeated string tags = 3;
                    }
                    """);
            String src = out.get("io.test.Person");
            assertNotNull(src);
            assertTrue(src.contains("package io.test;"), "package: " + src);
            assertTrue(src.contains("@ProtobufMessage(\"test.Person\")"), src);
            assertTrue(src.contains("public record Person("), src);
            assertTrue(src.contains("@ProtobufField(number = 1, type = FieldType.STRING) String name"), src);
            assertTrue(src.contains("@ProtobufField(number = 2, type = FieldType.INT32) int age"), src);
            assertTrue(src.contains("@ProtobufField(number = 3, type = FieldType.STRING) List<String> tags"), src);
            assertTrue(src.contains("implements Message"), src);
        }

        @Test
        void enum_top_level() {
            Map<String, String> out = emit("io.test", """
                    syntax = "proto3";
                    enum Role { USER = 0; ADMIN = 1; SUPER = 2; }
                    """);
            String src = out.get("io.test.Role");
            assertNotNull(src);
            assertTrue(src.contains("public enum Role {"), src);
            assertTrue(src.contains("USER,"), src);
            assertTrue(src.contains("SUPER;"), src);
        }

        @Test
        void nested_message_emitted_as_inner_record() {
            Map<String, String> out = emit("io.test", """
                    syntax = "proto3";
                    message Outer {
                      message Inner { int32 x = 1; }
                      Inner inner = 1;
                    }
                    """);
            String src = out.get("io.test.Outer");
            assertTrue(src.contains("public record Outer("), src);
            assertTrue(src.contains("Inner inner"), src);
            assertTrue(src.contains("public record Inner("), src);
        }

        @Test
        void repeated_int32_is_List_Integer() {
            Map<String, String> out = emit("io.test", """
                    syntax = "proto3";
                    message M { repeated int32 nums = 1; }
                    """);
            assertTrue(out.get("io.test.M").contains("List<Integer> nums"), out.get("io.test.M"));
        }

        @Test
        void reserved_java_keyword_field_suffixed() {
            Map<String, String> out = emit("io.test", """
                    syntax = "proto3";
                    message M { int32 class = 1; }
                    """);
            // 'class' est un mot-clé Java → suffixé _
            assertTrue(out.get("io.test.M").contains("class_"), out.get("io.test.M"));
        }
    }

    @Nested
    @DisplayName("Bonus : compilation in-memory + round-trip via runtime M1.3")
    class CompileAndRoundtrip {

        @Test
        void emitted_record_roundtrips_with_runtime() throws Exception {
            String pkg = "io.vidocq.champollion.codegen.gen";
            Map<String, String> sources = emit(pkg, """
                    syntax = "proto3";
                    package example;
                    message Person {
                      string name = 1;
                      int32 age = 2;
                      repeated string tags = 3;
                    }
                    """);
            ClassLoader cl = compile(sources);

            Class<?> personClass = cl.loadClass(pkg + ".Person");
            // Constructeur canonical du record : (String, int, List<String>)
            Object person = personClass.getDeclaredConstructors()[0]
                    .newInstance("alice", 30, List.of("a", "b"));

            byte[] bytes = Protobuf.toByteArray(person);
            Object back = Protobuf.parser(personClass).parseFrom(bytes);
            assertEquals(person, back);
        }
    }

    /**
     * Compilateur Java in-memory : prend un Map FQN → source, retourne un
     * ClassLoader qui charge les classes compilées.
     */
    private static ClassLoader compile(Map<String, String> sources) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            fail("No system JavaCompiler — run tests under a JDK, not a JRE.");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager standardFm = compiler.getStandardFileManager(diagnostics, null, null);
        InMemoryFileManager fm = new InMemoryFileManager(standardFm);

        List<JavaFileObject> units = new ArrayList<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            units.add(new InMemorySource(e.getKey(), e.getValue()));
        }
        // Options nécessaires pour résoudre les imports vers io.vidocq.champollion.protobuf (module).
        List<String> opts = List.of(
                "--add-modules", "io.vidocq.champollion.protobuf",
                "--module-path", System.getProperty("jdk.module.path", ""));
        boolean ok = compiler.getTask(null, fm, diagnostics, opts, null, units).call();
        if (!ok) {
            StringBuilder sb = new StringBuilder("Java compilation failed:\n");
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                sb.append("  ").append(d.getKind()).append(": ").append(d.getMessage(null)).append('\n');
            }
            for (Map.Entry<String, String> e : sources.entrySet()) {
                sb.append("\n--- ").append(e.getKey()).append(" ---\n").append(e.getValue());
            }
            fail(sb.toString());
        }
        return fm.classLoader();
    }

    static final class InMemorySource extends SimpleJavaFileObject {
        private final String code;
        InMemorySource(String fqn, String code) {
            super(URI.create("string:///" + fqn.replace('.', '/') + ".java"), Kind.SOURCE);
            this.code = code;
        }
        @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return code; }
    }

    static final class InMemoryClass extends SimpleJavaFileObject {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        InMemoryClass(String fqn) {
            super(URI.create("bytes:///" + fqn.replace('.', '/') + ".class"), Kind.CLASS);
        }
        @Override public java.io.OutputStream openOutputStream() { return out; }
        byte[] bytes() { return out.toByteArray(); }
    }

    static final class InMemoryFileManager extends ForwardingJavaFileManager<JavaFileManager> {
        private final Map<String, InMemoryClass> classes = new HashMap<>();

        InMemoryFileManager(JavaFileManager fileManager) {
            super(fileManager);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className,
                                                   JavaFileObject.Kind kind, FileObject sibling) {
            InMemoryClass cls = new InMemoryClass(className);
            classes.put(className, cls);
            return cls;
        }

        ClassLoader classLoader() {
            return new ClassLoader(JavaEmitterTest.class.getClassLoader()) {
                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    InMemoryClass cls = classes.get(name);
                    if (cls == null) throw new ClassNotFoundException(name);
                    byte[] b = cls.bytes();
                    return defineClass(name, b, 0, b.length);
                }
            };
        }
    }
}
