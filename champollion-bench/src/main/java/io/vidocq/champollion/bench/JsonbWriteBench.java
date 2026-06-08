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
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Benchmark write : POJO/record → JSON string.
 *
 * <p>4 implementations : Champollion, Yasson, Jackson databind, Jackson-jr.</p>
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
                record = pojo; // no record variant for Order
            }
            case "LARGE" -> {
                pojo = Workloads.largeBatch(100);
                record = pojo;
            }
            default -> throw new IllegalArgumentException(size);
        }
        // Force Champollion to be discovered before Yasson (ServiceLoader).
        // To compare fairly we instantiate each provider explicitly.
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
