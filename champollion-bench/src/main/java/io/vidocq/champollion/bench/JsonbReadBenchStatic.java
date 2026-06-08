/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
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
 * Benchmark read — static codegen mode (M5).
 *
 * <p>Already serialized JSON is passed to 5 implementations which bounce it back to
 * {@code OrderStaticRecord} / {@code SmallStaticRecord} / {@code List<OrderStaticRecord>}.</p>
 *
 * <p>{@code champollion_static} benefits from APT binding : no cache lookup,
 * no MethodHandles, direct switch on keys known at compile time.</p>
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
        // Serialize once with champollion (light pre-init) for payloads.
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
