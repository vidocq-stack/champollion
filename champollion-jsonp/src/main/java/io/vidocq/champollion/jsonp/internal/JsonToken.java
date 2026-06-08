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
package io.vidocq.champollion.jsonp.internal;

/**
 * Elementary tokens recognized by the RFC 8259 JSON scanner.
 *
 * <p>Sealed hierarchy for exhaustive matching in higher layers.</p>
 */
public sealed interface JsonToken {

    /** {@code {} */
    enum StartObject implements JsonToken { INSTANCE }

    /** {@code }} */
    enum EndObject implements JsonToken { INSTANCE }

    /** {@code [} */
    enum StartArray implements JsonToken { INSTANCE }

    /** {@code ]} */
    enum EndArray implements JsonToken { INSTANCE }

    /** {@code :} */
    enum NameSeparator implements JsonToken { INSTANCE }

    /** {@code ,} */
    enum ValueSeparator implements JsonToken { INSTANCE }

    /** {@code true} */
    enum True implements JsonToken { INSTANCE }

    /** {@code false} */
    enum False implements JsonToken { INSTANCE }

    /** {@code null} */
    enum Null implements JsonToken { INSTANCE }

    /** End of stream. */
    enum Eof implements JsonToken { INSTANCE }

    /** Decoded JSON string (without quotes, escapes applied). RFC 8259 §7. */
    record StringToken(String value) implements JsonToken {}

    /** JSON number as text (deferred parsing to {@code BigDecimal} if needed). RFC 8259 §6. */
    record NumberToken(String literal) implements JsonToken {}
}
