package io.vidocq.champollion.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Benchmark write : POJO/record → JSON string.
 *
 * <p>4 implémentations : Champollion, Yasson, Jackson databind, Jackson-jr.</p>
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(value = 2, jvmArgsAppend = {"-Xms1G", "-Xmx1G", "-XX:+UseG1GC"})
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
public class JsonbWriteBench {

    /** Workload : SMALL ~50 B, MEDIUM ~700 B, LARGE 100 medium nested. */
    @Param({"SMALL", "MEDIUM", "LARGE"})
    public String size;

    private Object pojo;
    private Object record;

    private Jsonb champollion;
    private Jsonb yasson;
    private ObjectMapper jackson;
    private com.fasterxml.jackson.jr.ob.JSON jacksonJr;

    @Setup(Level.Trial)
    public void setup() {
        switch (size) {
            case "SMALL" -> {
                pojo = Workloads.smallPojo();
                record = Workloads.smallRecord();
            }
            case "MEDIUM" -> {
                pojo = Workloads.mediumOrder();
                record = pojo; // pas de variant record pour Order
            }
            case "LARGE" -> {
                pojo = Workloads.largeBatch(100);
                record = pojo;
            }
            default -> throw new IllegalArgumentException(size);
        }
        // Force Champollion à être discovered avant Yasson (ServiceLoader).
        // Pour comparer fairement on instancie chaque provider explicitement.
        champollion = JsonbBuilder.newBuilder("io.vidocq.champollion.jsonb.internal.ChampollionJsonbProvider").build();
        yasson = JsonbBuilder.newBuilder("org.eclipse.yasson.JsonBindingProvider").build();
        jackson = new ObjectMapper().registerModule(new ParameterNamesModule());
        jacksonJr = com.fasterxml.jackson.jr.ob.JSON.std;
    }

    @TearDown(Level.Trial)
    public void teardown() throws Exception {
        champollion.close();
        yasson.close();
    }

    @Benchmark public void champollion_pojo(Blackhole bh) { bh.consume(champollion.toJson(pojo)); }
    @Benchmark public void champollion_record(Blackhole bh) { bh.consume(champollion.toJson(record)); }

    @Benchmark public void yasson_pojo(Blackhole bh) { bh.consume(yasson.toJson(pojo)); }
    @Benchmark public void yasson_record(Blackhole bh) { bh.consume(yasson.toJson(record)); }

    @Benchmark public void jackson_pojo(Blackhole bh) throws Exception { bh.consume(jackson.writeValueAsString(pojo)); }
    @Benchmark public void jackson_record(Blackhole bh) throws Exception { bh.consume(jackson.writeValueAsString(record)); }

    @Benchmark public void jacksonJr_pojo(Blackhole bh) throws Exception { bh.consume(jacksonJr.asString(pojo)); }
}
