package io.vidocq.champollion.jsonp.internal;

/**
 * Tokenizer JSON pull-based abstrait, RFC 8259 strict.
 *
 * <p>P10.2 — chaque sous-classe finale duplique {@link #next()} et ses helpers
 * pour permettre à HotSpot d'inliner statiquement {@code read()}/{@code peekRead()}
 * (monomorphic car appelés <em>au sein</em> d'une classe finale unique). Le
 * parent ne fournit que :</p>
 * <ul>
 *   <li>les champs partagés (peek, line/column/offset, StringBuilder buffer) ;</li>
 *   <li>la signature {@link #next()} et {@link #close()} ;</li>
 *   <li>{@link #line()}/{@link #column()}/{@link #offset()} pour le diagnostic ;</li>
 *   <li>une fabrique d'exception {@link #error(String)} et un helper
 *       {@link #describe(int)} partagés entre les implémentations.</li>
 * </ul>
 *
 * <p>Pas de {@code synchronized}, pas de {@code ThreadLocal} —
 * virtual-thread-friendly.</p>
 */
public abstract class JsonTokenizer {

    static final int NO_PEEK = -2;

    protected final StringBuilder buffer = new StringBuilder(64);

    /** Caractère pré-lu (pour le 1-char lookahead), ou {@code -2} si aucun. */
    protected int peek = NO_PEEK;
    protected long line = 1;
    protected long column = 0;
    protected long offset = 0;

    JsonTokenizer() {}

    /** Lit le prochain token. Whitespace RFC 8259 §2 sauté. */
    public abstract JsonToken next();

    /** Ferme la source sous-jacente (Reader). No-op en mode String. */
    abstract void close();

    public final long line() { return line; }
    public final long column() { return column; }
    public final long offset() { return offset; }

    protected final void track(int c) {
        if (c == -1) return;
        offset++;
        if (c == '\n') {
            line++;
            column = 0;
        } else {
            column++;
        }
    }

    protected final jakarta.json.stream.JsonParsingException error(String message) {
        return new jakarta.json.stream.JsonParsingException(
                message + " (at line " + line + ", column " + column + ", offset " + offset + ")",
                new SimpleLocation(line, column, offset));
    }

    private record SimpleLocation(long line, long column, long offset) implements jakarta.json.stream.JsonLocation {
        @Override public long getLineNumber() { return line; }
        @Override public long getColumnNumber() { return column; }
        @Override public long getStreamOffset() { return offset; }
    }

    protected static String describe(int c) {
        if (c == -1) return "<EOF>";
        if (c >= 0x20 && c < 0x7F) return "'" + (char) c + "'";
        return String.format("U+%04X", c);
    }

    protected static int hexDigit(int c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return 10 + c - 'a';
        if (c >= 'A' && c <= 'F') return 10 + c - 'A';
        return -1;
    }
}
