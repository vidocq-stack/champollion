package io.vidocq.champollion.codegen.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Mojo Champollion (squelette M0..M4) : sera étoffé en M5 pour scanner le
 * classpath du projet hôte et déléguer à {@code champollion-codegen-apt} la
 * génération des {@code BindingFactory}.
 *
 * <p>Pour l'instant, packaging du module = {@code jar} (cf. pom.xml). Le
 * passage en {@code maven-plugin} et l'activation du {@code maven-plugin-plugin}
 * descriptor sont reportés à M5 (l'ASM intégré à 3.15.1 ne lit pas encore
 * le bytecode Java 25).</p>
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class GenerateMojo extends AbstractMojo {

    /** Répertoire de sortie pour les sources générées. */
    @Parameter(defaultValue = "${project.build.directory}/generated-sources/champollion")
    private String outputDirectory;

    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("champollion-codegen-maven-plugin: skeleton (M5 not yet implemented).");
        getLog().info("Output directory would be: " + outputDirectory);
    }
}
