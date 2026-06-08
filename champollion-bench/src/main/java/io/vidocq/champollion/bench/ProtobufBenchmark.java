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

import io.vidocq.champollion.protobuf.Parser;
import io.vidocq.champollion.protobuf.Protobuf;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Bench JMH différentiel runtime reflectif (M1.3) vs parser statique (M3.1).
 *
 * <p>Le record {@link BenchPerson} est annoté {@code @ProtobufStatic} : l'APT
 * {@code ProtobufStaticProcessor} génère un {@code BenchPerson$$Parser}
 * ServiceLoader-discoverable. {@link Protobuf#parser(Class)} le résout en
 * premier (mode statique), sinon tombe sur {@link RuntimeBinding} (réflexion
 * via MethodHandles).</p>
 *
 * <p>Le benchmark force les deux modes en utilisant les deux entrypoints :</p>
 * <ul>
 *   <li>{@link Protobuf#parser(Class)} → ServiceLoader-first → static</li>
 *   <li>{@link Protobuf#runtimeParser(Class)} → force le runtime reflectif
 *       (bypass du ServiceLoader)</li>
 * </ul>
 *
 * <p>Run : {@code java -jar target/benchmarks.jar ProtobufBenchmark}</p>
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class ProtobufBenchmark {

    private byte[] encoded;
    private BenchPerson sample;
    private Parser<BenchPerson> staticParser;
    private Parser<BenchPerson> runtimeParser;

    @Setup
    public void setUp() {
        sample = new BenchPerson(
                "alice the analyst",
                42,
                List.of("dev", "ops", "platform"),
                true,
                123456789012345L);
        encoded = Protobuf.toByteArray(sample);
        staticParser = Protobuf.parser(BenchPerson.class);          // ServiceLoader → APT
        runtimeParser = Protobuf.runtimeParser(BenchPerson.class);  // reflectif M1.3
    }

    @Benchmark
    public BenchPerson parse_static() throws Exception {
        return staticParser.parseFrom(encoded);
    }

    @Benchmark
    public BenchPerson parse_runtime() throws Exception {
        return runtimeParser.parseFrom(encoded);
    }

    @Benchmark
    public byte[] serialize() {
        return Protobuf.toByteArray(sample);
    }
}
