package io.vidocq.champollion.jsonp.internal;

/**
 * Tokenizer JSON RFC 8259 fast-path lisant directement une {@link String} via
 * {@link String#charAt} (P10.1). Aucun {@code Reader}, aucun {@code char[]}
 * intermédiaire — économise ~1 KB d'alloc par {@code fromJson(String)} et la
 * copie {@code String.getChars}.
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
    void close() { /* no-op : pas de ressource à fermer */ }

    @Override
    protected int read() {
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

    @Override
    protected int peekRead() {
        if (peek != NO_PEEK) return peek;
        if (srcPos >= srcLen) { peek = -1; return -1; }
        peek = src.charAt(srcPos++);
        return peek;
    }
}
