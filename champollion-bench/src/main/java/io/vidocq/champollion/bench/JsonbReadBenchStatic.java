package io.vidocq.champollion.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.fasterxml.jackson.core.type.TypeReference;
import io.vidocq.champollion.jsonb.internal.ChampollionJsonbBuilder;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark read — mode codegen statique (M5).
 *
 * <p>JSON déjà sérialisé est passé à 5 implémentations qui le rebondissent en
 * {@code OrderStaticRecord} / {@code SmallStaticRecord} / {@code List<OrderStaticRecord>}.</p>
 *
 * <p>{@code champollion_static} bénéficie du binding APT : pas de cache lookup,
 * pas de MethodHandles, switch direct sur les keys connues à la compilation.</p>
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(value = 2, jvmArgsAppend = {"-Xms1G", "-Xmx1G", "-XX:+UseG1GC"})
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
public class JsonbReadBenchStatic {

    @Param({"SMALL", "MEDIUM", "LARGE"})
    public String size;

    private String json;
    private Class<?> recordType;
    private Type runtimeType;
    private TypeReference<?> jacksonRef;

    private Jsonb champollionStatic;
    private Jsonb champollionRuntime;
    private Jsonb yasson;
    private ObjectMapper jackson;
    private com.fasterxml.jackson.jr.ob.JSON jacksonJr;

    @Setup(Level.Trial)
    public void setup() {
        // Sérialise une fois avec champollion (pré-init léger) pour les payloads.
        Jsonb seed = JsonbBuilder
                .newBuilder("io.vidocq.champollion.jsonb.internal.ChampollionJsonbProvider")
                .build();
        switch (size) {
            case "SMALL" -> {
                json = seed.toJson(SmallStaticRecord.sample());
                recordType = SmallStaticRecord.class;
                runtimeType = SmallStaticRecord.class;
                jacksonRef = new TypeReference<SmallStaticRecord>() {};
            }
            case "MEDIUM" -> {
                json = seed.toJson(OrderStaticRecord.sample());
                recordType = OrderStaticRecord.class;
                runtimeType = OrderStaticRecord.class;
                jacksonRef = new TypeReference<OrderStaticRecord>() {};
            }
            case "LARGE" -> {
                json = seed.toJson(OrderStaticRecord.batch(100));
                recordType = List.class;
                runtimeType = new com.fasterxml.jackson.core.type.TypeReference<List<OrderStaticRecord>>() {}.getType();
                jacksonRef = new TypeReference<List<OrderStaticRecord>>() {};
            }
            default -> throw new IllegalArgumentException(size);
        }
        try { seed.close(); } catch (Exception ignored) {}

        champollionStatic = JsonbBuilder
                .newBuilder("io.vidocq.champollion.jsonb.internal.ChampollionJsonbProvider")
                .build();
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

    @Benchmark public void champollion_static(Blackhole bh)  { bh.consume(champollionStatic.fromJson(json, runtimeType)); }
    @Benchmark public void champollion_runtime(Blackhole bh) { bh.consume(champollionRuntime.fromJson(json, runtimeType)); }
    @Benchmark public void yasson(Blackhole bh)              { bh.consume(yasson.fromJson(json, runtimeType)); }
    @Benchmark public void jackson(Blackhole bh) throws Exception { bh.consume(jackson.readValue(json, jacksonRef)); }
    @Benchmark public void jacksonJr(Blackhole bh) throws Exception {
        if ("LARGE".equals(size)) bh.consume(jacksonJr.listOfFrom(OrderStaticRecord.class, json));
        else bh.consume(jacksonJr.beanFrom(recordType, json));
    }
}
