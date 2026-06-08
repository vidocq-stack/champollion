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

import java.io.IOException;
import java.io.InputStream;

/**
 * Deserializes Protocol Buffers bytes into an instance of {@code T}.
 *
 * <p>Implementations are obtained via {@link Protobuf#parser(Class)} (reflective
 * runtime resolution, cached by {@link Class}) or via the static codegen
 * {@code champollion-protobuf-codegen} (loaded via {@link java.util.ServiceLoader}).</p>
 */
public interface Parser<T> {

    T parseFrom(CodedInputStream in) throws IOException;

    default T parseFrom(byte[] data) throws IOException {
        return parseFrom(CodedInputStream.newInstance(data));
    }

    default T parseFrom(byte[] data, int offset, int length) throws IOException {
        return parseFrom(CodedInputStream.newInstance(data, offset, length));
    }

    default T parseFrom(InputStream in) throws IOException {
        return parseFrom(CodedInputStream.newInstance(in));
    }
}
