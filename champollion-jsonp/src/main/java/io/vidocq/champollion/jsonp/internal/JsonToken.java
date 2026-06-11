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
 * <p>P12 — plain enum (was a sealed interface with {@code StringToken}/
 * {@code NumberToken} records): scalar tokens no longer carry their value.
 * The tokenizer keeps the last scanned scalar as a <em>lazy pending value</em>
 * (a buffer range or a scratch copy) and materializes it only when
 * {@link JsonTokenizer#currentString()} or a number accessor is actually
 * called. Draining the event stream without reading values allocates
 * nothing per scalar.</p>
 */
public enum JsonToken {
    /** {@code {} */
    START_OBJECT,
    /** {@code }} */
    END_OBJECT,
    /** {@code [} */
    START_ARRAY,
    /** {@code ]} */
    END_ARRAY,
    /** {@code :} */
    NAME_SEPARATOR,
    /** {@code ,} */
    VALUE_SEPARATOR,
    /** {@code true} */
    TRUE,
    /** {@code false} */
    FALSE,
    /** {@code null} */
    NULL,
    /** Decoded JSON string (without quotes, escapes applied). RFC 8259 §7. */
    STRING,
    /** JSON number literal (deferred parsing to {@code BigDecimal} if needed). RFC 8259 §6. */
    NUMBER,
    /** End of stream. */
    EOF
}
