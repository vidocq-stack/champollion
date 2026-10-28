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

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BUG-20261028-02: an integer beyond the {@code long} range, read where the target type is unknown ({@code Object},
 * a raw collection or map), failed with an {@link ArithmeticException} instead of giving a {@link BigInteger}.
 */
@DisplayName("BUG-20261028-02 — untyped integer beyond the long range")
class UntypedBigIntegerRegressionTest {

    private static final String HUGE = "123456789012345678901234567890";

    private static Jsonb jsonb;

    @BeforeAll
    static void open() {
        jsonb = JsonbBuilder.create();
    }

    @AfterAll
    static void close() throws Exception {
        jsonb.close();
    }

    @Test
    void a_huge_integer_reads_as_a_big_integer() {
        assertEquals(new BigInteger(HUGE), jsonb.fromJson(HUGE, Object.class));
        assertEquals(new BigInteger("-" + HUGE), jsonb.fromJson("-" + HUGE, Object.class));
    }

    @Test
    void a_huge_integer_inside_untyped_containers() {
        assertEquals(List.of(new BigInteger(HUGE)), jsonb.fromJson("[" + HUGE + "]", Object.class));
        assertEquals(Map.of("n", new BigInteger(HUGE)), jsonb.fromJson("{\"n\":" + HUGE + "}", Object.class));
    }

    @Test
    void integers_keep_their_types_at_the_int_boundaries() {
        assertEquals(Integer.MAX_VALUE, jsonb.fromJson(String.valueOf(Integer.MAX_VALUE), Object.class));
        assertEquals(Integer.MIN_VALUE, jsonb.fromJson(String.valueOf(Integer.MIN_VALUE), Object.class));
        assertEquals(BigInteger.valueOf(Integer.MAX_VALUE + 1L),
                jsonb.fromJson(String.valueOf(Integer.MAX_VALUE + 1L), Object.class));
    }
}
