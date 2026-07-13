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
package io.vidocq.champollion.protobuf;

import io.vidocq.champollion.protobuf.internal.RuntimeBinding;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * Entry point for Protocol Buffers serialization and deserialization.
 *
 * <p>Stable, Java Modules-friendly API. Runtime implementations are provided by
 * {@link RuntimeBinding} (reflective introspection + cache by {@link Class}).
 * The static mode (codegen via {@code champollion-protobuf-codegen}) will be
 * exposed via {@link java.util.ServiceLoader} in M3.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * @ProtobufMessage
 * record Person(@ProtobufField(number = 1, type = FieldType.STRING) String name,
 *               @ProtobufField(number = 2, type = FieldType.INT32) int age) {}
 *
 * byte[] bytes = Protobuf.toByteArray(new Person("alice", 30));
 * Person back  = Protobuf.parser(Person.class).parseFrom(bytes);
 * }</pre>
 */
public final class Protobuf {

    private Protobuf() {}

    public static <T> Parser<T> parser(Class<T> type) {
        Objects.requireNonNull(type, "type");
        // Preferred static mode: a ParserProvider loaded by ServiceLoader
        // (produced by the {@code @ProtobufStatic} APT) can provide a parser without
        // reflection, AOT-compatible.
        for (ParserProvider p : ServiceLoader.load(ParserProvider.class)) {
            Parser<T> candidate = p.parserFor(type);
            if (candidate != null) return candidate;
        }
        return RuntimeBinding.parser(type);
    }

    /**
     * Forces the reflective runtime resolver ({@code MethodHandles}), even if a
     * static {@code ParserProvider} is available via {@link ServiceLoader}.
     * Useful for differential benchmarking (M3.3) or diagnostic tools.
     */
    public static <T> Parser<T> runtimeParser(Class<T> type) {
        Objects.requireNonNull(type, "type");
        return RuntimeBinding.parser(type);
    }

    public static byte[] toByteArray(Object message) {
        Objects.requireNonNull(message, "message");
        try {
            int size = getSerializedSize(message);
            byte[] buffer = new byte[size];
            CodedOutputStream out = CodedOutputStream.newInstance(buffer);
            RuntimeBinding.writeTo(message, out);
            out.flush();
            return buffer;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to serialize " + message.getClass(), e);
        }
    }

    public static void writeTo(Object message, OutputStream out) throws IOException {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(out, "out");
        CodedOutputStream cos = CodedOutputStream.newInstance(out);
        RuntimeBinding.writeTo(message, cos);
        cos.flush();
    }

    public static void writeTo(Object message, CodedOutputStream out) throws IOException {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(out, "out");
        RuntimeBinding.writeTo(message, out);
    }

    public static int getSerializedSize(Object message) {
        Objects.requireNonNull(message, "message");
        return RuntimeBinding.getSerializedSize(message);
    }
}
