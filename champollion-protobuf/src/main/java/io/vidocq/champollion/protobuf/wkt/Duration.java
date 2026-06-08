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

/**
 * {@code google.protobuf.Duration} — signed duration.
 *
 * <p>Spec: <a href="https://protobuf.dev/reference/protobuf/google.protobuf/#duration">Duration</a>.
 * Canonical JSON: {@code "3.5s"} (optional fractional seconds, mandatory
 * {@code s} suffix). Both components must have the same sign.</p>
 */
@ProtobufMessage("google.protobuf.Duration")
public record Duration(
        @ProtobufField(number = 1, type = FieldType.INT64) long seconds,
        @ProtobufField(number = 2, type = FieldType.INT32) int nanos
) implements Message {

    public static Duration ofSeconds(long seconds) {
        return new Duration(seconds, 0);
    }

    public static Duration of(long seconds, int nanos) {
        return new Duration(seconds, nanos);
    }

    public static Duration from(java.time.Duration jd) {
        return new Duration(jd.getSeconds(), jd.getNano());
    }

    public java.time.Duration toJavaDuration() {
        return java.time.Duration.ofSeconds(seconds, nanos);
    }
}
