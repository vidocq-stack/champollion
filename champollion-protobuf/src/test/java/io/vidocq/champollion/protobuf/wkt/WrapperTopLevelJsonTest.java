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

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.vidocq.champollion.protobuf.ProtobufJson;
import org.junit.jupiter.api.Test;

/** A wrapper written as the whole JSON document is its bare value (proto3 JSON mapping). */
class WrapperTopLevelJsonTest {

    @Test
    void wrappers_are_written_as_their_value() {
        assertEquals("1.5", ProtobufJson.toJson(new Wrappers.DoubleValue(1.5)));
        assertEquals("2.5", ProtobufJson.toJson(new Wrappers.FloatValue(2.5f)));
        assertEquals("\"7\"", ProtobufJson.toJson(new Wrappers.Int64Value(7L)));
        assertEquals("\"8\"", ProtobufJson.toJson(new Wrappers.UInt64Value(8L)));
        assertEquals("9", ProtobufJson.toJson(new Wrappers.Int32Value(9)));
        assertEquals("10", ProtobufJson.toJson(new Wrappers.UInt32Value(10)));
        assertEquals("true", ProtobufJson.toJson(new Wrappers.BoolValue(true)));
        assertEquals("\"a\"", ProtobufJson.toJson(new Wrappers.StringValue("a")));
        assertEquals("\"AQI=\"", ProtobufJson.toJson(new Wrappers.BytesValue(new byte[] {1, 2})));
    }
}
