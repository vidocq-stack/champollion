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
package io.vidocq.champollion.jsonp.internal;

import jakarta.json.JsonNumber;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

/**
 * {@link JsonNumber} implementation backed by the JSON number's lexical form,
 * with deferred parsing into {@link BigDecimal} (lazy cache).
 *
 * <p>{@code equals} compares the numeric value ({@link BigDecimal}), as required
 * by Jakarta JSON-P §4.5: {@code 1} equals {@code 1.0}.</p>
 */
public final class ChampollionJsonNumber implements JsonNumber {

    private final String literal;
    private volatile BigDecimal cache;

    private ChampollionJsonNumber(String literal) {
        this.literal = literal;
    }

    public static ChampollionJsonNumber of(String literal) {
        if (literal == null) throw new NullPointerException("literal is null");
        return new ChampollionJsonNumber(literal);
    }

    public static ChampollionJsonNumber of(BigDecimal value) {
        if (value == null) throw new NullPointerException("value is null");
        return new ChampollionJsonNumber(value.toString());
    }

    public static ChampollionJsonNumber of(int value) {
        return new ChampollionJsonNumber(Integer.toString(value));
    }

    public static ChampollionJsonNumber of(long value) {
        return new ChampollionJsonNumber(Long.toString(value));
    }

    @Override public ValueType getValueType() { return ValueType.NUMBER; }

    @Override public boolean isIntegral() {
        // Lexical form: no '.' and no 'e/E' → integral.
        return literal.indexOf('.') < 0 && literal.indexOf('e') < 0 && literal.indexOf('E') < 0;
    }

    @Override public int intValue() { return bigDecimalValue().intValue(); }
    @Override public int intValueExact() { return bigDecimalValue().intValueExact(); }
    @Override public long longValue() { return bigDecimalValue().longValue(); }
    @Override public long longValueExact() { return bigDecimalValue().longValueExact(); }
    @Override public double doubleValue() { return bigDecimalValue().doubleValue(); }

    @Override public BigInteger bigIntegerValue() {
        return bigDecimalValue().toBigInteger();
    }

    @Override public BigInteger bigIntegerValueExact() {
        return bigDecimalValue().toBigIntegerExact();
    }

    @Override public BigDecimal bigDecimalValue() {
        BigDecimal c = cache;
        if (c == null) {
            c = new BigDecimal(literal);
            cache = c;
        }
        return c;
    }

    @Override public Number numberValue() { return bigDecimalValue(); }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JsonNumber that)) return false;
        return bigDecimalValue().compareTo(that.bigDecimalValue()) == 0;
    }

    @Override public int hashCode() {
        return Objects.hash(bigDecimalValue().stripTrailingZeros());
    }

    @Override public String toString() { return literal; }
}
