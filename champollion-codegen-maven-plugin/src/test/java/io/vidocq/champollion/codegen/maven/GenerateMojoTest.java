package io.vidocq.champollion.codegen.maven;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("M5.8 — GenerateMojo")
class GenerateMojoTest {

    @Test
    void emit_trigger_for_packaged_target(@TempDir Path tmp) throws Exception {
        Path src = GenerateMojo.emitTrigger(tmp, "com.example.Foo");
        assertTrue(Files.exists(src));
        String content = Files.readString(src);

        assertTrue(content.contains("package com.example;"),
                "Trigger must declare package");
        assertTrue(content.contains("@io.vidocq.champollion.jsonb.spi.JsonbStatic"),
                "Trigger must carry @JsonbStatic");
        assertTrue(content.contains("record Foo$$Trigger() {}"),
                "Trigger must declare the marker record");

        assertEquals(tmp.resolve("com/example/Foo$$Trigger.java"), src);
    }

    @Test
    void emit_trigger_for_default_package(@TempDir Path tmp) throws Exception {
        Path src = GenerateMojo.emitTrigger(tmp, "Bar");
        String content = Files.readString(src);
        assertTrue(!content.contains("package "), "No package line for default package");
        assertTrue(content.contains("record Bar$$Trigger() {}"));
    }

    @Test
    void mojo_class_loads_and_extends_AbstractMojo() {
        // @Mojo has RetentionPolicy.CLASS — not visible via runtime reflection.
        // We just verify the class hierarchy: if the module compiles and
        // the class extends AbstractMojo, the maven-plugin-plugin descriptor
        // will have included it in META-INF/maven/plugin.xml.
        assertNotNull(GenerateMojo.class);
        assertTrue(org.apache.maven.plugin.AbstractMojo.class.isAssignableFrom(GenerateMojo.class));
    }
}
