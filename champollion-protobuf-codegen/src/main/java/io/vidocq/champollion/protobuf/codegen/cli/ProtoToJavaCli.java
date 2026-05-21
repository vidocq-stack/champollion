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
 * CLI {@code .proto → records Java annotés}. Pendant standalone du Mojo
 * {@code champollion-protobuf:generate} pour les contextes où Maven 4 model 4.0.0
 * ne peut pas charger le plugin (cf. {@code champollion-protobuf-tck}).
 *
 * <p>Usage : {@code java -cp <classpath> io.vidocq.champollion.protobuf.codegen.cli.ProtoToJavaCli
 *           <sourceDir> <outputDir> <javaPackage> [--static-parser]}.</p>
 *
 * <p>Scanne récursivement {@code sourceDir} pour les {@code *.proto}, résout
 * cross-fichiers via {@link SchemaResolver#resolveAll}, émet via
 * {@link JavaEmitter#emit(io.vidocq.champollion.protobuf.Descriptors.FileDescriptor)},
 * écrit chaque source dans {@code outputDir/<package>/<Class>.java}.</p>
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
