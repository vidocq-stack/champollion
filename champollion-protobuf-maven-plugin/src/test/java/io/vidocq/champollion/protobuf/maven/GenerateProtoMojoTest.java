package io.vidocq.champollion.protobuf.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link GenerateProtoMojo} — instanciation programmatique (sans
 * maven-plugin-testing-harness, qui force le module path Maven 4 compliqué).
 */
class GenerateProtoMojoTest {

    @Nested
    @DisplayName("execute — chaîne complète .proto → Java source")
    class HappyPath {

        @Test
        void emits_java_for_simple_proto(@TempDir Path tmp) throws Exception {
            Path src = tmp.resolve("src/main/proto");
            Files.createDirectories(src);
            Files.writeString(src.resolve("person.proto"), """
                    syntax = "proto3";
                    package io.example;
                    message Person {
                      string name = 1;
                      int32 age = 2;
                      repeated string tags = 3;
                    }
                    """);

            Path out = tmp.resolve("target/generated-sources/protobuf");

            GenerateProtoMojo mojo = new GenerateProtoMojo();
            mojo.setSourceDirectory(src.toFile());
            mojo.setOutputDirectory(out.toFile());
            mojo.setJavaPackage(""); // inherits from proto package

            mojo.execute();

            Path emitted = out.resolve("io/example/Person.java");
            assertTrue(Files.exists(emitted), "Expected " + emitted);
            String content = Files.readString(emitted);
            assertTrue(content.contains("package io.example;"), content);
            assertTrue(content.contains("public record Person("), content);
            assertTrue(content.contains("@ProtobufField(number = 1, type = FieldType.STRING)"), content);
        }

        @Test
        void java_package_override_takes_precedence(@TempDir Path tmp) throws Exception {
            Path src = tmp.resolve("src/main/proto");
            Files.createDirectories(src);
            Files.writeString(src.resolve("a.proto"), """
                    syntax = "proto3";
                    package proto.pkg;
                    message A { string x = 1; }
                    """);

            Path out = tmp.resolve("target/generated-sources/protobuf");
            GenerateProtoMojo mojo = new GenerateProtoMojo();
            mojo.setSourceDirectory(src.toFile());
            mojo.setOutputDirectory(out.toFile());
            mojo.setJavaPackage("io.override");

            mojo.execute();

            assertTrue(Files.exists(out.resolve("io/override/A.java")));
            assertFalse(Files.exists(out.resolve("proto/pkg/A.java")));
        }

        @Test
        void multiple_files_in_subdirs(@TempDir Path tmp) throws Exception {
            Path src = tmp.resolve("src/main/proto");
            Files.createDirectories(src.resolve("nested"));
            Files.writeString(src.resolve("a.proto"), """
                    syntax = "proto3";
                    package x;
                    message A { string s = 1; }
                    """);
            Files.writeString(src.resolve("nested/b.proto"), """
                    syntax = "proto3";
                    package y;
                    message B { int32 i = 1; }
                    """);

            Path out = tmp.resolve("out");
            GenerateProtoMojo mojo = new GenerateProtoMojo();
            mojo.setSourceDirectory(src.toFile());
            mojo.setOutputDirectory(out.toFile());
            mojo.execute();

            assertTrue(Files.exists(out.resolve("x/A.java")));
            assertTrue(Files.exists(out.resolve("y/B.java")));
        }
    }

    @Nested
    @DisplayName("execute — cas limites")
    class EdgeCases {

        @Test
        void missing_source_directory_is_noop(@TempDir Path tmp) throws Exception {
            GenerateProtoMojo mojo = new GenerateProtoMojo();
            mojo.setSourceDirectory(tmp.resolve("nope").toFile());
            mojo.setOutputDirectory(tmp.resolve("out").toFile());
            // Ne doit pas planter
            mojo.execute();
            assertFalse(Files.exists(tmp.resolve("out")));
        }

        @Test
        void empty_source_directory_is_noop(@TempDir Path tmp) throws Exception {
            Files.createDirectories(tmp.resolve("src"));
            GenerateProtoMojo mojo = new GenerateProtoMojo();
            mojo.setSourceDirectory(tmp.resolve("src").toFile());
            mojo.setOutputDirectory(tmp.resolve("out").toFile());
            mojo.execute();
            // Either the out directory was not created or it is empty
            if (Files.exists(tmp.resolve("out"))) {
                try (var s = Files.list(tmp.resolve("out"))) {
                    assertEquals(0, s.count());
                }
            }
        }

        @Test
        void proto_without_package_uses_default(@TempDir Path tmp) throws Exception {
            Path src = tmp.resolve("src");
            Files.createDirectories(src);
            Files.writeString(src.resolve("nopkg.proto"), """
                    syntax = "proto3";
                    message Plain { string s = 1; }
                    """);
            Path out = tmp.resolve("out");
            GenerateProtoMojo mojo = new GenerateProtoMojo();
            mojo.setSourceDirectory(src.toFile());
            mojo.setOutputDirectory(out.toFile());
            mojo.execute();
            assertTrue(Files.exists(out.resolve("protobuf/generated/Plain.java")));
        }
    }
}
