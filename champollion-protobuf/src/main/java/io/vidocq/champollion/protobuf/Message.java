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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;

/**
 * Marker for types serializable as Protocol Buffers.
 *
 * <p>The default methods delegate to {@link Protobuf}, which resolves
 * (via a concurrent cache) a {@code BindingPlan} through reflection on the
 * {@link ProtobufField} annotations of the record components.</p>
 *
 * <p>For records annotated {@link ProtobufMessage}, implementing {@code Message}
 * is not mandatory — {@link Protobuf#toByteArray(Object)} also works on records
 * that do not implement it — but this is the recommended idiom to benefit from
 * the default methods.</p>
 */
public interface Message {

    default byte[] toByteArray() {
        return Protobuf.toByteArray(this);
    }

    default int getSerializedSize() {
        return Protobuf.getSerializedSize(this);
    }

    default void writeTo(OutputStream out) throws IOException {
        Protobuf.writeTo(this, out);
    }

    default void writeTo(CodedOutputStream out) throws IOException {
        Protobuf.writeTo(this, out);
    }

    @SuppressWarnings("unchecked")
    static <T> T parseFrom(Class<T> type, byte[] data) {
        try {
            return (T) Protobuf.parser(type).parseFrom(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
