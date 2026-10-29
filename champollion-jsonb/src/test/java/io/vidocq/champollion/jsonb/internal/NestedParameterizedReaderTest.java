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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Resolving the reader of a parameterized type resolves the readers of the types it is made of, some of them
 * parameterized too: {@code List<Order>} needs {@code Order}, whose {@code List<Line>} member is another entry of the
 * same cache. Building them inside {@code ConcurrentHashMap.computeIfAbsent} failed with "Recursive update" whenever
 * the inner key fell in the bin of the outer one, which depends on identity hash codes and so changed from one JVM
 * to the next (BUG-20261029-09).
 */
@DisplayName("JSON-B — readers of parameterized types made of parameterized types")
class NestedParameterizedReaderTest {

    public record Line(String sku, int quantity) {}

    /** Many distinct parameterized members: one of them shares a bin with {@code List<Wide>} in any JVM run. */
    public record Wide(
            List<Line> lines, List<String> a1, List<Integer> a2, List<Long> a3, List<Double> a4, List<Boolean> a5,
            List<BigDecimal> a6, List<BigInteger> a7, List<Short> a8, List<Float> a9, List<Character> a10,
            List<UUID> a11, List<Instant> a12, List<LocalDate> a13, List<LocalTime> a14, List<LocalDateTime> a15,
            List<OffsetDateTime> a16, List<ZonedDateTime> a17, List<Duration> a18, List<Byte> a19,
            Set<String> b1, Set<Integer> b2, Set<Long> b3, Set<Line> b4, Set<UUID> b5, SortedSet<String> b6,
            Deque<String> b7, Deque<Integer> b8, Deque<Line> b9,
            Map<String, String> c1, Map<String, Integer> c2, Map<String, Long> c3, Map<String, Line> c4,
            Map<String, List<Line>> c5, Map<String, List<String>> c6, Map<String, Set<Line>> c7,
            Optional<Line> d1, Optional<String> d2, Optional<List<Line>> d3, List<List<Line>> d4,
            List<Map<String, Line>> d5, List<Optional<Line>> d6, Set<List<Line>> d7) {}

    public record Order(String id, List<Line> lines) {}

    @Test
    void aListOfRecordsWithManyParameterizedMembersIsRead() throws Exception {
        for (int run = 0; run < 20; run++) {
            try (Jsonb jsonb = JsonbBuilder.create()) {
                List<Wide> read = jsonb.fromJson("[{\"lines\":[{\"sku\":\"s\",\"quantity\":2}]}]",
                        TypeTokens.parameterized(List.class, Wide.class));
                assertEquals(List.of(new Line("s", 2)), read.getFirst().lines());
            }
        }
    }

    @Test
    void aListOfOrdersWithLinesIsRead() throws Exception {
        try (Jsonb jsonb = JsonbBuilder.create()) {
            List<Order> read = jsonb.fromJson("[{\"id\":\"o1\",\"lines\":[{\"sku\":\"s\",\"quantity\":1}]}]",
                    TypeTokens.parameterized(List.class, Order.class));
            assertEquals(List.of(new Order("o1", List.of(new Line("s", 1)))), read);
        }
    }
}
