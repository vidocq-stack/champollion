package io.vidocq.champollion.protobuf.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;

/**
 * Mojo {@code champollion-protobuf:generate} — scanne {@code src/main/proto},
 * délègue à {@code champollion-protobuf-codegen} pour produire un Java
 * équivalent à {@code protoc-gen-java}.
 *
 * <p>Squelette M1 : le Mojo existe pour stabiliser l'arborescence Maven mais
 * l'implémentation réelle (lexer/parser {@code .proto}, emitter Class-File API)
 * vient en M2.</p>
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public final class GenerateProtoMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.basedir}/src/main/proto")
    private File sourceDirectory;

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/protobuf")
    private File outputDirectory;

    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("champollion-protobuf:generate — squelette M1, codegen réel reporté M2.");
        getLog().info("  sourceDirectory : " + sourceDirectory);
        getLog().info("  outputDirectory : " + outputDirectory);
    }
}
