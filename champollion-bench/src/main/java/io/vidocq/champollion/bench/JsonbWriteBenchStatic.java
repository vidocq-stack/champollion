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
import io.vidocq.champollion.jsonb.internal.ChampollionJsonbBuilder;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark write — <strong>static codegen mode</strong> (M5).
 *
 * <p>Compare 4 paths on records annotated {@code @JsonbStatic} :</p>
 * <ul>
 *   <li>{@code champollion_static} — APT binding discovered by ServiceLoader, zero
 *       reflection, direct branch {@code JsonbBinding.write(g, value)} ;</li>
 *   <li>{@code champollion_runtime} — same Jsonb but {@code withStaticBindings(List.of())}
 *       forces introspective fallback (MethodHandles + cache) ;</li>
 *   <li>{@code yasson} / {@code jackson} / {@code jacksonJr} — competitors.</li>
 * </ul>
 *
 * <p>The APT {@code champollion-codegen-apt} is wired in
 * {@code annotationProcessorPaths} in the pom of the bench module : at compile time,
 * a {@code <Type>$$Binding} is generated for each record annotated here, and
 * referenced in {@code META-INF/services/io.vidocq.champollion.jsonb.spi.JsonbBinding}.</p>
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
        // ServiceLoader active → @JsonbStatic bindings discovered.
        champollionStatic = JsonbBuilder
                .newBuilder("io.vidocq.champollion.jsonb.internal.ChampollionJsonbProvider")
                .build();

        // ServiceLoader bypassed → static bindings explicitly empty.
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
