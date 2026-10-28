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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A map value of enum type is encoded and decoded by the enum's {@link ProtoEnumValue} numbers (BUG-20261027-01). */
class MapEnumValueTest {

    public enum Status {
        @ProtoEnumValue(0) NONE,
        @ProtoEnumValue(-1) UNKNOWN,
        @ProtoEnumValue(10) ACTIVE
    }

    @ProtobufMessage
    public record Statuses(
            @ProtobufField(number = 1, type = FieldType.MAP, mapKey = FieldType.STRING, mapValue = FieldType.ENUM)
            Map<String, Status> byName
    ) implements Message {}

    @Test
    void declared_numbers_round_trip() throws IOException {
        Statuses s = new Statuses(Map.of("a", Status.UNKNOWN, "b", Status.ACTIVE, "c", Status.NONE));
        Statuses back = Protobuf.parser(Statuses.class).parseFrom(Protobuf.toByteArray(s));
        assertEquals(s.byName(), back.byName());
    }

    @Test
    void a_number_no_constant_declares_reads_as_null() throws IOException {
        // map entry: key "a", value 1 (the ordinal of UNKNOWN, but no constant declares the number 1)
        byte[] bytes = {0x0A, 0x05, 0x0A, 0x01, 'a', 0x10, 0x01};
        Statuses back = Protobuf.parser(Statuses.class).parseFrom(bytes);
        assertNull(back.byName().get("a"));
    }
}
