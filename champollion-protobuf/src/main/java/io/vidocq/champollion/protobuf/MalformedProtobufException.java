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

/**
 * Thrown when the binary stream does not respect the Protocol Buffers wire format
 * (varint > 10 bytes, unknown wire type, negative LEN length, truncation, etc.).
 *
 * <p>Spec : <a href="https://protobuf.dev/programming-guides/encoding/">Protocol Buffers Encoding</a>.</p>
 */
public final class MalformedProtobufException extends IOException {

    public MalformedProtobufException(String message) {
        super(message);
    }

    public MalformedProtobufException(String message, Throwable cause) {
        super(message, cause);
    }

    public static MalformedProtobufException truncated() {
        return new MalformedProtobufException(
                "While parsing a protocol message, the input ended unexpectedly "
                        + "in the middle of a field. This could mean either that the input "
                        + "has been truncated or that an embedded message misreported its own length.");
    }

    public static MalformedProtobufException malformedVarint() {
        return new MalformedProtobufException(
                "CodedInputStream encountered a malformed varint (more than 10 bytes).");
    }

    public static MalformedProtobufException negativeSize() {
        return new MalformedProtobufException(
                "CodedInputStream encountered an embedded string or message which claimed to have negative size.");
    }

    public static MalformedProtobufException invalidWireType(int wireType) {
        return new MalformedProtobufException(
                "Protocol message contained an invalid wire type: " + wireType);
    }

    public static MalformedProtobufException invalidUtf8Encode(Throwable cause) {
        return new MalformedProtobufException(
                "Cannot encode string field as valid UTF-8 (unpaired UTF-16 surrogate, "
                        + "features.utf8_validation = VERIFY).",
                cause);
    }

    public static MalformedProtobufException invalidUtf8(Throwable cause) {
        return new MalformedProtobufException(
                "Protocol message contained a string field with malformed UTF-8 "
                        + "(features.utf8_validation = VERIFY).",
                cause);
    }

    public static MalformedProtobufException recursionLimitExceeded() {
        return new MalformedProtobufException(
                "Protocol message had too many levels of nesting. May be malicious. "
                        + "Use CodedInputStream.setRecursionLimit() to increase the depth limit.");
    }
}
