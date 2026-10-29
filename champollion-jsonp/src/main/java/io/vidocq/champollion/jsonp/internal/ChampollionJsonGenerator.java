/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.champollion.jsonp.internal;

import io.vidocq.champollion.spi.RawJsonKeyWriter;
import jakarta.json.JsonException;
import jakarta.json.JsonValue;
import jakarta.json.stream.JsonGenerationException;
import jakarta.json.stream.JsonGenerator;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Generator JSON-P 2.1 push-based, RFC 8259 strict.
 *
 * <p>Pile de contextes pour valider la grammaire (KEY attendue dans un objet,
 * VALUE attendue dans un array, etc.). Pas de {@code synchronized}.</p>
 *
 * <p>Pretty-printing: 4 spaces, single LF. Not configurable at M1 — will be
 * exposed via {@link jakarta.json.stream.JsonGeneratorFactory} in M1.4.</p>
 */
public final class ChampollionJsonGenerator implements JsonGenerator, RawJsonKeyWriter {

    private enum Ctx {
        ROOT_BEFORE,    // root not written yet
        ROOT_AFTER,     // root written, nothing else allowed
        OBJECT_FIRST,   // in an object, no member yet
        OBJECT_KEY,     // in an object, waiting for a key (after ',')
        OBJECT_VALUE,   // in an object, a key was just written, value expected
        OBJECT_AFTER_VALUE, // after an object value, expects ',' or '}'
        ARRAY_FIRST,    // in an array, no element yet
        ARRAY_AFTER     // in an array, after an element, expects ',' or ']'
    }

    /**
     * RFC 8259 §7 escape of each control character: the two-character form when there is one,
     * otherwise the six-character one with four lower-case hexadecimal digits.
     */
    private static final String[] CONTROL_ESCAPES = new String[0x20];

    static {
        for (int c = 0; c < CONTROL_ESCAPES.length; c++) {
            CONTROL_ESCAPES[c] = String.format("\\u%04x", c);
        }
        CONTROL_ESCAPES['\b'] = "\\b";
        CONTROL_ESCAPES['\f'] = "\\f";
        CONTROL_ESCAPES['\n'] = "\\n";
        CONTROL_ESCAPES['\r'] = "\\r";
        CONTROL_ESCAPES['\t'] = "\\t";
    }

    private static final String JSON_TRUE = "true";
    private static final String JSON_FALSE = "false";

    /** The generator's own buffer: every value, key, comma and escape goes to it. */
    private final GeneratorSink<?> out;
    private final boolean pretty;
    /** The contexts, root first: a plain array, as a value changes the top in place rather than popping and pushing it. */
    private Ctx[] stack = new Ctx[8];
    private int size;
    private int depth = 0;

    public ChampollionJsonGenerator(Writer out, boolean pretty) {
        this(new WriterSink(requireTarget(out, "writer is null")), pretty);
    }

    /**
     * A generator writing UTF-8 to {@code out}, encoded by the generator itself: no
     * {@code OutputStreamWriter}, and a buffer that starts small.
     */
    public ChampollionJsonGenerator(OutputStream out, boolean pretty) {
        this(new Utf8StreamSink(requireTarget(out, "output stream is null")), pretty);
    }

    /**
     * As {@link #ChampollionJsonGenerator(OutputStream, boolean)}, with a place where the caller keeps a buffer
     * from one document to the next. A small document stays in the small buffer the generator starts with; a
     * document that outgrows it borrows the kept buffer, of the size at which the generator hands its bytes to
     * {@code out}, instead of growing its own, and gives it back on {@link #close()}. Several generators may
     * share the place: one that finds it empty allocates a buffer, which it leaves there when it is closed.
     */
    public ChampollionJsonGenerator(OutputStream out, boolean pretty, AtomicReference<byte[]> keptBuffer) {
        this(new Utf8StreamSink(requireTarget(out, "output stream is null"),
                requireTarget(keptBuffer, "kept buffer place is null")), pretty);
    }

    private ChampollionJsonGenerator(GeneratorSink<?> out, boolean pretty) {
        this.out = out;
        this.pretty = pretty;
        push(Ctx.ROOT_BEFORE);
    }

    private static <T> T requireTarget(T target, String message) {
        if (target == null) throw new IllegalArgumentException(message);
        return target;
    }

    // ===== writeStart* / writeEnd =====

    @Override public JsonGenerator writeStartObject() {
        beforeValue();
        write('{');
        push(Ctx.OBJECT_FIRST);
        depth++;
        return this;
    }

    @Override public JsonGenerator writeStartObject(String name) {
        beforeKey(name);
        write('{');
        push(Ctx.OBJECT_FIRST);
        depth++;
        return this;
    }

    @Override public JsonGenerator writeStartArray() {
        beforeValue();
        write('[');
        push(Ctx.ARRAY_FIRST);
        depth++;
        return this;
    }

    @Override public JsonGenerator writeStartArray(String name) {
        beforeKey(name);
        write('[');
        push(Ctx.ARRAY_FIRST);
        depth++;
        return this;
    }

    @Override public JsonGenerator writeEnd() {
        Ctx top = peek();
        if (top == Ctx.ROOT_BEFORE || top == Ctx.ROOT_AFTER) {
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
        pop();
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
        out.appendLong(value);
        afterValue();
        return this;
    }

    @Override public JsonGenerator write(String name, long value) {
        beforeKey(name);
        out.appendLong(value);
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
        writeRaw(value ? JSON_TRUE : JSON_FALSE);
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

    /** Emits an arbitrary JsonValue (object, array, scalar). */
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
            case TRUE -> writeRaw(JSON_TRUE);
            case FALSE -> writeRaw(JSON_FALSE);
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
        out.appendLong(value);
        afterValue();
        return this;
    }

    @Override public JsonGenerator write(long value) {
        beforeValue();
        out.appendLong(value);
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
        writeRaw(value ? JSON_TRUE : JSON_FALSE);
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
     * Fast path for static codegens: writes the pre-encoded key (quoted +
     * RFC 8259 §7 escaping already applied) without running the escape scanner
     * on every call.
     *
     * <p>{@code preQuotedKey} must already include the surrounding quotes, for
     * example {@code "\"name\""}. The {@code :} and any comma are handled by
     * this method.</p>
     *
     * <p>Usage: produces the same code as {@link #writeKey(String)} but in
     * O(1) instead of O(n) on the name length (no character-by-character scan).</p>
     */
    @Override
    public void writeKeyRaw(String preQuotedKey) {
        Ctx top = peek();
        switch (top) {
            case OBJECT_FIRST -> {
                if (pretty) { write('\n'); indent(); }
                replaceTop(Ctx.OBJECT_VALUE);
            }
            case OBJECT_AFTER_VALUE -> {
                write(',');
                if (pretty) { write('\n'); indent(); }
                replaceTop(Ctx.OBJECT_VALUE);
            }
            default -> throw new JsonGenerationException("Key not allowed at this position (state=" + top + ")");
        }
        writeRaw(preQuotedKey);
        write(':');
        if (pretty) write(' ');
    }

    /**
     * P4 fast path: fragment {@code "name":} as a single block. Reduces the hot
     * path of an object to {@code N+1} writes instead of {@code 3N+1}.
     */
    @Override
    public void writeKeyRawWithColon(String preQuotedKeyWithColon) {
        Ctx top = peek();
        switch (top) {
            case OBJECT_FIRST -> {
                if (pretty) { write('\n'); indent(); }
                replaceTop(Ctx.OBJECT_VALUE);
            }
            case OBJECT_AFTER_VALUE -> {
                write(',');
                if (pretty) { write('\n'); indent(); }
                replaceTop(Ctx.OBJECT_VALUE);
            }
            default -> throw new JsonGenerationException("Key not allowed at this position (state=" + top + ")");
        }
        writeRaw(preQuotedKeyWithColon);
        if (pretty) write(' ');
    }

    @Override public void close() {
        if (peek() != Ctx.ROOT_AFTER) {
            throw new JsonGenerationException("close() called with open containers or empty document");
        }
        // Spec §3.5: close() closes the underlying Writer/OutputStream and
        // propagates any IOException as JsonException.
        out.drain();
        try {
            out.closeTarget();
        } catch (IOException e) {
            throw new JsonException("I/O error closing generator", e);
        } finally {
            out.release();
        }
    }

    @Override public void flush() {
        out.drain();
        try {
            out.flushTarget();
        } catch (IOException e) {
            throw new JsonException("I/O error flushing JSON", e);
        }
    }

    // ===== state machine helpers =====

    private void beforeValue() {
        Ctx top = peek();
        switch (top) {
            case ROOT_BEFORE -> replaceTop(Ctx.ROOT_AFTER);
            case ARRAY_FIRST -> {
                if (pretty) { write('\n'); indent(); }
                replaceTop(Ctx.ARRAY_AFTER);
            }
            case ARRAY_AFTER -> {
                write(',');
                if (pretty) { write('\n'); indent(); }
            }
            case OBJECT_VALUE -> replaceTop(Ctx.OBJECT_AFTER_VALUE);
            default -> throw new JsonGenerationException("Unexpected value at this position (state=" + top + ")");
        }
    }

    private void beforeKey(String name) {
        Ctx top = peek();
        switch (top) {
            case OBJECT_FIRST -> {
                if (pretty) { write('\n'); indent(); }
                replaceTop(Ctx.OBJECT_VALUE);
            }
            case OBJECT_AFTER_VALUE -> {
                write(',');
                if (pretty) { write('\n'); indent(); }
                replaceTop(Ctx.OBJECT_VALUE);
            }
            case OBJECT_KEY -> replaceTop(Ctx.OBJECT_VALUE);
            default -> throw new JsonGenerationException("Key not allowed at this position (state=" + top + ")");
        }
        writeString(name);
        write(':');
        if (pretty) write(' ');
    }

    private void afterValue() {
        // For *named* values in an object, we are in OBJECT_VALUE → switch to OBJECT_AFTER_VALUE.
        Ctx top = peek();
        if (top == Ctx.OBJECT_VALUE) {
            pop();
            push(Ctx.OBJECT_AFTER_VALUE);
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
        out.append(s);
    }

    private void write(char c) {
        out.write(c);
    }

    private void writeString(String s) {
        // Runs of characters that need no escape are appended whole: a pure ASCII name or value,
        // the overwhelming majority, in one call, and a surrogate pair never split.
        write('"');
        int n = s.length();
        int start = 0;
        for (int i = 0; i < n; i++) {
            String escape = escapeOf(s.charAt(i));
            if (escape != null) {
                if (i > start) out.append(s, start, i - start);
                writeRaw(escape);
                start = i + 1;
            }
        }
        if (start < n) out.append(s, start, n - start);
        write('"');
    }

    /** RFC 8259 §7: the escape of {@code c}, or {@code null} when it is written as is. */
    private static String escapeOf(char c) {
        if (c >= 0x20) {
            if (c == '"') return "\\\"";
            if (c == '\\') return "\\\\";
            return null;
        }
        return CONTROL_ESCAPES[c];
    }

    /** The current context; the root one is never popped, so there always is one. */
    private Ctx peek() {
        return stack[size - 1];
    }

    private void push(Ctx ctx) {
        if (size == stack.length) stack = Arrays.copyOf(stack, size << 1);
        stack[size++] = ctx;
    }

    private void pop() {
        stack[--size] = null;
    }

    private void replaceTop(Ctx ctx) {
        stack[size - 1] = ctx;
    }

    private void indent() {
        for (int i = 0; i < depth; i++) writeRaw("    ");
    }
}
