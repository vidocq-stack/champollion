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
package io.vidocq.champollion.bench.rest;

import io.vidocq.champollion.jsonb.internal.ChampollionJsonbBuilder;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The binding generated for a type a REST resource exchanges, against the runtime's reflective mapping of the same
 * type: a plain class with a superclass, a {@code @JsonbDateFormat}, a {@code @JsonbNumberFormat}, an array and a
 * list of records ({@link BenchOrder}), written and read.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(value = 2, jvmArgsAppend = {"-Xms1G", "-Xmx1G", "-XX:+UseG1GC"})
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
public class JsonbRestBindingBench {

    @Param({"1", "20"})
    public int lines;

    private BenchOrder order;
    private BenchPriced priced;
    private String json;
    private Jsonb generated;
    private Jsonb reflective;

    @Setup(Level.Trial)
    public void setup() {
        order = BenchOrder.sample(lines);
        priced = BenchPriced.sample(lines);
        generated = JsonbBuilder.newBuilder("io.vidocq.champollion.jsonb.internal.ChampollionJsonbProvider").build();
        reflective = ((ChampollionJsonbBuilder) JsonbBuilder
                .newBuilder("io.vidocq.champollion.jsonb.internal.ChampollionJsonbProvider"))
                .withStaticBindings(List.of())
                .build();
        json = generated.toJson(order);
        if (!json.equals(reflective.toJson(order))) {
            throw new IllegalStateException("the generated binding and the runtime differ: " + json);
        }
    }

    @TearDown(Level.Trial)
    public void teardown() throws Exception {
        generated.close();
        reflective.close();
    }

    @Benchmark public void write_generated(Blackhole bh)  { bh.consume(generated.toJson(order)); }
    @Benchmark public void write_reflective(Blackhole bh) { bh.consume(reflective.toJson(order)); }
    @Benchmark public void read_generated(Blackhole bh)   { bh.consume(generated.fromJson(json, BenchOrder.class)); }
    @Benchmark public void write_generated_adapters(Blackhole bh) { bh.consume(generated.toJson(priced)); }
    @Benchmark public void read_reflective(Blackhole bh)  { bh.consume(reflective.fromJson(json, BenchOrder.class)); }
}
