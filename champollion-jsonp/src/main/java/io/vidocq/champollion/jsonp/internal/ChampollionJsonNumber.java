package io.vidocq.champollion.jsonp.internal;

import jakarta.json.JsonNumber;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

/**
 * Implémentation de {@link JsonNumber} adossée à la forme lexicale du nombre JSON,
 * avec parsing différé en {@link BigDecimal} (cache lazy).
 *
 * <p>{@code equals} compare la valeur numérique ({@link BigDecimal}), comme exigé
 * par la spec Jakarta JSON-P §4.5 : {@code 1} est égal à {@code 1.0}.</p>
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
        // Forme lexicale : ni '.', ni 'e/E' → integral.
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
