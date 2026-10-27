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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.champollion.protobuf.wkt.Any;
import io.vidocq.champollion.protobuf.wkt.Wrappers.BytesValue;
import org.junit.jupiter.api.Test;

/** The public messages holding a {@code bytes} field compare, hash and print the bytes, not the array. */
class ByteArrayValueSemanticsTest {

    @Test
    void bytes_values_with_the_same_content_are_equal() {
        BytesValue a = new BytesValue(new byte[] {1, 2, 3});
        BytesValue b = new BytesValue(new byte[] {1, 2, 3});

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, new BytesValue(new byte[] {1, 2}));
        assertNotEquals(a, null);
        assertEquals(a, a);
    }

    @Test
    void bytes_value_prints_its_content() {
        assertEquals("BytesValue[value=[1, 2, 3]]", new BytesValue(new byte[] {1, 2, 3}).toString());
    }

    @Test
    void any_prints_its_content() {
        String text = new Any("type.googleapis.com/x.Y", new byte[] {4, 5}).toString();

        assertTrue(text.contains("type.googleapis.com/x.Y"), text);
        assertTrue(text.contains("[4, 5]"), text);
    }
}
