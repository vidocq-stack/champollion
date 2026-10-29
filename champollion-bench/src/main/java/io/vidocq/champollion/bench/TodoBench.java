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

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import org.openjdk.jmh.annotations.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The JSON microbenchmark of the vidocq-benchmarks bench-json suite (BENCH-20261029-01), reproduced here so a
 * change can be measured before it is published: the Todo payloads through {@code Jsonb} on byte streams, as a
 * REST response and a REST request use it. Writing goes to a fresh {@code ByteArrayOutputStream(256)} followed by
 * {@code toByteArray()}, the list of 100 todos (4 085 bytes) through a {@link ParameterizedType}; reading parses a
 * creation body from a {@code ByteArrayInputStream}. Run with {@code -prof gc} for the bytes per operation.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 2, jvmArgsAppend = {"-Xms1G", "-Xmx1G", "-XX:+UseG1GC"})
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class TodoBench {

    private static final Type TODO_LIST = new ListOf(TodoStaticRecord.class);

    private Jsonb champollion;
    private ObjectMapper jackson;
    private JavaType jacksonTodoList;
    private TodoStaticRecord one;
    private List<TodoStaticRecord> list;
    private byte[] creationBody;

    @Setup(Level.Trial)
    public void setup() {
        champollion = JsonbBuilder.newBuilder("io.vidocq.champollion.jsonb.internal.ChampollionJsonbProvider").build();
        jackson = new ObjectMapper().registerModule(new ParameterNamesModule());
        jacksonTodoList = jackson.getTypeFactory().constructType(TODO_LIST);
        list = new ArrayList<>();
        for (long id = 1; id <= 100; id++) {
            list.add(new TodoStaticRecord(id, "Todo " + id, false));
        }
        list = List.copyOf(list);
        one = list.get(6);
        creationBody = "{\"title\":\"Write the benchmark report\",\"done\":false}".getBytes(StandardCharsets.UTF_8);
    }

    @TearDown(Level.Trial)
    public void teardown() throws Exception {
        champollion.close();
    }

    @Benchmark
    public byte[] champollion_writeOne() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        champollion.toJson(one, TodoStaticRecord.class, out);
        return out.toByteArray();
    }

    @Benchmark
    public byte[] champollion_writeList() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        champollion.toJson(list, TODO_LIST, out);
        return out.toByteArray();
    }

    @Benchmark
    public TodoInputStaticRecord champollion_readCreation() {
        return champollion.fromJson(new ByteArrayInputStream(creationBody), TodoInputStaticRecord.class);
    }

    @Benchmark
    public byte[] jackson_writeOne() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        jackson.writerFor(TodoStaticRecord.class).writeValue(out, one);
        return out.toByteArray();
    }

    @Benchmark
    public byte[] jackson_writeList() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        jackson.writerFor(jacksonTodoList).writeValue(out, list);
        return out.toByteArray();
    }

    @Benchmark
    public TodoInputStaticRecord jackson_readCreation() throws IOException {
        return jackson.readValue(new ByteArrayInputStream(creationBody), TodoInputStaticRecord.class);
    }

    /** {@code List<element>}, as a REST layer builds it from the generic return type of a resource method. */
    private record ListOf(Type element) implements ParameterizedType {
        @Override public Type[] getActualTypeArguments() { return new Type[] {element}; }
        @Override public Type getRawType() { return List.class; }
        @Override public Type getOwnerType() { return null; }
    }
}
