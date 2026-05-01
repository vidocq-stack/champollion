package io.vidocq.champollion.jsonp.internal;

/**
 * Escape RFC 8259 §7 partagé entre {@link ChampollionJsonGenerator} et
 * {@link ChampollionJsonString#toString()} (pour avoir une représentation textuelle
 * directement valide en JSON).
 */
final class JsonStringEscaper {

    private JsonStringEscaper() {}

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
