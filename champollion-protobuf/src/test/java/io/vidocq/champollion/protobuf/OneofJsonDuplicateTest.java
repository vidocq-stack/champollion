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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.UncheckedIOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests M5.6 — {@code @ProtobufField(oneofGroup="...")} : le parser JSON rejette
 * deux property keys du même groupe oneof.
 *
 * <p>Spec : <a href="https://protobuf.dev/programming-guides/proto3/#oneof">
 * Proto3 oneof</a>, <a href="https://protobuf.dev/programming-guides/json/">
 * Proto3 JSON canonical §oneof</a>.</p>
 */
class OneofJsonDuplicateTest {

    @ProtobufMessage
    public record OneofHolder(
            @ProtobufField(number = 1, type = FieldType.UINT32,
                           explicitPresence = true, oneofGroup = "choice")
            Integer choiceUint32,
            @ProtobufField(number = 2, type = FieldType.STRING,
                           explicitPresence = true, oneofGroup = "choice")
            String choiceString,
            @ProtobufField(number = 3, type = FieldType.BOOL,
                           explicitPresence = true, oneofGroup = "choice")
            Boolean choiceBool,
            @ProtobufField(number = 4, type = FieldType.STRING)
            String notInOneof) {}

    @Test
    @DisplayName("Single oneof member → OK")
    void singleMemberAccepted() {
        OneofHolder h = ProtobufJson.fromJson(OneofHolder.class, "{\"choiceString\":\"x\"}");
        assertEquals("x", h.choiceString());
        assertNull(h.choiceUint32());
        assertNull(h.choiceBool());
    }

    @Test
    @DisplayName("Two members same oneof → parse_error")
    void twoMembersRejected() {
        UncheckedIOException ex = assertThrows(UncheckedIOException.class,
                () -> ProtobufJson.fromJson(OneofHolder.class,
                        "{\"choiceString\":\"x\",\"choiceUint32\":42}"));
        assertEquals(true, ex.getCause().getMessage().contains("oneof"),
                "expected 'oneof' in: " + ex.getCause().getMessage());
    }

    @Test
    @DisplayName("Three members same oneof → parse_error (sur la 2e)")
    void threeMembersRejected() {
        assertThrows(UncheckedIOException.class,
                () -> ProtobufJson.fromJson(OneofHolder.class,
                        "{\"choiceBool\":true,\"choiceString\":\"x\",\"choiceUint32\":42}"));
    }

    @Test
    @DisplayName("null value JSON ne compte pas dans le tracker")
    void nullValueDoesNotCount() {
        // choiceUint32:null laisse le slot null, et choiceString=x devient le seul présent.
        OneofHolder h = ProtobufJson.fromJson(OneofHolder.class,
                "{\"choiceUint32\":null,\"choiceString\":\"x\"}");
        assertEquals("x", h.choiceString());
        assertNull(h.choiceUint32());
    }

    @Test
    @DisplayName("Champ hors oneof ne déclenche pas de duplicate")
    void nonOneofFieldNotTracked() {
        OneofHolder h = ProtobufJson.fromJson(OneofHolder.class,
                "{\"notInOneof\":\"hello\",\"choiceString\":\"x\"}");
        assertEquals("hello", h.notInOneof());
        assertEquals("x", h.choiceString());
    }
}
