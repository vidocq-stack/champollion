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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests M5.9 — {@code FieldType.MAP} wire format.
 *
 * <p>Spec : <a href="https://protobuf.dev/programming-guides/encoding/#maps">
 * Encoding §maps</a> — chaque entrée est un message {@code length-delimited}
 * avec key (tag 1) et value (tag 2).</p>
 */
class MapWireFormatTest {

    @ProtobufMessage
    public record MapStringInt(
            @ProtobufField(number = 1, type = FieldType.MAP,
                           mapKey = FieldType.STRING, mapValue = FieldType.INT32)
            Map<String, Integer> entries) {}

    @ProtobufMessage
    public record MapIntInt(
            @ProtobufField(number = 1, type = FieldType.MAP,
                           mapKey = FieldType.INT32, mapValue = FieldType.INT32)
            Map<Integer, Integer> entries) {}

    @Nested
    @DisplayName("Wire format binaire round-trip")
    class Wire {

        @Test
        @DisplayName("map<string,int32> — 1 entrée")
        void singleEntryStringInt() throws Exception {
            Map<String, Integer> m = new LinkedHashMap<>();
            m.put("k", 42);
            byte[] bytes = Protobuf.toByteArray(new MapStringInt(m));
            assertNotNull(bytes);
            MapStringInt back = Protobuf.parser(MapStringInt.class).parseFrom(bytes);
            assertEquals(1, back.entries().size());
            assertEquals(42, back.entries().get("k"));
        }

        @Test
        @DisplayName("map<int32,int32> — multiple entrées")
        void multipleEntriesIntInt() throws Exception {
            Map<Integer, Integer> m = new LinkedHashMap<>();
            m.put(1, 100);
            m.put(2, 200);
            m.put(3, 300);
            byte[] bytes = Protobuf.toByteArray(new MapIntInt(m));
            MapIntInt back = Protobuf.parser(MapIntInt.class).parseFrom(bytes);
            assertEquals(3, back.entries().size());
            assertEquals(100, back.entries().get(1));
            assertEquals(200, back.entries().get(2));
            assertEquals(300, back.entries().get(3));
        }

        @Test
        @DisplayName("map vide → wire vide (omitted)")
        void emptyMapOmitted() {
            byte[] bytes = Protobuf.toByteArray(new MapStringInt(Map.of()));
            assertArrayEquals(new byte[0], bytes);
        }

        @Test
        @DisplayName("map duplicate key → last wins")
        void duplicateKeyLastWins() throws Exception {
            // Forge un wire avec deux entries pour la même key "k" :
            // tag=0x0A, len=5, entry { tag=0x0A len=1 'k' tag=0x10 1 } (key=k, val=1)
            // tag=0x0A, len=5, entry { tag=0x0A len=1 'k' tag=0x10 2 } (key=k, val=2)
            byte[] wire = new byte[] {
                    0x0A, 0x05, 0x0A, 0x01, 'k', 0x10, 0x01,
                    0x0A, 0x05, 0x0A, 0x01, 'k', 0x10, 0x02
            };
            MapStringInt back = Protobuf.parser(MapStringInt.class).parseFrom(wire);
            assertEquals(1, back.entries().size());
            assertEquals(2, back.entries().get("k"), "last wins");
        }
    }
}
