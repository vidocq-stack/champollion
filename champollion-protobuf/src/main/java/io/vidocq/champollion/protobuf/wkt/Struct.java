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

import java.util.Map;

/**
 * {@code google.protobuf.Struct} — dynamically typed JSON object.
 *
 * <p>Spec: <a href="https://protobuf.dev/reference/protobuf/google.protobuf/#struct">
 * Struct</a> — equivalent to a JSON object via {@code map<string, Value>}.</p>
 */
@ProtobufMessage("google.protobuf.Struct")
public record Struct(
        @ProtobufField(number = 1, type = FieldType.MAP,
                       mapKey = FieldType.STRING, mapValue = FieldType.MESSAGE)
        Map<String, Value> fields
) implements Message {

    public static final Struct EMPTY = new Struct(Map.of());
}
