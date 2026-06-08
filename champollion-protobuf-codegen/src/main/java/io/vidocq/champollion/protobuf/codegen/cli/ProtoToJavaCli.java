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
package io.vidocq.champollion.protobuf.codegen.cli;

import io.vidocq.champollion.protobuf.Descriptors;
import io.vidocq.champollion.protobuf.codegen.JavaEmitter;
import io.vidocq.champollion.protobuf.codegen.SchemaResolver;
import io.vidocq.champollion.protobuf.codegen.internal.ProtoParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * CLI {@code .proto → annotated Java records}. Standalone companion to the Mojo
 * {@code champollion-protobuf:generate} for environments where Maven 4 model 4.0.0
 * cannot load the plugin (cf. {@code champollion-protobuf-tck}).
 *
 * <p>Usage: {@code java -cp <classpath> io.vidocq.champollion.protobuf.codegen.cli.ProtoToJavaCli
 *           <sourceDir> <outputDir> <javaPackage> [--static-parser]}.</p>
 *
 * <p>Recursively scans {@code sourceDir} for {@code *.proto}, resolves
 * cross-file references via {@link SchemaResolver#resolveAll}, emits via
 * {@link JavaEmitter#emit(io.vidocq.champollion.protobuf.Descriptors.FileDescriptor)},
 * writes each source to {@code outputDir/<package>/<Class>.java}.</p>
 */
public final class ProtoToJavaCli {

    private ProtoToJavaCli() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println(
                    "usage: ProtoToJavaCli <sourceDir> <outputDir> <javaPackage> [--static-parser]");
            System.exit(2);
        }
        Path sourceDir = Path.of(args[0]);
        Path outputDir = Path.of(args[1]);
        String javaPackage = args[2];
        boolean staticParser = args.length > 3 && "--static-parser".equals(args[3]);

        List<io.vidocq.champollion.protobuf.codegen.ProtoAst.ProtoFile> files;
        try (Stream<Path> walk = Files.walk(sourceDir)) {
            files = walk
                    .filter(p -> p.toString().endsWith(".proto"))
                    .map(p -> {
                        try {
                            return ProtoParser.parse(
                                    sourceDir.relativize(p).toString(),
                                    Files.readString(p));
                        } catch (IOException e) {
                            throw new RuntimeException("Cannot read " + p, e);
                        }
                    })
                    .toList();
        }
        if (files.isEmpty()) {
            System.err.println("ProtoToJavaCli: no .proto under " + sourceDir);
            return;
        }
        Map<String, Descriptors.FileDescriptor> resolved = SchemaResolver.resolveAll(files);
        JavaEmitter emitter = new JavaEmitter(javaPackage, staticParser);
        Path pkgRoot = outputDir;
        for (String segment : javaPackage.split("\\.")) {
            pkgRoot = pkgRoot.resolve(segment);
        }
        Files.createDirectories(pkgRoot);
        int emitted = 0;
        for (Descriptors.FileDescriptor file : resolved.values()) {
            Map<String, String> sources = emitter.emit(file);
            for (Map.Entry<String, String> e : sources.entrySet()) {
                String className = e.getKey().substring(javaPackage.length() + 1);
                Path javaFile = pkgRoot.resolve(className + ".java");
                Files.writeString(javaFile, e.getValue());
                emitted++;
            }
        }
        System.out.println("ProtoToJavaCli: " + emitted + " source(s) written to " + pkgRoot);
    }
}
