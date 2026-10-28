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
package io.vidocq.champollion.codegen.maven;

import io.vidocq.champollion.codegen.apt.JsonbStaticProcessor;
import io.vidocq.champollion.jsonb.spi.JsonbStatic;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("GenerateMojo — execute against a project")
class GenerateMojoExecuteTest {

    private static final String TARGET = "com.example.Sample";

    /** A project whose compile classpath is a fixed list, or a resolution failure when null. */
    private static final class FixedClasspathProject extends MavenProject {
        private final List<String> classpath;

        FixedClasspathProject(List<String> classpath) {
            this.classpath = classpath;
        }

        @Override
        public List<String> getCompileClasspathElements() throws DependencyResolutionRequiredException {
            if (classpath == null) {
                throw new DependencyResolutionRequiredException(null);
            }
            return classpath;
        }
    }

    /** The jars (or directories) holding the processor and the annotations it reads. */
    private static List<String> processorClasspath() {
        Set<String> out = new LinkedHashSet<>();
        for (Class<?> c : List.of(JsonbStaticProcessor.class, JsonbStatic.class,
                jakarta.json.bind.Jsonb.class, jakarta.json.Json.class,
                io.vidocq.champollion.spi.RawJsonKeyWriter.class)) {
            try {
                out.add(Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
            } catch (URISyntaxException e) {
                throw new IllegalStateException(e);
            }
        }
        return new ArrayList<>(out);
    }

    private static GenerateMojo mojo(MavenProject project, List<String> targets, File out) {
        GenerateMojo m = new GenerateMojo();
        m.setProject(project);
        m.setTargets(targets);
        m.setOutputDirectory(out);
        return m;
    }

    @Test
    void without_targets_only_the_source_root_is_added(@TempDir Path tmp) throws Exception {
        MavenProject project = new FixedClasspathProject(List.of());
        File out = tmp.resolve("gen").toFile();
        mojo(project, new ArrayList<>(), out).execute();
        assertEquals(List.of(out.getAbsolutePath()), project.getCompileSourceRoots());
        assertFalse(out.exists(), "nothing generated");
    }

    @Test
    void a_target_gets_its_trigger_and_the_processor_output(@TempDir Path tmp) throws Exception {
        MavenProject project = new FixedClasspathProject(processorClasspath());
        File out = tmp.resolve("gen").toFile();
        GenerateMojo m = mojo(project, List.of(TARGET), out);
        m.execute();

        assertEquals(out.toPath(), m.outputDirectoryPath());
        assertTrue(Files.isRegularFile(out.toPath().resolve("triggers/com/example/Sample$$Trigger.java")));
        try (Stream<Path> files = Files.walk(out.toPath())) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().endsWith("$$Binding.java")),
                    "the processor generated a binding source");
        }
        assertEquals(List.of(out.getAbsolutePath()), project.getCompileSourceRoots());
    }

    @Test
    void a_trigger_that_does_not_compile_fails_the_build(@TempDir Path tmp) {
        MavenProject project = new FixedClasspathProject(processorClasspath());
        GenerateMojo m = mojo(project, List.of("com.example.not-a-name"), tmp.resolve("gen").toFile());
        MojoExecutionException e = assertThrows(MojoExecutionException.class, m::execute);
        assertTrue(e.getMessage().contains("codegen-apt failed"), e.getMessage());
    }

    @Test
    void an_unresolved_classpath_fails_the_build(@TempDir Path tmp) {
        GenerateMojo m = mojo(new FixedClasspathProject(null), List.of(TARGET), tmp.resolve("gen").toFile());
        MojoExecutionException e = assertThrows(MojoExecutionException.class, m::execute);
        assertInstanceOf(DependencyResolutionRequiredException.class, e.getCause());
    }

    @Test
    void an_output_directory_that_is_a_file_fails_the_build(@TempDir Path tmp) throws Exception {
        Path file = Files.writeString(tmp.resolve("occupied"), "x");
        GenerateMojo m = mojo(new FixedClasspathProject(List.of()), List.of(TARGET), file.toFile());
        MojoExecutionException e = assertThrows(MojoExecutionException.class, m::execute);
        assertTrue(e.getMessage().contains("Failed to generate"), e.getMessage());
    }
}
