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

import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbException;
import jakarta.json.bind.annotation.JsonbCreator;
import jakarta.json.bind.annotation.JsonbProperty;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** A JSON value a {@code @JsonbCreator} cannot take is a {@code JsonbException}, as for any property (BUG-20261101-08). */
class CreatorParametersTest {

    public static class Account {
        private final long number;

        @JsonbCreator
        public Account(@JsonbProperty("number") long number) {
            this.number = number;
        }

        public long getNumber() { return number; }
    }

    @Test
    void aNullForAPrimitiveParameterIsAJsonbException() throws Exception {
        try (var jsonb = JsonbBuilder.create()) {
            assertThrows(JsonbException.class, () -> jsonb.fromJson("{\"number\":null}", Account.class));
        }
    }
}
