package io.vidocq.champollion.jsonp.internal;

import jakarta.json.JsonException;

import java.io.IOException;
import java.io.Reader;

/**
 * Tokenizer JSON pull-based, RFC 8259 strict.
 *
 * <p>Pas de buffer interne autre qu'un {@link StringBuilder} amorti pour les chaînes/nombres.
 * Pas de {@code synchronized}, pas de {@code ThreadLocal} — virtual-thread-friendly.</p>
 *
 * <p>Le tokenizer ne maintient aucun état structurel (équilibre des accolades, etc.).
 * Cette responsabilité revient au {@code JsonParser} de niveau supérieur.</p>
 */
public final class JsonTokenizer {

    private final Reader reader;
    private final StringBuilder buffer = new StringBuilder(64);

    /** Caractère pré-lu, ou {@code -2} si aucun. {@code -1} signifie EOF lu. */
    private int peek = NO_PEEK;
    private long line = 1;
    private long column = 0;
    private long offset = 0;

    private static final int NO_PEEK = -2;

    public JsonTokenizer(Reader reader) {
        if (reader == null) {
            throw new IllegalArgumentException("reader is null");
        }
        this.reader = reader;
    }

    /**
     * Lit le prochain token. Whitespace RFC 8259 §2 sauté.
     */
    public JsonToken next() {
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

    /** Position courante (utile pour les diagnostics). */
    public long line() { return line; }
    public long column() { return column; }
    public long offset() { return offset; }

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

    private int read() {
        if (peek != NO_PEEK) {
            int c = peek;
            peek = NO_PEEK;
            track(c);
            return c;
        }
        try {
            int c = reader.read();
            track(c);
            return c;
        } catch (IOException e) {
            throw new JsonException("I/O error reading JSON", e);
        }
    }

    private int peekRead() {
        if (peek != NO_PEEK) return peek;
        try {
            peek = reader.read();
            return peek;
        } catch (IOException e) {
            throw new JsonException("I/O error reading JSON", e);
        }
    }

    private int consumePeek() {
        int c = peek;
        peek = NO_PEEK;
        track(c);
        return c;
    }

    private void track(int c) {
        if (c == -1) return;
        offset++;
        if (c == '\n') {
            line++;
            column = 0;
        } else {
            column++;
        }
    }

    private jakarta.json.stream.JsonParsingException error(String message) {
        return new jakarta.json.stream.JsonParsingException(
                message + " (at line " + line + ", column " + column + ", offset " + offset + ")",
                new SimpleLocation(line, column, offset));
    }

    private record SimpleLocation(long line, long column, long offset) implements jakarta.json.stream.JsonLocation {
        @Override public long getLineNumber() { return line; }
        @Override public long getColumnNumber() { return column; }
        @Override public long getStreamOffset() { return offset; }
    }

    private static String describe(int c) {
        if (c == -1) return "<EOF>";
        if (c >= 0x20 && c < 0x7F) return "'" + (char) c + "'";
        return String.format("U+%04X", c);
    }
}
