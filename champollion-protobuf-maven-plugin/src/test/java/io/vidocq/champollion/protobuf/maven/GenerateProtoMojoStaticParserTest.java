package io.vidocq.champollion.protobuf.maven;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests M3.2 — propagation du flag {@code staticParser} depuis le Mojo
 * jusqu'au {@code JavaEmitter} : un fichier {@code .proto} compilé avec
 * {@code staticParser=true} produit un record annoté
 * {@code @ProtobufStatic} prêt à être consommé par l'APT M3.1.
 */
@DisplayName("GenerateProtoMojo — staticParser parameter")
class GenerateProtoMojoStaticParserTest {

    private static final String PROTO = """
            syntax = "proto3";
            package io.example;
            message Person {
              string name = 1;
              int32 age = 2;
            }
            """;

    @Test
    void emitted_record_annotated_when_staticParser_true(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src/main/proto");
        Files.createDirectories(src);
        Files.writeString(src.resolve("person.proto"), PROTO);
        Path out = tmp.resolve("target/generated-sources/protobuf");

        GenerateProtoMojo mojo = new GenerateProtoMojo();
        mojo.setSourceDirectory(src.toFile());
        mojo.setOutputDirectory(out.toFile());
        mojo.setStaticParser(true);
        mojo.execute();

        String content = Files.readString(out.resolve("io/example/Person.java"));
        assertTrue(content.contains("@ProtobufStatic"), content);
    }

    @Test
    void emitted_record_not_annotated_by_default(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src/main/proto");
        Files.createDirectories(src);
        Files.writeString(src.resolve("person.proto"), PROTO);
        Path out = tmp.resolve("target/generated-sources/protobuf");

        GenerateProtoMojo mojo = new GenerateProtoMojo();
        mojo.setSourceDirectory(src.toFile());
        mojo.setOutputDirectory(out.toFile());
        // staticParser not set → default false
        mojo.execute();

        String content = Files.readString(out.resolve("io/example/Person.java"));
        assertFalse(content.contains("@ProtobufStatic"), content);
    }
}
