package io.vidocq.champollion.jsonp.internal;

import jakarta.json.JsonException;

import java.io.IOException;
import java.io.Reader;

/**
 * Tokenizer JSON RFC 8259 lisant un {@link Reader} via un buffer
 * {@code char[BUF_SIZE]} pré-alloué (P1).
 */
public final class JsonReaderTokenizer extends JsonTokenizer {

    /** Taille du buffer char[] de lecture. */
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

    @Override
    protected int read() {
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

    @Override
    protected int peekRead() {
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
            // BUF_SIZE constante : permet à HotSpot d'inférer la taille à
            // compile-time et d'éliminer des bounds-checks sur buf.
            int n = reader.read(buf, 0, BUF_SIZE);
            if (n <= 0) { eof = true; bufEnd = 0; bufPos = 0; }
            else { bufEnd = n; bufPos = 0; }
        } catch (IOException e) {
            throw new JsonException("I/O error reading JSON", e);
        }
    }
}
