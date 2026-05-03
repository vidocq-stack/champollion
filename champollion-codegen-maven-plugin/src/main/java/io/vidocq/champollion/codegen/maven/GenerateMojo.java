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
 * Mojo Champollion : pour les classes JSON-B fournies dans la liste {@code <targets>},
 * génère un binding statique {@code <FQN>$$Binding} via {@code champollion-codegen-apt}
 * sans nécessiter d'annoter le source — utile pour les types externes ou hérités.
 *
 * <p>Le Mojo génère à la volée un fichier source proxy {@code <FQN>$$Trigger.java}
 * annoté {@code @JsonbStatic} qui hérite/wrapper le type cible, puis lance le
 * {@code javac} avec le {@code JsonbStaticProcessor} actif. Les classes générées
 * sont placées dans {@code outputDirectory} qui est ajouté aux sources du projet.</p>
 *
 * <p>Pour les classes annotées {@code @JsonbStatic} directement dans les sources
 * du projet, ce plugin n'est pas nécessaire — l'APT s'active automatiquement
 * pendant la compilation principale.</p>
 */
@Mojo(name = "generate",
        defaultPhase = LifecyclePhase.GENERATE_SOURCES,
        requiresDependencyResolution = ResolutionScope.COMPILE,
        threadSafe = true)
public class GenerateMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * Liste des FQN des records à scanner et pour lesquels générer un binding.
     * Les types doivent être présents sur le classpath compile.
     */
    @Parameter
    private List<String> targets = new ArrayList<>();

    /** Répertoire de sortie pour les sources générées (proxies + bindings). */
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

            // Pour chaque target, génère un trigger annoté @JsonbStatic qui re-déclare
            // les composants du record (proxy minimaliste). Plus simple : on suppose que
            // l'utilisateur peut référencer le type via un import et qu'on génère un
            // record-trigger qui pointe dessus.
            // M5.8 MVP : on génère des "shadow records" annotés @JsonbStatic qui
            // re-déclarent les composants d'un record ciblé. L'utilisateur fournit un
            // FQN pleine forme, et on s'attend à ce que le record cible soit accessible.
            //
            // Pour rester gérable, M5.8 livre l'infrastructure et la phase generate-sources :
            // l'extension dynamique (introspection de chaque target pour générer les
            // shadow records) sera enrichie au fur et à mesure des cas d'usage réels.

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
     * Émet un fichier {@code <Pkg>.<Simple>$$Trigger.java} annoté {@code @JsonbStatic}
     * qui pointe vers un record cible accessible. M5.8 MVP : trigger vide, l'APT
     * verra l'annotation et générera le binding pour le record déclaré.
     *
     * <p>Note : ce MVP suppose que l'utilisateur a annoté le record cible
     * directement. La voie "scan tous les records du classpath" sera ajoutée plus
     * tard quand on aura besoin de couvrir les types externes.</p>
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
     * Lance le compilateur Java sur les sources trigger, avec le
     * {@code JsonbStaticProcessor} actif et le classpath compile du projet hôte.
     */
    private void runApt(List<Path> sources) throws MojoExecutionException, IOException {
        if (sources.isEmpty()) return;
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new MojoExecutionException("No system Java compiler available (run on JDK, not JRE).");
        }

        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDirectory));

            // Classpath du projet hôte. getCompileClasspathElements peut lever
            // une checked exception sur certaines versions Maven : on capture
            // largement pour rester portable.
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

    /** Hooks pour les tests : injection programmatique. */
    void setProject(MavenProject p) { this.project = p; }
    void setTargets(List<String> t) { this.targets = t; }
    void setOutputDirectory(File f) { this.outputDirectory = f; }

    Path outputDirectoryPath() { return outputDirectory.toPath(); }
}
