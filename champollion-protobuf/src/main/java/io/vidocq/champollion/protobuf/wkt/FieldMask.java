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

import java.util.List;

/**
 * {@code google.protobuf.FieldMask} — projection over a subset of fields.
 *
 * <p>Spec: <a href="https://protobuf.dev/reference/protobuf/google.protobuf/#field-mask">FieldMask</a>.
 * Canonical JSON: a single comma-separated string with paths converted
 * to {@code lowerCamelCase}, e.g. {@code "user.firstName,user.address.zipCode"}.</p>
 */
@ProtobufMessage("google.protobuf.FieldMask")
public record FieldMask(
        @ProtobufField(number = 1, type = FieldType.STRING) List<String> paths
) implements Message {

    public FieldMask {
        paths = List.copyOf(paths);
    }

    public static FieldMask of(String... paths) {
        return new FieldMask(List.of(paths));
    }
}
