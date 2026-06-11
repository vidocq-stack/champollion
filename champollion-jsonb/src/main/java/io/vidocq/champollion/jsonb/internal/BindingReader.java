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
package io.vidocq.champollion.jsonb.internal;

import jakarta.json.stream.JsonParser;

/**
 * Reads a Java value from a {@link JsonParser}. On entry, the parser is positioned
 * <em>just before</em> the event corresponding to the value (the reader calls
 * {@code next()} itself to consume the leading event).
 */
@FunctionalInterface
interface BindingReader {
    Object read(JsonParser p);

    /**
     * Marker for readers backed by a user-provided
     * {@link jakarta.json.bind.serializer.JsonbDeserializer}: unlike internal
     * readers (which consume exactly their value), a user deserializer may
     * legitimately consume events up to and including the enclosing object's
     * {@code END_OBJECT} (TCK-sanctioned pattern). {@code readObjectAndApply}
     * applies its early-exit escape hatch only to these readers — applying it
     * to internal readers was BUG-20260611-01 (a nested-POJO member's own
     * {@code END_OBJECT} aborted the enclosing object read).
     */
    @FunctionalInterface
    interface UserDeserializer extends BindingReader {}
}
