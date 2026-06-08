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

import io.vidocq.champollion.protobuf.Descriptors;
import io.vidocq.champollion.protobuf.codegen.JavaEmitter;
import io.vidocq.champollion.protobuf.codegen.ProtoAst;
import io.vidocq.champollion.protobuf.codegen.SchemaResolver;
import io.vidocq.champollion.protobuf.codegen.internal.ProtoParser;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Mojo {@code champollion-protobuf:generate} — orchestrates the chain
 * {@code .proto → AST → Descriptors → compilable Java records}.
 *
 * <p>Hooks into {@link LifecyclePhase#GENERATE_SOURCES}: emitted sources are
 * automatically added to the host project {@code compileSourceRoots}.</p>
 *
 * <p>Steps (per {@code .proto} file):</p>
 * <ol>
 *   <li>{@link ProtoParser#parse(String, String)} — syntax AST</li>
 *   <li>{@link SchemaResolver#resolve(io.vidocq.champollion.protobuf.codegen.ProtoAst.ProtoFile)}
 *       — semantic resolution</li>
 *   <li>{@link JavaEmitter#emit(Descriptors.FileDescriptor)} — Java source emission</li>
 *   <li>Write to {@link #outputDirectory} under the tree rooted at
 *       {@link #javaPackage} (per-file override option in M2.5).</li>
 * </ol>
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public final class GenerateProtoMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.basedir}/src/main/proto")
    private File sourceDirectory;

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/protobuf")
    private File outputDirectory;

    /**
     * Target Java package for generated classes. If empty, uses the file proto
     * {@code package} (typically {@code io.example} for {@code package io.example;}).
     * The per-file multi-package mode will come in M2.5.
     */
    @Parameter(defaultValue = "")
    private String javaPackage;

    /**
     * If {@code true}, emitted records are annotated {@code @ProtobufStatic} —
     * the {@code ProtobufStaticProcessor} APT (M3.1) will produce a
     * zero-reflection, ServiceLoader-discoverable parser. AOT-friendly.
     */
    @Parameter(defaultValue = "false")
    private boolean staticParser;

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException {
        if (!sourceDirectory.isDirectory()) {
            getLog().info("champollion-protobuf:generate — no .proto sources at " + sourceDirectory);
            return;
        }
        List<Path> protoFiles = collectProtoFiles(sourceDirectory.toPath());
        if (protoFiles.isEmpty()) {
            getLog().info("champollion-protobuf:generate — empty " + sourceDirectory);
            return;
        }
        try {
            Files.createDirectories(outputDirectory.toPath());
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot create output dir " + outputDirectory, e);
        }
        // Multi-file resolver: parse all files first, then resolve globally
        // to allow cross-file imports.
        List<ProtoAst.ProtoFile> asts = new ArrayList<>(protoFiles.size());
        for (Path proto : protoFiles) {
            String content;
            try {
                content = Files.readString(proto);
            } catch (IOException e) {
                throw new MojoExecutionException("Cannot read " + proto, e);
            }
            asts.add(ProtoParser.parse(proto.getFileName().toString(), content));
        }
        Map<String, Descriptors.FileDescriptor> resolved = SchemaResolver.resolveAll(asts);

        int total = 0;
        for (Descriptors.FileDescriptor desc : resolved.values()) {
            total += emitFile(desc);
        }
        getLog().info("champollion-protobuf:generate — wrote " + total
                + " Java file(s) for " + protoFiles.size() + " .proto source(s) → " + outputDirectory);

        if (project != null) {
            project.addCompileSourceRoot(outputDirectory.getAbsolutePath());
        }
    }

    int emitFile(Descriptors.FileDescriptor desc) throws MojoExecutionException {
        String pkg = effectiveJavaPackage(desc);
        Map<String, String> emitted = new JavaEmitter(pkg, staticParser).emit(desc);
        int count = 0;
        for (Map.Entry<String, String> e : emitted.entrySet()) {
            Path target = outputDirectory.toPath().resolve(toRelativeJavaPath(e.getKey()));
            try {
                Files.createDirectories(target.getParent());
                Files.writeString(target, e.getValue(),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
                count++;
            } catch (IOException ex) {
                throw new MojoExecutionException("Cannot write " + target, ex);
            }
        }
        return count;
    }

    private String effectiveJavaPackage(Descriptors.FileDescriptor desc) {
        if (javaPackage != null && !javaPackage.isEmpty()) return javaPackage;
        return desc.packageName().isEmpty() ? "protobuf.generated" : desc.packageName();
    }

    private static Path toRelativeJavaPath(String fqn) {
        return Path.of(fqn.replace('.', '/') + ".java");
    }

    static List<Path> collectProtoFiles(Path root) {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".proto"))
                    .forEach(out::add);
        } catch (IOException e) {
            // Silent: a missing directory is handled above by execute().
        }
        return out;
    }

    // Package-private setters for programmatic tests.
    void setSourceDirectory(File d) { this.sourceDirectory = d; }
    void setOutputDirectory(File d) { this.outputDirectory = d; }
    void setJavaPackage(String p) { this.javaPackage = p; }
    void setStaticParser(boolean b) { this.staticParser = b; }
}
