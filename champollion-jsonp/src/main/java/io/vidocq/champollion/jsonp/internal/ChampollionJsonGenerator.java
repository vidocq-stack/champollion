package io.vidocq.champollion.jsonp.internal;

import io.vidocq.champollion.spi.RawJsonKeyWriter;
import jakarta.json.JsonException;
import jakarta.json.JsonValue;
import jakarta.json.stream.JsonGenerationException;
import jakarta.json.stream.JsonGenerator;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Generator JSON-P 2.1 push-based, RFC 8259 strict.
 *
 * <p>Pile de contextes pour valider la grammaire (KEY attendue dans un objet,
 * VALUE attendue dans un array, etc.). Pas de {@code synchronized}.</p>
 *
 * <p>Pretty-printing : 4 espaces, LF unique. Pas configurable au niveau M1 — sera
 * exposé via {@link jakarta.json.stream.JsonGeneratorFactory} en M1.4.</p>
 */
public final class ChampollionJsonGenerator implements JsonGenerator, RawJsonKeyWriter {

    private enum Ctx {
        ROOT_BEFORE,    // racine pas encore écrite
        ROOT_AFTER,     // racine écrite, plus rien autorisé
        OBJECT_FIRST,   // dans un objet, aucun membre encore
        OBJECT_KEY,     // dans un objet, en attente d'une clé (après ',')
        OBJECT_VALUE,   // dans un objet, on vient d'écrire une clé, valeur attendue
        OBJECT_AFTER_VALUE, // après une valeur d'objet, attend ',' ou '}'
        ARRAY_FIRST,    // dans un array, aucun élément
        ARRAY_AFTER     // dans un array, après un élément, attend ',' ou ']'
    }

    private final Writer out;
    private final boolean pretty;
    private final Deque<Ctx> stack = new ArrayDeque<>();
    private int depth = 0;

    public ChampollionJsonGenerator(Writer out, boolean pretty) {
        if (out == null) throw new IllegalArgumentException("writer is null");
        // P2 — buffer 1 KB par défaut sur le Writer cible. Évite des dizaines
        // de petits writes par valeur sur OutputStreamWriter/StringWriter et
        // amortit le coût d'écriture des escapes string char-par-char.
        // Si l'appelant a déjà fourni un Writer bufferisé, on évite la double
        // bufferisation.
        this.out = (out instanceof BufferedWriter || out instanceof java.io.StringWriter
                || out instanceof java.io.CharArrayWriter)
                ? out
                : new BufferedWriter(out, 256);
        this.pretty = pretty;
        this.stack.push(Ctx.ROOT_BEFORE);
    }

    // ===== writeStart* / writeEnd =====

    @Override public JsonGenerator writeStartObject() {
        beforeValue();
        write('{');
        stack.push(Ctx.OBJECT_FIRST);
        depth++;
        return this;
    }

    @Override public JsonGenerator writeStartObject(String name) {
        beforeKey(name);
        write('{');
        stack.push(Ctx.OBJECT_FIRST);
        depth++;
        return this;
    }

    @Override public JsonGenerator writeStartArray() {
        beforeValue();
        write('[');
        stack.push(Ctx.ARRAY_FIRST);
        depth++;
        return this;
    }

    @Override public JsonGenerator writeStartArray(String name) {
        beforeKey(name);
        write('[');
        stack.push(Ctx.ARRAY_FIRST);
        depth++;
        return this;
    }

    @Override public JsonGenerator writeEnd() {
        Ctx top = stack.peek();
        if (top == null || top == Ctx.ROOT_BEFORE || top == Ctx.ROOT_AFTER) {
            throw new JsonGenerationException("writeEnd() called without matching start");
        }
        boolean wasEmpty = (top == Ctx.OBJECT_FIRST || top == Ctx.ARRAY_FIRST);
        boolean isObject = (top == Ctx.OBJECT_FIRST || top == Ctx.OBJECT_KEY || top == Ctx.OBJECT_AFTER_VALUE);
        if (top == Ctx.OBJECT_VALUE) {
            throw new JsonGenerationException("writeEnd() called after key without value");
        }
        depth--;
        if (pretty && !wasEmpty) {
            write('\n');
            indent();
        }
        write(isObject ? '}' : ']');
        stack.pop();
        afterValue();
        return this;
    }

    // ===== named members in object =====

    @Override public JsonGenerator write(String name, JsonValue value) {
        beforeKey(name);
        emitJsonValue(value);
        afterValue();
        return this;
    }

    @Override public JsonGenerator write(String name, String value) {
        beforeKey(name);
        writeString(value);
        afterValue();
        return this;
    }

    @Override public JsonGenerator write(String name, BigInteger value) {
        beforeKey(name);
        writeRaw(value.toString());
        afterValue();
        return this;
    }

    @Override public JsonGenerator write(String name, BigDecimal value) {
        beforeKey(name);
        writeRaw(value.toString());
        afterValue();
        return this;
    }

    @Override public JsonGenerator write(String name, int value) {
        beforeKey(name);
        writeRaw(Integer.toString(value));
        afterValue();
        return this;
    }

    @Override public JsonGenerator write(String name, long value) {
        beforeKey(name);
        writeRaw(Long.toString(value));
        afterValue();
        return this;
    }

    @Override public JsonGenerator write(String name, double value) {
        beforeKey(name);
        writeDouble(value);
        afterValue();
        return this;
    }

    @Override public JsonGenerator write(String name, boolean value) {
        beforeKey(name);
        writeRaw(value ? "true" : "false");
        afterValue();
        return this;
    }

    @Override public JsonGenerator writeNull(String name) {
        beforeKey(name);
        writeRaw("null");
        afterValue();
        return this;
    }

    // ===== unnamed values (in arrays / root) =====

    @Override public JsonGenerator write(JsonValue value) {
        beforeValue();
        emitJsonValue(value);
        afterValue();
        return this;
    }

    /** Émet un JsonValue arbitraire (objet, array, scalaire). */
    private void emitJsonValue(JsonValue value) {
        if (value == null) { writeRaw("null"); return; }
        switch (value.getValueType()) {
            case OBJECT -> {
                write('{');
                boolean first = true;
                for (var e : ((jakarta.json.JsonObject) value).entrySet()) {
                    if (!first) write(',');
                    first = false;
                    writeString(e.getKey());
                    write(':');
                    emitJsonValue(e.getValue());
                }
                write('}');
            }
            case ARRAY -> {
                write('[');
                boolean first = true;
                for (JsonValue v : (jakarta.json.JsonArray) value) {
                    if (!first) write(',');
                    first = false;
                    emitJsonValue(v);
                }
                write(']');
            }
            case STRING -> writeString(((jakarta.json.JsonString) value).getString());
            case NUMBER -> writeRaw(((jakarta.json.JsonNumber) value).bigDecimalValue().toString());
            case TRUE -> writeRaw("true");
            case FALSE -> writeRaw("false");
            case NULL -> writeRaw("null");
        }
    }

    @Override public JsonGenerator write(String value) {
        beforeValue();
        writeString(value);
        afterValue();
        return this;
    }

    @Override public JsonGenerator write(BigDecimal value) {
        beforeValue();
        writeRaw(value.toString());
        afterValue();
        return this;
    }

    @Override public JsonGenerator write(BigInteger value) {
        beforeValue();
        writeRaw(value.toString());
        afterValue();
        return this;
    }

    @Override public JsonGenerator write(int value) {
        beforeValue();
        writeRaw(Integer.toString(value));
        afterValue();
        return this;
    }

    @Override public JsonGenerator write(long value) {
        beforeValue();
        writeRaw(Long.toString(value));
        afterValue();
        return this;
    }

    @Override public JsonGenerator write(double value) {
        beforeValue();
        writeDouble(value);
        afterValue();
        return this;
    }

    @Override public JsonGenerator write(boolean value) {
        beforeValue();
        writeRaw(value ? "true" : "false");
        afterValue();
        return this;
    }

    @Override public JsonGenerator writeNull() {
        beforeValue();
        writeRaw("null");
        afterValue();
        return this;
    }

    @Override public JsonGenerator writeKey(String name) {
        beforeKey(name);
        return this;
    }

    /**
     * Voie rapide pour les codegens statiques : écrit la clé pré-encodée (quoted +
     * escape RFC 8259 §7 déjà appliqué) sans repasser dans le scanner d'escape à
     * chaque appel.
     *
     * <p>{@code preQuotedKey} doit déjà inclure les guillemets englobants, par
     * exemple {@code "\"name\""}. Le {@code :} et la virgule éventuelle sont
     * gérés par cette méthode.</p>
     *
     * <p>Usage : produit du même code que {@link #writeKey(String)} mais en
     * O(1) au lieu de O(n) sur la longueur du nom (pas de scan caractère par
     * caractère).</p>
     */
    @Override
    public void writeKeyRaw(String preQuotedKey) {
        Ctx top = stack.peek();
        switch (top) {
            case OBJECT_FIRST -> {
                if (pretty) { write('\n'); indent(); }
                stack.pop(); stack.push(Ctx.OBJECT_VALUE);
            }
            case OBJECT_AFTER_VALUE -> {
                write(',');
                if (pretty) { write('\n'); indent(); }
                stack.pop(); stack.push(Ctx.OBJECT_VALUE);
            }
            default -> throw new JsonGenerationException("Key not allowed at this position (state=" + top + ")");
        }
        writeRaw(preQuotedKey);
        write(':');
        if (pretty) write(' ');
    }

    @Override public void close() {
        if (stack.peek() != Ctx.ROOT_AFTER) {
            throw new JsonGenerationException("close() called with open containers or empty document");
        }
        // Spec §3.5 : close() ferme le Writer/OutputStream sous-jacent et propage
        // toute IOException en JsonException.
        try {
            out.flush();
            out.close();
        } catch (IOException e) {
            throw new JsonException("I/O error closing generator", e);
        }
    }

    @Override public void flush() {
        try {
            out.flush();
        } catch (IOException e) {
            throw new JsonException("I/O error flushing JSON", e);
        }
    }

    // ===== state machine helpers =====

    private void beforeValue() {
        Ctx top = stack.peek();
        switch (top) {
            case ROOT_BEFORE -> { stack.pop(); stack.push(Ctx.ROOT_AFTER); }
            case ARRAY_FIRST -> {
                if (pretty) { write('\n'); indent(); }
                stack.pop(); stack.push(Ctx.ARRAY_AFTER);
            }
            case ARRAY_AFTER -> {
                write(',');
                if (pretty) { write('\n'); indent(); }
            }
            case OBJECT_VALUE -> {
                stack.pop(); stack.push(Ctx.OBJECT_AFTER_VALUE);
            }
            default -> throw new JsonGenerationException("Unexpected value at this position (state=" + top + ")");
        }
    }

    private void beforeKey(String name) {
        Ctx top = stack.peek();
        switch (top) {
            case OBJECT_FIRST -> {
                if (pretty) { write('\n'); indent(); }
                stack.pop(); stack.push(Ctx.OBJECT_VALUE);
            }
            case OBJECT_AFTER_VALUE -> {
                write(',');
                if (pretty) { write('\n'); indent(); }
                stack.pop(); stack.push(Ctx.OBJECT_VALUE);
            }
            case OBJECT_KEY -> {
                stack.pop(); stack.push(Ctx.OBJECT_VALUE);
            }
            default -> throw new JsonGenerationException("Key not allowed at this position (state=" + top + ")");
        }
        writeString(name);
        write(':');
        if (pretty) write(' ');
    }

    private void afterValue() {
        // Pour les valeurs *nommées* dans un objet, on est en OBJECT_VALUE → bascule OBJECT_AFTER_VALUE.
        Ctx top = stack.peek();
        if (top == Ctx.OBJECT_VALUE) {
            stack.pop();
            stack.push(Ctx.OBJECT_AFTER_VALUE);
        }
    }

    private void writeDouble(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new NumberFormatException("JSON does not allow NaN or Infinity");
        }
        writeRaw(Double.toString(value));
    }

    // ===== low-level write =====

    private void writeRaw(String s) {
        try { out.write(s); } catch (IOException e) { throw new JsonException("I/O error", e); }
    }

    private void write(char c) {
        try { out.write(c); } catch (IOException e) { throw new JsonException("I/O error", e); }
    }

    private void writeString(String s) {
        // Fast-path ASCII pur sans escape — couvre l'écrasante majorité des keys
        // de records et des chaînes ordinaires. Évite N appels char-par-char à
        // out.write(c) au profit d'un unique out.write(s, off, len).
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c < 0x20 || c == '"' || c == '\\') break;
            i++;
        }
        try {
            out.write('"');
            if (i == n) {
                out.write(s);
                out.write('"');
                return;
            }
            // Préfixe propre, puis slow path à partir de i.
            if (i > 0) out.write(s, 0, i);
            for (; i < n; i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> out.write("\\\"");
                    case '\\' -> out.write("\\\\");
                    case '\b' -> out.write("\\b");
                    case '\f' -> out.write("\\f");
                    case '\n' -> out.write("\\n");
                    case '\r' -> out.write("\\r");
                    case '\t' -> out.write("\\t");
                    default -> {
                        if (c < 0x20) {
                            out.write(String.format("\\u%04x", (int) c));
                        } else {
                            out.write(c);
                        }
                    }
                }
            }
            out.write('"');
        } catch (IOException e) {
            throw new JsonException("I/O error", e);
        }
    }

    private void indent() {
        try {
            for (int i = 0; i < depth; i++) out.write("    ");
        } catch (IOException e) {
            throw new JsonException("I/O error", e);
        }
    }
}
