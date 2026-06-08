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
package io.vidocq.champollion.protobuf.wkt;

import io.vidocq.champollion.protobuf.FieldType;
import io.vidocq.champollion.protobuf.Message;
import io.vidocq.champollion.protobuf.ProtobufField;
import io.vidocq.champollion.protobuf.ProtobufMessage;

import java.time.DateTimeException;
import java.time.Instant;

/**
 * {@code google.protobuf.Timestamp} — point dans le temps UTC depuis 1970-01-01.
 *
 * <p>Spec : <a href="https://protobuf.dev/reference/protobuf/google.protobuf/#timestamp">Timestamp</a>.
 * JSON canonical : <a href="https://datatracker.ietf.org/doc/html/rfc3339">RFC 3339</a>
 * avec suffixe {@code Z}, ex. {@code "2026-05-21T10:00:00.123456789Z"}.</p>
 */
@ProtobufMessage("google.protobuf.Timestamp")
public record Timestamp(
        @ProtobufField(number = 1, type = FieldType.INT64) long seconds,
        @ProtobufField(number = 2, type = FieldType.INT32) int nanos
) implements Message {

    public static Timestamp ofEpochSecond(long seconds, int nanos) {
        return new Timestamp(seconds, nanos);
    }

    public static Timestamp from(Instant instant) {
        return new Timestamp(instant.getEpochSecond(), instant.getNano());
    }

    public Instant toInstant() {
        try {
            return Instant.ofEpochSecond(seconds, nanos);
        } catch (DateTimeException | ArithmeticException e) {
            throw new IllegalStateException("Invalid Timestamp(" + seconds + ", " + nanos + ")", e);
        }
    }
}
