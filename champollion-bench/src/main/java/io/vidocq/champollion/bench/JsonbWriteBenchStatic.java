package io.vidocq.champollion.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import io.vidocq.champollion.jsonb.internal.ChampollionJsonbBuilder;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark write — <strong>mode codegen statique</strong> (M5).
 *
 * <p>Compare 4 voies sur des records annotés {@code @JsonbStatic} :</p>
 * <ul>
 *   <li>{@code champollion_static} — binding APT découvert par ServiceLoader, zéro
 *       réflexion, branche directe {@code JsonbBinding.write(g, value)} ;</li>
 *   <li>{@code champollion_runtime} — même Jsonb mais {@code withStaticBindings(List.of())}
 *       force le fallback introspectif (MethodHandles + cache) ;</li>
 *   <li>{@code yasson} / {@code jackson} / {@code jacksonJr} — concurrents.</li>
 * </ul>
 *
 * <p>L'APT {@code champollion-codegen-apt} est branché en
 * {@code annotationProcessorPaths} dans le pom du module bench : à la compilation,
 * un {@code <Type>$$Binding} est généré pour chaque record annoté ici, et
 * référencé dans {@code META-INF/services/io.vidocq.champollion.jsonb.spi.JsonbBinding}.</p>
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(value = 2, jvmArgsAppend = {"-Xms1G", "-Xmx1G", "-XX:+UseG1GC"})
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
public class JsonbWriteBenchStatic {

    @Param({"SMALL", "MEDIUM", "LARGE"})
    public String size;

    private Object payload;

    private Jsonb champollionStatic;
    private Jsonb champollionRuntime;
    private Jsonb yasson;
    private ObjectMapper jackson;
    private com.fasterxml.jackson.jr.ob.JSON jacksonJr;

    @Setup(Level.Trial)
    public void setup() {
        switch (size) {
            case "SMALL" -> payload = SmallStaticRecord.sample();
            case "MEDIUM" -> payload = OrderStaticRecord.sample();
            case "LARGE" -> payload = OrderStaticRecord.batch(100);
            default -> throw new IllegalArgumentException(size);
        }
        // ServiceLoader actif → bindings @JsonbStatic découverts.
        champollionStatic = JsonbBuilder
                .newBuilder("io.vidocq.champollion.jsonb.internal.ChampollionJsonbProvider")
                .build();

        // ServiceLoader bypassé → bindings statiques explicitement vides.
        champollionRuntime = ((ChampollionJsonbBuilder) JsonbBuilder
                .newBuilder("io.vidocq.champollion.jsonb.internal.ChampollionJsonbProvider"))
                .withStaticBindings(List.of())
                .build();

        yasson = JsonbBuilder.newBuilder("org.eclipse.yasson.JsonBindingProvider").build();
        jackson = new ObjectMapper().registerModule(new ParameterNamesModule());
        jacksonJr = com.fasterxml.jackson.jr.ob.JSON.std;
    }

    @TearDown(Level.Trial)
    public void teardown() throws Exception {
        champollionStatic.close();
        champollionRuntime.close();
        yasson.close();
    }

    @Benchmark public void champollion_static(Blackhole bh)  { bh.consume(champollionStatic.toJson(payload)); }
    @Benchmark public void champollion_runtime(Blackhole bh) { bh.consume(champollionRuntime.toJson(payload)); }
    @Benchmark public void yasson(Blackhole bh)              { bh.consume(yasson.toJson(payload)); }
    @Benchmark public void jackson(Blackhole bh) throws Exception { bh.consume(jackson.writeValueAsString(payload)); }
    @Benchmark public void jacksonJr(Blackhole bh) throws Exception { bh.consume(jacksonJr.asString(payload)); }
}
