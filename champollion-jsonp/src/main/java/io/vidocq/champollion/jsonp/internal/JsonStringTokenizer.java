package io.vidocq.champollion.jsonp.internal;

/**
 * RFC 8259 fast-path JSON tokenizer that reads a {@link String} directly via
 * {@link String#charAt} (P10.1). No {@code Reader}, no intermediate {@code char[]}
 * — saves about 1 KB of allocations per {@code fromJson(String)} and avoids
 * {@code String.getChars} copying.
 *
 * <p>P10.2 — {@link #next()} and all helpers are duplicated locally (not in the
 * abstract parent) so calls to {@link #read()} / {@link #peekRead()} remain
 * statically resolved in this final class, making them HotSpot-inlineable without
 * virtual dispatch.</p>
 */
public final class JsonStringTokenizer extends JsonTokenizer {

    private final String src;
    private final int srcLen;
    private int srcPos = 0;

    public JsonStringTokenizer(String src) {
        if (src == null) throw new IllegalArgumentException("src is null");
        this.src = src;
        this.srcLen = src.length();
    }

    @Override
    void close() { /* no-op: nothing to close */ }

    private int read() {
        if (peek != NO_PEEK) {
            int c = peek;
            peek = NO_PEEK;
            track(c);
            return c;
        }
        if (srcPos >= srcLen) return -1;
        int c = src.charAt(srcPos++);
        track(c);
        return c;
    }

    private int peekRead() {
        if (peek != NO_PEEK) return peek;
        if (srcPos >= srcLen) { peek = -1; return -1; }
        peek = src.charAt(srcPos++);
        return peek;
    }

    private int consumePeek() {
        int c = peek;
        peek = NO_PEEK;
        track(c);
        return c;
    }

    @Override
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
            if (c == '"') return new JsonToken.StringToken(buffer.toString());
            if (c == '\\') { buffer.append(readEscape()); continue; }
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

    private JsonToken.NumberToken readNumber(int first) {
        buffer.setLength(0);
        buffer.append((char) first);

        int c = first;
        if (c == '-') {
            c = read();
            if (c == -1) throw error("Unexpected end after '-'");
            if (c < '0' || c > '9') throw error("Expected digit after '-', got " + describe(c));
            buffer.append((char) c);
        }

        if (c == '0') {
            int n = peekRead();
            if (n >= '0' && n <= '9') throw error("Leading zeros are not allowed in JSON numbers");
        } else {
            while (true) {
                int n = peekRead();
                if (n >= '0' && n <= '9') buffer.append((char) consumePeek());
                else break;
            }
        }

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

        n = peekRead();
        if (n == 'e' || n == 'E') {
            buffer.append((char) consumePeek());
            int s = peekRead();
            if (s == '+' || s == '-') buffer.append((char) consumePeek());
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
}
