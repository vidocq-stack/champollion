package io.vidocq.champollion.jsonp.internal;

/**
 * Tokenizer JSON pull-based abstrait, RFC 8259 strict.
 *
 * <p>P10.1 — split en 2 sous-classes finales :</p>
 * <ul>
 *   <li>{@link JsonReaderTokenizer} : lecture par bloc {@code char[BUF_SIZE]}
 *       sur un {@link java.io.Reader} (cas {@code fromJson(Reader)}).</li>
 *   <li>{@link JsonStringTokenizer} : lecture directe via {@link String#charAt}
 *       (cas {@code fromJson(String)} — économise ~1 KB d'alloc + une copie
 *       {@code String.getChars}).</li>
 * </ul>
 *
 * <p>Avec 2 call sites concrets distincts ({@code instanceof}-stable), HotSpot
 * peut faire un <em>inline-cache bimorphique</em> stable sur {@link #read()} /
 * {@link #peekRead()} et préserver le throughput Reader-mode.</p>
 *
 * <p>Pas de {@code synchronized}, pas de {@code ThreadLocal} — virtual-thread-friendly.
 * Le tokenizer ne maintient aucun état structurel ; le {@link ChampollionJsonParser}
 * de niveau supérieur gère la machine d'état.</p>
 */
public abstract sealed class JsonTokenizer
        permits JsonReaderTokenizer, JsonStringTokenizer {

    static final int NO_PEEK = -2;

    protected final StringBuilder buffer = new StringBuilder(64);

    /** Caractère pré-lu (pour {@link #peekRead()}), ou {@code -2} si aucun. */
    protected int peek = NO_PEEK;
    protected long line = 1;
    protected long column = 0;
    protected long offset = 0;

    JsonTokenizer() {}

    /** Ferme la source sous-jacente (Reader). No-op en mode String. */
    abstract void close();

    /** Lit le prochain caractère, ou -1 à la fin. */
    protected abstract int read();

    /** Pré-lit le prochain caractère sans le consommer (1-char lookahead). */
    protected abstract int peekRead();

    public final long line() { return line; }
    public final long column() { return column; }
    public final long offset() { return offset; }

    /** Lit le prochain token. Whitespace RFC 8259 §2 sauté. */
    public final JsonToken next() {
        int c = skipWhitespace();
        return switch (c) {
            case -1 -> JsonToken.Eof.INSTANCE;
            case '{' -> JsonToken.StartObject.INSTANCE;
            case '}' -> JsonToken.EndObject.INSTANCE;
            case '[' -> JsonToken.StartArray.INSTANCE;
            case ']' -> JsonToken.EndArray.INSTANCE;
            case ':' -> JsonToken.NameSeparator.INSTANCE;
            case ',' -> JsonToken.ValueSeparator.INSTANCE;
            case '"' -> readString();
            case 't' -> readKeyword("true", JsonToken.True.INSTANCE);
            case 'f' -> readKeyword("false", JsonToken.False.INSTANCE);
            case 'n' -> readKeyword("null", JsonToken.Null.INSTANCE);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) {
                    yield readNumber(c);
                }
                throw error("Unexpected character: " + describe(c));
            }
        };
    }

    private int skipWhitespace() {
        while (true) {
            int c = read();
            if (c == -1) return -1;
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') continue;
            return c;
        }
    }

    private JsonToken readKeyword(String expected, JsonToken token) {
        for (int i = 1; i < expected.length(); i++) {
            int c = read();
            if (c != expected.charAt(i)) {
                throw error("Expected '" + expected + "', mismatch at character " + (i + 1));
            }
        }
        return token;
    }

    private JsonToken.StringToken readString() {
        buffer.setLength(0);
        while (true) {
            int c = read();
            if (c == -1) throw error("Unterminated string literal");
            if (c == '"') {
                return new JsonToken.StringToken(buffer.toString());
            }
            if (c == '\\') {
                buffer.append(readEscape());
                continue;
            }
            // RFC 8259 §7 : control characters U+0000..U+001F MUST be escaped.
            if (c < 0x20) {
                throw error("Unescaped control character U+" + String.format("%04X", c) + " in string");
            }
            buffer.append((char) c);
        }
    }

    private char readEscape() {
        int c = read();
        return switch (c) {
            case '"' -> '"';
            case '\\' -> '\\';
            case '/' -> '/';
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'u' -> readUnicodeEscape();
            case -1 -> throw error("Unterminated escape sequence");
            default -> throw error("Invalid escape character: " + describe(c));
        };
    }

    private char readUnicodeEscape() {
        int code = 0;
        for (int i = 0; i < 4; i++) {
            int c = read();
            if (c == -1) throw error("Unterminated unicode escape");
            int d = hexDigit(c);
            if (d < 0) throw error("Invalid hex digit in unicode escape: " + describe(c));
            code = (code << 4) | d;
        }
        return (char) code;
    }

    private static int hexDigit(int c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return 10 + c - 'a';
        if (c >= 'A' && c <= 'F') return 10 + c - 'A';
        return -1;
    }

    private JsonToken.NumberToken readNumber(int first) {
        // RFC 8259 §6 : number = [ minus ] int [ frac ] [ exp ]
        buffer.setLength(0);
        buffer.append((char) first);

        int c = first;
        if (c == '-') {
            c = read();
            if (c == -1) throw error("Unexpected end after '-'");
            if (c < '0' || c > '9') throw error("Expected digit after '-', got " + describe(c));
            buffer.append((char) c);
        }

        // int : '0' | digit1-9 *digit  (RFC 8259 §6 : pas de zéros initiaux)
        if (c == '0') {
            int n = peekRead();
            if (n >= '0' && n <= '9') {
                throw error("Leading zeros are not allowed in JSON numbers");
            }
        } else {
            while (true) {
                int n = peekRead();
                if (n >= '0' && n <= '9') {
                    buffer.append((char) consumePeek());
                } else break;
            }
        }

        // frac
        int n = peekRead();
        if (n == '.') {
            buffer.append((char) consumePeek());
            int d = read();
            if (d < '0' || d > '9') throw error("Expected digit after '.', got " + describe(d));
            buffer.append((char) d);
            while (true) {
                int x = peekRead();
                if (x >= '0' && x <= '9') buffer.append((char) consumePeek());
                else break;
            }
        }

        // exp
        n = peekRead();
        if (n == 'e' || n == 'E') {
            buffer.append((char) consumePeek());
            int s = peekRead();
            if (s == '+' || s == '-') {
                buffer.append((char) consumePeek());
            }
            int d = read();
            if (d < '0' || d > '9') throw error("Expected digit in exponent, got " + describe(d));
            buffer.append((char) d);
            while (true) {
                int x = peekRead();
                if (x >= '0' && x <= '9') buffer.append((char) consumePeek());
                else break;
            }
        }

        return new JsonToken.NumberToken(buffer.toString());
    }

    protected final int consumePeek() {
        int c = peek;
        peek = NO_PEEK;
        track(c);
        return c;
    }

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
}
