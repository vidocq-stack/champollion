package io.vidocq.champollion.jsonp.internal;

/**
 * RFC 8259 §7 escaping shared between {@link ChampollionJsonGenerator} and
 * {@link ChampollionJsonString#toString()} (to obtain a text representation that
 * is directly valid JSON).
 */
public final class JsonStringEscaper {

    private JsonStringEscaper() {}

    /**
     * Public variant for codegen tools: returns the {@code "<s>"} string with
     * surrounding quotes and RFC 8259 §7 escaping applied.
     */
    public static String preQuoted(String s) {
        return quote(s);
    }

    static String quote(String s) {
        var sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
