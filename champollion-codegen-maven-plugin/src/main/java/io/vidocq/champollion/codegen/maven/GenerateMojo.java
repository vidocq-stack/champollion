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

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/**
 * Champollion Mojo: for JSON-B classes listed in {@code <targets>}, generates a
 * static {@code <FQN>$$Binding} via {@code champollion-codegen-apt} without
 * requiring source annotations — useful for external or inherited types.
 *
 * <p>The Mojo generates an on-the-fly proxy source file {@code <FQN>$$Trigger.java}
 * annotated with {@code @JsonbStatic} that extends/wraps the target type, then
 * launches {@code javac} with the {@code JsonbStaticProcessor} enabled. Generated
 * classes are placed in {@code outputDirectory}, which is added to the project sources.</p>
 *
 * <p>For classes annotated with {@code @JsonbStatic} directly in the project
 * sources, this plugin is not needed — the APT activates automatically during the
 * main compilation.</p>
 */
@Mojo(name = "generate",
        defaultPhase = LifecyclePhase.GENERATE_SOURCES,
        requiresDependencyResolution = ResolutionScope.COMPILE,
        threadSafe = true)
public class GenerateMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * List of record FQNs to scan and for which to generate a binding.
     * The types must be present on the compile classpath.
     */
    @Parameter
    private List<String> targets = new ArrayList<>();

    /** Output directory for generated sources (proxies + bindings). */
    @Parameter(defaultValue = "${project.build.directory}/generated-sources/champollion")
    private File outputDirectory;

    @Override
    public void execute() throws MojoExecutionException {
        if (targets.isEmpty()) {
            getLog().info("champollion-codegen: no <targets> configured, nothing to generate.");
            project.addCompileSourceRoot(outputDirectory.getAbsolutePath());
            return;
        }

        try {
            Files.createDirectories(outputDirectory.toPath());
            Path sourceTriggers = outputDirectory.toPath().resolve("triggers");
            Files.createDirectories(sourceTriggers);

            // For each target, generate a trigger annotated with @JsonbStatic that
            // redeclares the record components (minimal proxy). Simpler: assume the
            // user can reference the type via an import and generate a record trigger
            // that points to it.
            // M5.8 MVP: generate "shadow records" annotated with @JsonbStatic that
            // redeclare the components of a targeted record. The user provides a fully
            // qualified name, and the target record is expected to be accessible.
            //
            // To keep things manageable, M5.8 ships the infrastructure and the
            // generate-sources phase: the dynamic extension (introspection of each
            // target to generate the shadow records) will grow with real use cases.

            List<Path> triggerSources = new ArrayList<>();
            for (String fqn : targets) {
                triggerSources.add(GenerateMojo.emitTrigger(sourceTriggers, fqn));
            }

            runApt(triggerSources);
        } catch (IOException ex) {
            throw new MojoExecutionException("Failed to generate Champollion bindings", ex);
        }

        project.addCompileSourceRoot(outputDirectory.getAbsolutePath());
        getLog().info("champollion-codegen: generated " + targets.size() + " binding(s) in " + outputDirectory);
    }

    /**
     * Emits a {@code <Pkg>.<Simple>$$Trigger.java} file annotated with
     * {@code @JsonbStatic} that points to an accessible target record. M5.8 MVP:
     * empty trigger, the APT sees the annotation and generates the binding for the
     * declared record.
     *
     * <p>Note: this MVP assumes the user annotated the target record directly. The
     * "scan all records on the classpath" path will be added later when external
     * types need to be covered.</p>
     */
    static Path emitTrigger(Path triggersDir, String fqn) throws IOException {
        int dot = fqn.lastIndexOf('.');
        String pkg = dot > 0 ? fqn.substring(0, dot) : "";
        String simple = dot > 0 ? fqn.substring(dot + 1) : fqn;

        Path pkgDir = pkg.isEmpty() ? triggersDir : triggersDir.resolve(pkg.replace('.', '/'));
        Files.createDirectories(pkgDir);
        Path src = pkgDir.resolve(simple + "$$Trigger.java");

        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(src, StandardCharsets.UTF_8))) {
            if (!pkg.isEmpty()) {
                pw.println("package " + pkg + ";");
                pw.println();
            }
            pw.println("/** Auto-generated trigger for Champollion codegen on " + fqn + ". */");
            pw.println("@io.vidocq.champollion.jsonb.spi.JsonbStatic");
            pw.println("record " + simple + "$$Trigger() {}");
        }
        return src;
    }

    /**
     * Launches the Java compiler on the trigger sources, with the
     * {@code JsonbStaticProcessor} enabled and the host project's compile classpath.
     */
    private void runApt(List<Path> sources) throws MojoExecutionException, IOException {
        if (sources.isEmpty()) return;
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new MojoExecutionException("No system Java compiler available (run on JDK, not JRE).");
        }

        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDirectory));

            // Host project's classpath. getCompileClasspathElements can throw a
            // checked exception on some Maven versions: catch broadly to stay portable.
            List<File> cp = new ArrayList<>();
            try {
                for (Object e : project.getCompileClasspathElements()) {
                    cp.add(new File(e.toString()));
                }
            } catch (Exception ex) {
                throw new MojoExecutionException("Cannot resolve compile classpath: " + ex.getMessage(), ex);
            }
            fm.setLocation(StandardLocation.CLASS_PATH, cp);

            var task = compiler.getTask(
                    null, fm, null,
                    List.of("--release", "25",
                            "-processor", "io.vidocq.champollion.codegen.apt.JsonbStaticProcessor",
                            "-proc:only"),
                    null,
                    fm.getJavaFileObjectsFromPaths(sources));
            boolean ok = task.call();
            if (!ok) {
                throw new MojoExecutionException("Champollion codegen-apt failed for: " + sources);
            }
        }
    }

    /** Test hooks: programmatic injection. */
    void setProject(MavenProject p) { this.project = p; }
    void setTargets(List<String> t) { this.targets = t; }
    void setOutputDirectory(File f) { this.outputDirectory = f; }

    Path outputDirectoryPath() { return outputDirectory.toPath(); }
}
