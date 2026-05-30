package io.vidocq.champollion.jsonp.internal;

import jakarta.json.JsonException;

import java.io.IOException;
import java.io.Reader;

/**
 * RFC 8259 JSON tokenizer that reads a {@link Reader} through a pre-allocated
 * {@code char[BUF_SIZE]} buffer (P1).
 *
 * <p>P10.2 — {@link #next()} and all helpers ({@code skipWhitespace},
 * {@code readKeyword}, {@code readString}, {@code readEscape},
 * {@code readNumber}…) are duplicated locally (not in the abstract parent) so
 * calls to {@link #read()}/{@link #peekRead()} remain <em>statically resolved</em>
 * in the same final class, and therefore HotSpot-inlineable without virtual
 * dispatch.</p>
 */
public final class JsonReaderTokenizer extends JsonTokenizer {

    /** Size of the read {@code char[]} buffer. */
    public static final int BUF_SIZE = 512;

    private final Reader reader;
    private final char[] buf = new char[BUF_SIZE];
    private int bufPos = 0;
    private int bufEnd = 0;
    private boolean eof = false;

    public JsonReaderTokenizer(Reader reader) {
        if (reader == null) throw new IllegalArgumentException("reader is null");
        this.reader = reader;
    }

    @Override
    void close() {
        try {
            reader.close();
        } catch (IOException e) {
            throw new JsonException("I/O error closing reader", e);
        }
    }

    private int read() {
        if (peek != NO_PEEK) {
            int c = peek;
            peek = NO_PEEK;
            track(c);
            return c;
        }
        if (bufPos >= bufEnd) {
            if (eof) return -1;
            refill();
            if (bufEnd == 0) return -1;
        }
        int c = buf[bufPos++];
        track(c);
        return c;
    }

    private int peekRead() {
        if (peek != NO_PEEK) return peek;
        if (bufPos >= bufEnd) {
            if (eof) return -1;
            refill();
            if (bufEnd == 0) { peek = -1; return -1; }
        }
        peek = buf[bufPos++];
        return peek;
    }

    private void refill() {
        try {
            int n = reader.read(buf, 0, BUF_SIZE);
            if (n <= 0) { eof = true; bufEnd = 0; bufPos = 0; }
            else { bufEnd = n; bufPos = 0; }
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
