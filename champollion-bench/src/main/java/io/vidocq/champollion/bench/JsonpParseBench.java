package io.vidocq.champollion.bench;

import jakarta.json.spi.JsonProvider;
import jakarta.json.stream.JsonParser;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.StringReader;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark JSON-P pull-parser : Champollion vs Parsson.
 *
 * <p>On scanne tous les events du flux sans construire d'object model,
 * pour mesurer le tokenizer pur.</p>
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(value = 2, jvmArgsAppend = {"-Xms1G", "-Xmx1G", "-XX:+UseG1GC"})
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
public class JsonpParseBench {

    @Param({"SMALL", "MEDIUM", "LARGE"})
    public String size;

    private String json;
    private JsonProvider champollion;
    private JsonProvider parsson;

    @Setup(Level.Trial)
    public void setup() {
        json = switch (size) {
            case "SMALL" -> Workloads.smallJson();
            case "MEDIUM" -> Workloads.mediumJson();
            case "LARGE" -> Workloads.largeJson(100);
            default -> throw new IllegalArgumentException(size);
        };
        champollion = providerByClass("io.vidocq.champollion.jsonp.internal.ChampollionJsonProvider");
        parsson = providerByClass("org.eclipse.parsson.JsonProviderImpl");
    }

    private static JsonProvider providerByClass(String fqcn) {
        try {
            return (JsonProvider) Class.forName(fqcn).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Cannot instantiate " + fqcn, e);
        }
    }

    private static int drain(JsonParser p) {
        int n = 0;
        while (p.hasNext()) { p.next(); n++; }
        return n;
    }

    @Benchmark public void champollion(Blackhole bh) {
        try (var r = new StringReader(json); var p = champollion.createParser(r)) {
            bh.consume(drain(p));
        }
    }

    @Benchmark public void parsson(Blackhole bh) {
        try (var r = new StringReader(json); var p = parsson.createParser(r)) {
            bh.consume(drain(p));
        }
    }
}
