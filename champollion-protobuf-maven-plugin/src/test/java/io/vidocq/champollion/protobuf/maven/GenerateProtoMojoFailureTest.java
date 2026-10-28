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
package io.vidocq.champollion.protobuf.maven;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The generation goal fails the build, naming the path, when it cannot create or write its output. */
class GenerateProtoMojoFailureTest {

    private static Path sources(Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("proto"));
        Files.writeString(src.resolve("m.proto"), """
                syntax = "proto3";
                package demo;
                message M { string s = 1; }
                """);
        return src;
    }

    @Test
    void an_output_directory_that_is_a_file_fails_the_build(@TempDir Path tmp) throws IOException {
        Path out = Files.writeString(tmp.resolve("out"), "a file, not a directory");
        GenerateProtoMojo mojo = new GenerateProtoMojo();
        mojo.setSourceDirectory(sources(tmp).toFile());
        mojo.setOutputDirectory(out.toFile());
        MojoExecutionException e = assertThrows(MojoExecutionException.class, mojo::execute);
        assertTrue(e.getMessage().contains("Cannot create output dir"), e.getMessage());
    }

    @Test
    void a_target_that_cannot_be_written_fails_the_build(@TempDir Path tmp) throws IOException {
        Path out = Files.createDirectories(tmp.resolve("out"));
        // the package directory of the generated source is taken by a file
        Files.writeString(out.resolve("demo"), "blocks the package directory");
        GenerateProtoMojo mojo = new GenerateProtoMojo();
        mojo.setSourceDirectory(sources(tmp).toFile());
        mojo.setOutputDirectory(out.toFile());
        mojo.setJavaPackage("");
        MojoExecutionException e = assertThrows(MojoExecutionException.class, mojo::execute);
        assertTrue(e.getMessage().startsWith("Cannot write "), e.getMessage());
    }
}
