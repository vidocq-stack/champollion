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
package io.vidocq.champollion.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RawJsonKeyWriter — default writeKeyRawWithColon")
class RawJsonKeyWriterTest {

    /** Records the keys handed to {@link RawJsonKeyWriter#writeKeyRaw}. */
    private static final class Recorder implements RawJsonKeyWriter {
        final List<String> keys = new ArrayList<>();

        @Override
        public void writeKeyRaw(String preQuotedKey) {
            keys.add(preQuotedKey);
        }
    }

    @Test
    void strips_the_colon_and_delegates() {
        Recorder r = new Recorder();
        r.writeKeyRawWithColon("\"name\":");
        assertEquals(List.of("\"name\""), r.keys);
    }

    @Test
    void rejects_a_fragment_without_a_trailing_colon() {
        Recorder r = new Recorder();
        assertThrows(IllegalArgumentException.class, () -> r.writeKeyRawWithColon("\"name\""));
        assertThrows(IllegalArgumentException.class, () -> r.writeKeyRawWithColon(""));
        assertTrue(r.keys.isEmpty());
    }
}
