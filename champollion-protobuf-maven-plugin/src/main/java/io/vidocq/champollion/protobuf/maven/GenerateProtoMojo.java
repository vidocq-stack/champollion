package io.vidocq.champollion.protobuf.maven;

import io.vidocq.champollion.protobuf.Descriptors;
import io.vidocq.champollion.protobuf.codegen.JavaEmitter;
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
 * Mojo {@code champollion-protobuf:generate} — orchestre la chaîne
 * {@code .proto → AST → Descriptors → Java records compilables}.
 *
 * <p>Lie {@link LifecyclePhase#GENERATE_SOURCES} : les sources émises sont
 * automatiquement ajoutées au {@code compileSourceRoots} du projet hôte.</p>
 *
 * <p>Étapes (par fichier {@code .proto}) :</p>
 * <ol>
 *   <li>{@link ProtoParser#parse(String, String)} — AST syntaxique</li>
 *   <li>{@link SchemaResolver#resolve(io.vidocq.champollion.protobuf.codegen.ProtoAst.ProtoFile)}
 *       — résolution sémantique</li>
 *   <li>{@link JavaEmitter#emit(Descriptors.FileDescriptor)} — émission Java source</li>
 *   <li>Écriture dans {@link #outputDirectory} sous l'arborescence du
 *       {@link #javaPackage} (option override par fichier en M2.5).</li>
 * </ol>
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public final class GenerateProtoMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.basedir}/src/main/proto")
    private File sourceDirectory;

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/protobuf")
    private File outputDirectory;

    /**
     * Package Java cible des classes générées. Si vide, utilise le {@code package}
     * proto du fichier (typiquement {@code io.example} pour
     * {@code package io.example;}). Le multi-package par-file viendra en M2.5.
     */
    @Parameter(defaultValue = "")
    private String javaPackage;

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
        int total = 0;
        for (Path proto : protoFiles) {
            total += generateOne(proto);
        }
        getLog().info("champollion-protobuf:generate — wrote " + total
                + " Java file(s) for " + protoFiles.size() + " .proto source(s) → " + outputDirectory);

        if (project != null) {
            project.addCompileSourceRoot(outputDirectory.getAbsolutePath());
        }
    }

    int generateOne(Path proto) throws MojoExecutionException {
        String content;
        try {
            content = Files.readString(proto);
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot read " + proto, e);
        }
        var ast = ProtoParser.parse(proto.getFileName().toString(), content);
        Descriptors.FileDescriptor desc = SchemaResolver.resolve(ast);
        String pkg = effectiveJavaPackage(desc);
        Map<String, String> emitted = new JavaEmitter(pkg).emit(desc);
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
            // Silencieux : un répertoire absent est traité plus haut par execute().
        }
        return out;
    }

    // Setters package-private pour les tests programmatiques.
    void setSourceDirectory(File d) { this.sourceDirectory = d; }
    void setOutputDirectory(File d) { this.outputDirectory = d; }
    void setJavaPackage(String p) { this.javaPackage = p; }
}
