package io.vidocq.champollion.bench;

import jakarta.json.spi.JsonProvider;
import jakarta.json.stream.JsonGenerator;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark JSON-P generator push with <strong>unbuffered</strong> target
 * ({@link OutputStreamWriter} on an {@link OutputStream} that discards bytes).
 *
 * <p>This is the real REST case (Cassini → entityStream). Measures the benefit of
 * optimization P2 ({@code BufferedWriter} inside Champollion) which does not
 * appear with {@link StringWriter} (already buffered in memory).</p>
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgsAppend = {"-Xms1G", "-Xmx1G", "-XX:+UseG1GC"})
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 3)
public class JsonpGenerateOSBench {

    @Param({"SMALL", "MEDIUM"})
    public String size;

    private JsonProvider champollion;
    private JsonProvider parsson;

    /** {@link OutputStream} no-op to eliminate disk/network write cost. */
    private static final class NullOutputStream extends OutputStream {
        @Override public void write(int b) { /* no-op */ }
        @Override public void write(byte[] b, int off, int len) { /* no-op */ }
    }

    @Setup(Level.Trial)
    public void setup() {
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

    private void writeMedium(JsonGenerator g) {
        g.writeStartObject().write("id", 1001L).write("customer", "Acme Corp")
                .writeStartArray("items");
        for (int i = 0; i < 5; i++) {
            g.writeStartObject().write("sku", "SKU-" + i)
                    .write("quantity", i + 1).write("unitPrice", 10.0 + i * 1.5).writeEnd();
        }
        g.writeEnd().writeStartObject("shipping").write("street", "12 Rue de Rivoli")
                .write("city", "Paris").write("zip", "75001").write("country", "FR").writeEnd()
                .write("total", 87.5).write("priority", true).writeEnd();
    }

    private void writeSmall(JsonGenerator g) {
        g.writeStartObject().write("id", 42L).write("name", "Champollion").write("active", true).writeEnd();
    }

    private void runWith(JsonProvider p, Blackhole bh) {
        Writer w = new OutputStreamWriter(new NullOutputStream(), StandardCharsets.UTF_8);
        try (JsonGenerator g = p.createGenerator(w)) {
            if ("SMALL".equals(size)) writeSmall(g);
            else writeMedium(g);
        }
        bh.consume(w);
    }

    @Benchmark public void champollion(Blackhole bh) { runWith(champollion, bh); }
    @Benchmark public void parsson(Blackhole bh) { runWith(parsson, bh); }
}
