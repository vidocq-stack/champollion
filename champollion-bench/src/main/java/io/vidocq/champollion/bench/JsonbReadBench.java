package io.vidocq.champollion.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark read : JSON string → POJO/record.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(value = 2, jvmArgsAppend = {"-Xms1G", "-Xmx1G", "-XX:+UseG1GC"})
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
public class JsonbReadBench {

    @Param({"SMALL", "MEDIUM", "LARGE"})
    public String size;

    private String json;
    private Class<?> pojoType;
    private Class<?> recordType;
    private java.lang.reflect.Type largeType;

    private Jsonb champollion;
    private Jsonb yasson;
    private ObjectMapper jackson;
    private com.fasterxml.jackson.jr.ob.JSON jacksonJr;

    @Setup(Level.Trial)
    public void setup() {
        switch (size) {
            case "SMALL" -> {
                json = Workloads.smallJson();
                pojoType = Workloads.SmallPojo.class;
                recordType = Workloads.SmallRecord.class;
            }
            case "MEDIUM" -> {
                json = Workloads.mediumJson();
                pojoType = Workloads.Order.class;
                recordType = Workloads.Order.class;
            }
            case "LARGE" -> {
                json = Workloads.largeJson(100);
                pojoType = List.class;
                recordType = List.class;
                largeType = new com.fasterxml.jackson.core.type.TypeReference<List<Workloads.Order>>(){}.getType();
            }
            default -> throw new IllegalArgumentException(size);
        }
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

    @Benchmark public void champollion_pojo(Blackhole bh) {
        bh.consume(largeType != null ? champollion.fromJson(json, largeType) : champollion.fromJson(json, pojoType));
    }

    @Benchmark public void champollion_record(Blackhole bh) {
        if (size.equals("LARGE")) bh.consume(champollion.fromJson(json, largeType));
        else bh.consume(champollion.fromJson(json, recordType));
    }

    @Benchmark public void yasson_pojo(Blackhole bh) {
        bh.consume(largeType != null ? yasson.fromJson(json, largeType) : yasson.fromJson(json, pojoType));
    }

    @Benchmark public void jackson_pojo(Blackhole bh) throws Exception {
        if (largeType != null) bh.consume(jackson.readValue(json, jackson.getTypeFactory().constructType(largeType)));
        else bh.consume(jackson.readValue(json, pojoType));
    }

    @Benchmark public void jackson_record(Blackhole bh) throws Exception {
        if (largeType != null) bh.consume(jackson.readValue(json, jackson.getTypeFactory().constructType(largeType)));
        else bh.consume(jackson.readValue(json, recordType));
    }

    @Benchmark public void jacksonJr_pojo(Blackhole bh) throws Exception {
        if (size.equals("LARGE")) bh.consume(jacksonJr.listFrom(json));
        else bh.consume(jacksonJr.beanFrom(pojoType, json));
    }
}
