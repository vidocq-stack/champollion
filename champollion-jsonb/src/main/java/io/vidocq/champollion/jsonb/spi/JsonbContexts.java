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
package io.vidocq.champollion.jsonb.spi;

import io.vidocq.champollion.jsonb.internal.LeafCodecs;
import jakarta.json.bind.serializer.DeserializationContext;
import jakarta.json.bind.serializer.SerializationContext;

/**
 * The context a generated {@link JsonbBinding} gives the {@code JsonbSerializer}s and {@code JsonbDeserializer}s it
 * calls: the one of the {@code Jsonb} that called the binding, or, when the binding is called without one, the
 * context of a {@code Jsonb} of the default configuration (the only one generated bindings serve).
 */
public final class JsonbContexts {

    private JsonbContexts() {
    }

    /** {@code given}, or the serialization context of a default {@code Jsonb} when it is {@code null}. */
    public static SerializationContext serialization(SerializationContext given) {
        return given != null ? given : LeafCodecs.defaultSerializationContext();
    }

    /** {@code given}, or the deserialization context of a default {@code Jsonb} when it is {@code null}. */
    public static DeserializationContext deserialization(DeserializationContext given) {
        return given != null ? given : LeafCodecs.defaultDeserializationContext();
    }
}
