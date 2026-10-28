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
package io.vidocq.champollion.protobuf.internal;

import io.vidocq.champollion.protobuf.CodedInputStream;
import io.vidocq.champollion.protobuf.CodedOutputStream;
import io.vidocq.champollion.protobuf.FieldType;
import io.vidocq.champollion.protobuf.MalformedProtobufException;
import io.vidocq.champollion.protobuf.Parser;
import io.vidocq.champollion.protobuf.ProtobufField;
import io.vidocq.champollion.protobuf.ProtobufMessage;
import io.vidocq.champollion.protobuf.UnknownFieldSet;
import io.vidocq.champollion.protobuf.WireFormat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflective runtime binding for records annotated {@link ProtobufMessage}.
 *
 * <p>Introspection on first encounter of the class: we build a
 * {@link BindingPlan} via {@link RecordComponent#getAnnotation(Class)} and
 * {@link MethodHandle}s for the canonical constructor and accessors.
 * Binding plan cached in a {@link ConcurrentHashMap} keyed by {@link Class} — amortized cost
 * after warmup. No {@code synchronized}, no {@code ThreadLocal}.</p>
 *
 * <p>The static mode ({@code champollion-protobuf-codegen}) may later
 * produce a compiled binding that will supersede this runtime via {@link java.util.ServiceLoader}.</p>
 */
public final class RuntimeBinding {

    private static final ConcurrentHashMap<Class<?>, BindingPlan> PLANS = new ConcurrentHashMap<>();

    private RuntimeBinding() {}

    @SuppressWarnings("unchecked")
    public static <T> Parser<T> parser(Class<T> type) {
        BindingPlan plan = planFor(type);
        return in -> (T) readMessage(plan, in);
    }

    public static void writeTo(Object message, CodedOutputStream out) throws IOException {
        BindingPlan plan = planFor(message.getClass());
        writeMessage(plan, message, out);
    }

    public static int getSerializedSize(Object message) {
        BindingPlan plan = planFor(message.getClass());
        return computeMessageSize(plan, message);
    }

    // ================================================================== Plan resolution

    private static BindingPlan planFor(Class<?> type) {
        BindingPlan cached = PLANS.get(type);
        if (cached != null) return cached;
        BindingPlan computed = buildPlan(type);
        BindingPlan existing = PLANS.putIfAbsent(type, computed);
        return existing != null ? existing : computed;
    }

    private static BindingPlan buildPlan(Class<?> type) {
        if (!type.isRecord()) {
            throw new IllegalArgumentException(
                    "Champollion runtime binding only supports records, got " + type);
        }
        if (!type.isAnnotationPresent(ProtobufMessage.class)) {
            throw new IllegalArgumentException(
                    type + " is not annotated with @ProtobufMessage");
        }
        RecordComponent[] components = type.getRecordComponents();
        List<FieldBinding> fields = new ArrayList<>(components.length);
        Class<?>[] componentTypes = new Class<?>[components.length];
        MethodHandles.Lookup lookup = lookupFor(type);
        int unknownFieldsIndex = -1;
        MethodHandle unknownFieldsGetter = null;
        for (int i = 0; i < components.length; i++) {
            RecordComponent rc = components[i];
            componentTypes[i] = rc.getType();
            if (isUnknownFieldsComponent(rc)) {
                unknownFieldsIndex = i;
                unknownFieldsGetter = unknownFieldsGetter(lookup, rc);
            } else {
                fields.add(fieldBinding(type, rc, i, lookup));
            }
        }
        fields.sort(Comparator.comparingInt(f -> f.number));
        MethodHandle constructor;
        try {
            constructor = lookup.findConstructor(type, MethodType.methodType(void.class, componentTypes));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException(
                    "Cannot find canonical constructor for record " + type, e);
        }
        Map<Integer, FieldBinding> byNumber = HashMap.newHashMap(fields.size());
        for (FieldBinding fb : fields) byNumber.put(fb.number, fb);
        return new BindingPlan(type, fields, byNumber, constructor, componentTypes,
                unknownFieldsIndex, unknownFieldsGetter);
    }

    private static MethodHandles.Lookup lookupFor(Class<?> type) {
        try {
            return MethodHandles.privateLookupIn(type, MethodHandles.lookup());
        } catch (IllegalAccessException _) {
            // Fallback: a public record in an openless module (for
            // example loaded dynamically in the unnamed module) remains
            // usable via publicLookup — its canonical ctor and accessors are
            // public by construction.
            return MethodHandles.publicLookup();
        }
    }

    /**
     * The UnknownFieldSet component: it must be called "unknownFields" (by convention) and have the
     * UnknownFieldSet type. It is not annotated @ProtobufField.
     */
    private static boolean isUnknownFieldsComponent(RecordComponent rc) {
        return rc.getType() == UnknownFieldSet.class && "unknownFields".equals(rc.getName());
    }

    private static MethodHandle unknownFieldsGetter(MethodHandles.Lookup lookup, RecordComponent rc) {
        try {
            return lookup.unreflect(rc.getAccessor());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static FieldBinding fieldBinding(Class<?> type, RecordComponent rc, int componentIndex,
                                             MethodHandles.Lookup lookup) {
        ProtobufField pf = rc.getAnnotation(ProtobufField.class);
        if (pf == null) {
            throw new IllegalArgumentException(
                    "Record component " + type.getSimpleName() + "." + rc.getName()
                            + " is missing @ProtobufField");
        }
        boolean isMap = pf.type() == FieldType.MAP;
        boolean repeated = !isMap && List.class.isAssignableFrom(rc.getType());
        Class<?> elementType;
        Class<?> mapValueClass = null;
        if (isMap) {
            if (!Map.class.isAssignableFrom(rc.getType())) {
                throw new IllegalArgumentException(
                        "@ProtobufField(type=MAP) requires Java type Map<K,V> for "
                                + rc.getName());
            }
            mapValueClass = resolveMapValueClass(rc);
            elementType = Map.class;
        } else if (repeated) {
            elementType = resolveListElementType(rc);
        } else {
            elementType = rc.getType();
        }
        MethodHandle getter;
        try {
            getter = lookup.unreflect(rc.getAccessor());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "Cannot access record component " + rc.getName()
                            + ". Add 'opens " + type.getPackageName()
                            + " to io.vidocq.champollion.protobuf' in the consumer module-info.", e);
        }
        return new FieldBinding(pf, repeated, elementType, mapValueClass, componentIndex, getter);
    }

    private static Class<?> resolveListElementType(RecordComponent rc) {
        Type generic = rc.getGenericType();
        if (!(generic instanceof ParameterizedType pt)) {
            throw new IllegalArgumentException(
                    "List record component " + rc.getName() + " must be parameterized (e.g. List<String>).");
        }
        Type arg = pt.getActualTypeArguments()[0];
        if (arg instanceof Class<?> c) return c;
        throw new IllegalArgumentException(
                "Cannot resolve List element type for " + rc.getName() + " (got " + arg + ")");
    }

    /** Extracts V from Map<K,V>. Used for MAP with mapValue=MESSAGE. */
    private static Class<?> resolveMapValueClass(RecordComponent rc) {
        Type generic = rc.getGenericType();
        if (!(generic instanceof ParameterizedType pt)) return Object.class;
        Type[] args = pt.getActualTypeArguments();
        if (args.length < 2) return Object.class;
        Type v = args[1];
        if (v instanceof Class<?> c) return c;
        return Object.class;
    }

    private static byte[] encodeTagBytes(int fieldNumber, int wireType) {
        int tag = WireFormat.makeTag(fieldNumber, wireType);
        int size = CodedOutputStream.computeRawVarint32Size(tag);
        byte[] bytes = new byte[size];
        int pos = 0;
        while ((tag & ~0x7F) != 0) {
            bytes[pos++] = (byte) ((tag & 0x7F) | 0x80);
            tag >>>= 7;
        }
        bytes[pos] = (byte) tag;
        return bytes;
    }

    // ================================================================== Write

    private static void writeMessage(BindingPlan plan, Object message, CodedOutputStream out) throws IOException {
        for (FieldBinding fb : plan.fields) {
            Object value;
            try {
                value = fb.getter.invoke(message);
            } catch (Throwable t) {
                throw new IOException("Failed to read " + plan.recordType.getSimpleName()
                        + "#" + fb.number, t);
            }
            if (value != null) writeField(fb, value, out);
        }
        // Re-emits unknown fields at the end of the message (forward compat).
        if (plan.unknownFieldsIndex >= 0) {
            UnknownFieldSet ufs = unknownFields(plan, message);
            if (ufs != null && !ufs.isEmpty()) ufs.writeTo(out);
        }
    }

    private static void writeField(FieldBinding fb, Object value, CodedOutputStream out) throws IOException {
        if (fb.type == FieldType.MAP) {
            writeMap(fb, (Map<?, ?>) value, out);
        } else if (fb.repeated) {
            writeRepeated(fb, (List<?>) value, out);
        } else if (fb.explicitPresence || !isDefault(fb, value)) {
            // Proto3 implicit presence omits default values on the wire;
            // EXPLICIT presence (proto2 / Edition 2023 override) always writes them.
            out.writeRawBytes(fb.tagBytes, 0, fb.tagBytes.length);
            writeScalar(fb, value, out);
        }
    }

    private static UnknownFieldSet unknownFields(BindingPlan plan, Object message) throws IOException {
        try {
            return (UnknownFieldSet) plan.unknownFieldsGetter.invoke(message);
        } catch (Throwable t) {
            throw new IOException("Failed to read unknownFields", t);
        }
    }

    private static void writeRepeated(FieldBinding fb, List<?> list, CodedOutputStream out) throws IOException {
        if (list.isEmpty()) return;
        // Filters nulls (unknown enum values stored as null by the lenient parser).
        // Proto3 forward compat: omit these values when re-serializing.
        if (fb.packed) {
            writePacked(fb, list, out);
        } else {
            for (Object item : list) {
                if (item != null) {
                    out.writeRawBytes(fb.tagBytes, 0, fb.tagBytes.length);
                    writeScalar(fb, item, out);
                }
            }
        }
    }

    private static void writePacked(FieldBinding fb, List<?> list, CodedOutputStream out) throws IOException {
        int payloadSize = packedPayloadSize(fb, list);
        if (payloadSize == 0) return;
        out.writeRawBytes(fb.packedTagBytes, 0, fb.packedTagBytes.length);
        out.writeRawVarint32(payloadSize);
        for (Object item : list) {
            if (item != null) writeScalarNoTag(fb, item, out);
        }
    }

    private static int packedPayloadSize(FieldBinding fb, List<?> list) {
        int payload = 0;
        for (Object item : list) {
            if (item != null) payload += scalarSize(fb, item);
        }
        return payload;
    }

    private static void writeScalar(FieldBinding fb, Object value, CodedOutputStream out) throws IOException {
        writeScalarNoTag(fb, value, out);
    }

    private static void writeScalarNoTag(FieldBinding fb, Object value, CodedOutputStream out) throws IOException {
        switch (fb.type) {
            case INT32 -> out.writeInt32NoTag((int) value);
            case INT64 -> out.writeInt64NoTag((long) value);
            case UINT32 -> out.writeUInt32NoTag((int) value);
            case UINT64 -> out.writeUInt64NoTag((long) value);
            case SINT32 -> out.writeSInt32NoTag((int) value);
            case SINT64 -> out.writeSInt64NoTag((long) value);
            case BOOL -> out.writeBoolNoTag((boolean) value);
            case ENUM -> out.writeEnumNoTag(EnumValueMap.forClass(value.getClass()).valueOf((Enum<?>) value));
            case FIXED32 -> out.writeFixed32NoTag((int) value);
            case SFIXED32 -> out.writeSFixed32NoTag((int) value);
            case FLOAT -> out.writeFloatNoTag((float) value);
            case FIXED64 -> out.writeFixed64NoTag((long) value);
            case SFIXED64 -> out.writeSFixed64NoTag((long) value);
            case DOUBLE -> out.writeDoubleNoTag((double) value);
            case STRING -> out.writeStringNoTag((String) value);
            case BYTES -> out.writeBytesNoTag((byte[]) value);
            case MESSAGE -> {
                BindingPlan nested = planFor(value.getClass());
                int size = computeMessageSize(nested, value);
                out.writeRawVarint32(size);
                writeMessage(nested, value, out);
            }
            case MAP -> throw new IllegalStateException(
                    "MAP type should be handled via writeMap, not writeScalarNoTag");
        }
    }

    private static void writeMap(FieldBinding fb, Map<?, ?> map, CodedOutputStream out) throws IOException {
        if (map.isEmpty()) return;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            Object k = e.getKey();
            Object v = e.getValue();
            if (k == null) k = defaultMapKey(fb.mapKeyType);
            if (v == null) {
                Object dflt = defaultForMapType(fb.mapValueType, fb.mapValueClass);
                if (dflt == null) {
                    // ENUM unknown / MESSAGE null → encode entry avec key + value absente
                    int entrySize = fb.mapKeyTag.length + scalarSizeTyped(fb.mapKeyType, k);
                    out.writeRawBytes(fb.tagBytes, 0, fb.tagBytes.length);
                    out.writeRawVarint32(entrySize);
                    out.writeRawBytes(fb.mapKeyTag, 0, fb.mapKeyTag.length);
                    writeScalarTyped(fb.mapKeyType, k, out);
                    continue;
                }
                v = dflt;
            }
            int entrySize = fb.mapKeyTag.length + scalarSizeTyped(fb.mapKeyType, k)
                          + fb.mapValueTag.length + scalarSizeTyped(fb.mapValueType, v);
            out.writeRawBytes(fb.tagBytes, 0, fb.tagBytes.length);
            out.writeRawVarint32(entrySize);
            out.writeRawBytes(fb.mapKeyTag, 0, fb.mapKeyTag.length);
            writeScalarTyped(fb.mapKeyType, k, out);
            out.writeRawBytes(fb.mapValueTag, 0, fb.mapValueTag.length);
            writeScalarTyped(fb.mapValueType, v, out);
        }
    }

    /**
     * Default of a null map key. Proto3 §maps only allows integral, bool and string keys, whose
     * default is never null; any other key type is a broken binding.
     */
    private static Object defaultMapKey(FieldType keyType) {
        Object k = defaultForMapType(keyType, null);
        if (k == null) throw new IllegalStateException("Map key type " + keyType + " is forbidden by proto3 §maps");
        return k;
    }

    private static Object defaultForMapType(FieldType t, Class<?> messageClass) {
        return switch (t) {
            case INT32, UINT32, SINT32, FIXED32, SFIXED32 -> 0;
            case INT64, UINT64, SINT64, FIXED64, SFIXED64 -> 0L;
            case BOOL -> false;
            case FLOAT -> 0.0f;
            case DOUBLE -> 0.0;
            case STRING -> "";
            case BYTES -> new byte[0];
            case ENUM -> {
                // Default proto3 §enum: the first declared constant (value=0).
                // If an unknown enum is received on the wire, the caller distinguishes it via byValue→null.
                if (messageClass != null && messageClass.isEnum()) {
                    yield messageClass.getEnumConstants()[0];
                }
                yield null;
            }
            case MESSAGE, MAP -> null;
        };
    }

    private static void writeScalarTyped(FieldType type, Object value, CodedOutputStream out) throws IOException {
        switch (type) {
            case INT32 -> out.writeInt32NoTag((int) value);
            case INT64 -> out.writeInt64NoTag((long) value);
            case UINT32 -> out.writeUInt32NoTag((int) value);
            case UINT64 -> out.writeUInt64NoTag((long) value);
            case SINT32 -> out.writeSInt32NoTag((int) value);
            case SINT64 -> out.writeSInt64NoTag((long) value);
            case BOOL -> out.writeBoolNoTag((boolean) value);
            case ENUM -> out.writeEnumNoTag(EnumValueMap.forClass(value.getClass()).valueOf((Enum<?>) value));
            case FIXED32 -> out.writeFixed32NoTag((int) value);
            case SFIXED32 -> out.writeSFixed32NoTag((int) value);
            case FLOAT -> out.writeFloatNoTag((float) value);
            case FIXED64 -> out.writeFixed64NoTag((long) value);
            case SFIXED64 -> out.writeSFixed64NoTag((long) value);
            case DOUBLE -> out.writeDoubleNoTag((double) value);
            case STRING -> out.writeStringNoTag((String) value);
            case BYTES -> out.writeBytesNoTag((byte[]) value);
            case MESSAGE -> {
                BindingPlan nested = planFor(value.getClass());
                int size = computeMessageSize(nested, value);
                out.writeRawVarint32(size);
                writeMessage(nested, value, out);
            }
            case MAP -> throw new IllegalStateException("Nested MAP is forbidden by proto3 §maps");
        }
    }

    private static int scalarSizeTyped(FieldType type, Object value) {
        return switch (type) {
            case INT32, INT64 -> {
                long v = (type == FieldType.INT32) ? (int) value : (long) value;
                yield CodedOutputStream.computeRawVarint64Size(v);
            }
            case UINT32 -> CodedOutputStream.computeRawVarint32Size((int) value);
            case UINT64 -> CodedOutputStream.computeRawVarint64Size((long) value);
            case SINT32 -> CodedOutputStream.computeRawVarint32Size(CodedOutputStream.encodeZigZag32((int) value));
            case SINT64 -> CodedOutputStream.computeRawVarint64Size(CodedOutputStream.encodeZigZag64((long) value));
            case BOOL -> 1;
            case ENUM -> {
                int number = EnumValueMap.forClass(value.getClass()).valueOf((Enum<?>) value);
                // Negative ENUM (NEG=-1) → writeInt32NoTag sign-extends to 10 bytes.
                yield number < 0 ? CodedOutputStream.computeRawVarint64Size(number)
                             : CodedOutputStream.computeRawVarint32Size(number);
            }
            case FIXED32, SFIXED32, FLOAT -> 4;
            case FIXED64, SFIXED64, DOUBLE -> 8;
            case STRING -> {
                int utf8 = ((String) value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                yield CodedOutputStream.computeRawVarint32Size(utf8) + utf8;
            }
            case BYTES -> {
                int n = ((byte[]) value).length;
                yield CodedOutputStream.computeRawVarint32Size(n) + n;
            }
            case MESSAGE -> {
                BindingPlan nested = planFor(value.getClass());
                int s = computeMessageSize(nested, value);
                yield CodedOutputStream.computeRawVarint32Size(s) + s;
            }
            case MAP -> throw new IllegalStateException("Nested MAP is forbidden");
        };
    }

    // ================================================================== Size

    private static int computeMessageSize(BindingPlan plan, Object message) {
        int total = 0;
        for (FieldBinding fb : plan.fields) {
            Object value;
            try {
                value = fb.getter.invoke(message);
            } catch (Throwable t) {
                throw new IllegalStateException("Failed to read " + plan.recordType.getSimpleName()
                        + "#" + fb.number, t);
            }
            if (value != null) total += fieldSize(fb, value);
        }
        // UnknownFieldSet: its size counts in the output buffer.
        if (plan.unknownFieldsIndex >= 0) {
            UnknownFieldSet ufs;
            try {
                ufs = (UnknownFieldSet) plan.unknownFieldsGetter.invoke(message);
            } catch (Throwable t) {
                throw new IllegalStateException("Failed to read unknownFields", t);
            }
            if (ufs != null) total += ufs.getSerializedSize();
        }
        return total;
    }

    private static int fieldSize(FieldBinding fb, Object value) {
        if (fb.type == FieldType.MAP) return mapSize(fb, (Map<?, ?>) value);
        if (fb.repeated) return repeatedSize(fb, (List<?>) value);
        if (fb.explicitPresence || !isDefault(fb, value)) return fb.tagBytes.length + scalarSize(fb, value);
        return 0;
    }

    private static int mapSize(FieldBinding fb, Map<?, ?> map) {
        int total = 0;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            Object k = e.getKey() != null ? e.getKey() : defaultForMapType(fb.mapKeyType, null);
            Object v = e.getValue() != null ? e.getValue() : defaultForMapType(fb.mapValueType, fb.mapValueClass);
            // A null value without default (ENUM unknown / MESSAGE null) is encoded with the key alone.
            int entrySize = fb.mapKeyTag.length + scalarSizeTyped(fb.mapKeyType, k);
            if (v != null) entrySize += fb.mapValueTag.length + scalarSizeTyped(fb.mapValueType, v);
            total += fb.tagBytes.length + CodedOutputStream.computeRawVarint32Size(entrySize) + entrySize;
        }
        return total;
    }

    private static int repeatedSize(FieldBinding fb, List<?> list) {
        if (fb.packed) {
            int payload = packedPayloadSize(fb, list);
            return payload == 0 ? 0
                    : fb.packedTagBytes.length + CodedOutputStream.computeRawVarint32Size(payload) + payload;
        }
        int total = 0;
        for (Object item : list) {
            if (item != null) total += fb.tagBytes.length + scalarSize(fb, item);
        }
        return total;
    }

    private static int scalarSize(FieldBinding fb, Object value) {
        return switch (fb.type) {
            case INT32, INT64 -> {
                long v = (fb.type == FieldType.INT32) ? (int) value : (long) value;
                yield CodedOutputStream.computeRawVarint64Size(v);
            }
            case UINT32 -> CodedOutputStream.computeRawVarint32Size((int) value);
            case UINT64 -> CodedOutputStream.computeRawVarint64Size((long) value);
            case SINT32 -> CodedOutputStream.computeRawVarint32Size(CodedOutputStream.encodeZigZag32((int) value));
            case SINT64 -> CodedOutputStream.computeRawVarint64Size(CodedOutputStream.encodeZigZag64((long) value));
            case BOOL -> 1;
            case ENUM -> {
                int number = EnumValueMap.forClass(value.getClass()).valueOf((Enum<?>) value);
                // Negative ENUM (NEG=-1) → writeInt32NoTag sign-extends to 10 bytes.
                yield number < 0 ? CodedOutputStream.computeRawVarint64Size(number)
                             : CodedOutputStream.computeRawVarint32Size(number);
            }
            case FIXED32, SFIXED32, FLOAT -> 4;
            case FIXED64, SFIXED64, DOUBLE -> 8;
            case STRING -> {
                int utf8 = ((String) value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                yield CodedOutputStream.computeRawVarint32Size(utf8) + utf8;
            }
            case BYTES -> {
                int n = ((byte[]) value).length;
                yield CodedOutputStream.computeRawVarint32Size(n) + n;
            }
            case MESSAGE -> {
                BindingPlan nested = planFor(value.getClass());
                int n = computeMessageSize(nested, value);
                yield CodedOutputStream.computeRawVarint32Size(n) + n;
            }
            case MAP -> throw new IllegalStateException(
                    "MAP type should be handled via writeMap, not scalarSize");
        };
    }

    private static boolean isDefault(FieldBinding fb, Object value) {
        return switch (fb.type) {
            case INT32, UINT32, SINT32, FIXED32, SFIXED32, ENUM -> {
                if (value instanceof Enum<?> e) yield EnumValueMap.forClass(e.getClass()).valueOf(e) == 0;
                yield ((int) value) == 0;
            }
            case INT64, UINT64, SINT64, FIXED64, SFIXED64 -> ((long) value) == 0L;
            case BOOL -> !((boolean) value);
            case FLOAT -> ((float) value) == 0.0f;
            case DOUBLE -> ((double) value) == 0.0;
            case STRING -> ((String) value).isEmpty();
            case BYTES -> ((byte[]) value).length == 0;
            case MESSAGE -> false; // a non-null embedded message is never "default"
            case MAP -> ((Map<?, ?>) value).isEmpty();
        };
    }

    // ================================================================== Read

    private static Object readMessage(BindingPlan plan, CodedInputStream in) throws IOException {
        Object[] slots = new Object[plan.componentTypes.length];
        boolean[] hasValue = new boolean[plan.componentTypes.length];
        // UnknownFieldSet: if the record has an `unknownFields` component, collect
        // unknown fields so they can be re-emitted during serialization (forward compat).
        UnknownFieldSet.Builder unknownFieldsBuilder =
                plan.unknownFieldsIndex >= 0 ? UnknownFieldSet.newBuilder() : null;

        Map<Integer, ByteArrayOutputStream> messageBuffers =
                readFields(plan, in, slots, hasValue, unknownFieldsBuilder);
        if (messageBuffers != null) parseMergedMessages(plan, messageBuffers, slots);

        // Fill empty slots with the record defaults.
        for (FieldBinding fb : plan.fields) {
            if (!hasValue[fb.componentIndex]) slots[fb.componentIndex] = defaultFor(fb);
        }
        // The unknownFields slot must always be set (the record expects it non-null):
        // UnknownFieldSet.EMPTY or the Builder build result.
        if (plan.unknownFieldsIndex >= 0) {
            slots[plan.unknownFieldsIndex] = unknownFieldsBuilder != null
                    ? unknownFieldsBuilder.build()
                    : UnknownFieldSet.EMPTY;
        }

        try {
            return plan.constructor.invokeWithArguments(slots);
        } catch (Throwable t) {
            throw new IOException("Failed to invoke canonical constructor of "
                    + plan.recordType.getSimpleName(), t);
        }
    }

    /**
     * Reads the fields of a message into {@code slots}; returns the payloads of the singular
     * MESSAGE fields, accumulated for the proto3 §field-message-merge (null when there is none).
     */
    private static Map<Integer, ByteArrayOutputStream> readFields(
            BindingPlan plan, CodedInputStream in, Object[] slots, boolean[] hasValue,
            UnknownFieldSet.Builder unknownFieldsBuilder) throws IOException {
        Map<Integer, ByteArrayOutputStream> messageBuffers = null;
        for (int tag = in.readTag(); tag != 0; tag = in.readTag()) {
            FieldBinding fb = plan.byNumber.get(WireFormat.getTagFieldNumber(tag));
            if (fb == null) {
                in.skipField(tag, unknownFieldsBuilder);
            } else {
                if (fb.oneofGroup != null && !fb.oneofGroup.isEmpty()) {
                    clearOneofSiblings(plan, fb, slots, hasValue, messageBuffers);
                }
                int wireType = WireFormat.getTagWireType(tag);
                // Proto3 §field-message-merge spec: if a singular MESSAGE has already been seen,
                // accumulate bytes to reparse only once at the end of the message.
                if (fb.type == FieldType.MESSAGE && !fb.repeated
                        && wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                    messageBuffers = bufferMessage(messageBuffers, fb, in);
                    hasValue[fb.componentIndex] = true;
                } else {
                    readInto(fb, wireType, slots, hasValue, in);
                }
            }
        }
        return messageBuffers;
    }

    /** Appends the payload of a singular MESSAGE field to its buffer; returns the buffers, created when needed. */
    private static Map<Integer, ByteArrayOutputStream> bufferMessage(
            Map<Integer, ByteArrayOutputStream> messageBuffers, FieldBinding fb, CodedInputStream in)
            throws IOException {
        Map<Integer, ByteArrayOutputStream> buffers = messageBuffers != null ? messageBuffers : new HashMap<>();
        byte[] payload = in.readRawBytes(in.readRawVarint32());
        buffers.computeIfAbsent(fb.componentIndex, _ -> new ByteArrayOutputStream())
                .write(payload, 0, payload.length);
        return buffers;
    }

    /**
     * Proto3 §oneof spec: if multiple fields from the same oneof arrive on the wire, only the last
     * one is kept. Clears the other slots in the same oneofGroup before writing the new one.
     */
    private static void clearOneofSiblings(BindingPlan plan, FieldBinding fb, Object[] slots, boolean[] hasValue,
                                           Map<Integer, ByteArrayOutputStream> messageBuffers) {
        for (FieldBinding other : plan.fields) {
            if (other != fb && fb.oneofGroup.equals(other.oneofGroup)) {
                slots[other.componentIndex] = null;
                hasValue[other.componentIndex] = false;
                if (messageBuffers != null) messageBuffers.remove(other.componentIndex);
            }
        }
    }

    /** Parses the merged sub-messages (proto3 §field-message-merge). */
    private static void parseMergedMessages(BindingPlan plan, Map<Integer, ByteArrayOutputStream> messageBuffers,
                                            Object[] slots) throws IOException {
        for (var entry : messageBuffers.entrySet()) {
            FieldBinding fb = plan.fields.stream()
                    .filter(f -> f.componentIndex == entry.getKey())
                    .findFirst().orElseThrow();
            byte[] merged = entry.getValue().toByteArray();
            CodedInputStream nestedIn = CodedInputStream.newInstance(merged);
            slots[fb.componentIndex] = readMessage(planFor(fb.elementType), nestedIn);
        }
    }

    private static void readInto(FieldBinding fb, int wireType, Object[] slots, boolean[] hasValue,
                                 CodedInputStream in) throws IOException {
        if (fb.type == FieldType.MAP) {
            readMapEntry(fb, wireType, slots, hasValue, in);
        } else if (fb.repeated) {
            readRepeated(fb, wireType, slots, hasValue, in);
        } else {
            boolean expected = fb.type == FieldType.MESSAGE
                    ? wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED
                    : wireType == fb.type.wireType();
            if (!expected) throw MalformedProtobufException.invalidWireType(wireType);
            slots[fb.componentIndex] = readScalar(fb, in);
            hasValue[fb.componentIndex] = true;
        }
    }

    @SuppressWarnings("unchecked")
    private static void readMapEntry(FieldBinding fb, int wireType, Object[] slots, boolean[] hasValue,
                                     CodedInputStream in) throws IOException {
        if (wireType != WireFormat.WIRETYPE_LENGTH_DELIMITED) {
            throw MalformedProtobufException.invalidWireType(wireType);
        }
        int idx = fb.componentIndex;
        Map<Object, Object> map = (Map<Object, Object>) slots[idx];
        if (map == null) {
            map = new java.util.LinkedHashMap<>();
            slots[idx] = map;
            hasValue[idx] = true;
        }
        int oldLimit = in.pushLimit(in.readRawVarint32());
        Object k = defaultForMapType(fb.mapKeyType, null);
        Object v = defaultForMapType(fb.mapValueType, fb.mapValueClass);
        while (!in.isAtEnd()) {
            int t = in.readTag();
            switch (WireFormat.getTagFieldNumber(t)) {
                case 1 -> k = readScalarTyped(fb.mapKeyType, fb.mapValueClass, in);
                case 2 -> v = readScalarTyped(fb.mapValueType, fb.mapValueClass, in);
                default -> in.skipField(t);
            }
        }
        in.popLimit(oldLimit);
        map.put(k, v);
    }

    @SuppressWarnings("unchecked")
    private static void readRepeated(FieldBinding fb, int wireType, Object[] slots, boolean[] hasValue,
                                     CodedInputStream in) throws IOException {
        int idx = fb.componentIndex;
        List<Object> list = (List<Object>) slots[idx];
        if (list == null) {
            list = new ArrayList<>();
            slots[idx] = list;
            hasValue[idx] = true;
        }
        // The reader must accept PACKED and UNPACKED for the same field
        // (proto3 / Edition 2023 spec), regardless of the value of fb.packed
        // on the binding side — it is only a write hint.
        if (wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED && fb.type.packable()) {
            int oldLimit = in.pushLimit(in.readRawVarint32());
            while (!in.isAtEnd()) {
                list.add(readScalar(fb, in));
            }
            in.popLimit(oldLimit);
        } else if (wireType == fb.type.wireType()
                || (fb.type == FieldType.MESSAGE && wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED)) {
            list.add(readScalar(fb, in));
        } else {
            throw MalformedProtobufException.invalidWireType(wireType);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object readScalar(FieldBinding fb, CodedInputStream in) throws IOException {
        return switch (fb.type) {
            case INT32 -> in.readInt32();
            case INT64 -> in.readInt64();
            case UINT32 -> in.readUInt32();
            case UINT64 -> in.readUInt64();
            case SINT32 -> in.readSInt32();
            case SINT64 -> in.readSInt64();
            case BOOL -> in.readBool();
            case ENUM -> {
                int value = in.readEnum();
                Class<?> ec = fb.elementType;
                if (!ec.isEnum()) {
                    throw new IOException("ENUM field bound to non-enum class " + ec);
                }
                // EnumValueMap honors @ProtoEnumValue + ordinal fallback.
                // null = unknown value (proto3 forward compat).
                yield EnumValueMap.forClass(ec).byValue(value);
            }
            case FIXED32 -> in.readFixed32();
            case SFIXED32 -> in.readSFixed32();
            case FLOAT -> in.readFloat();
            case FIXED64 -> in.readFixed64();
            case SFIXED64 -> in.readSFixed64();
            case DOUBLE -> in.readDouble();
            case STRING -> in.readStringRequireUtf8();
            case BYTES -> in.readBytes();
            case MESSAGE -> {
                int size = in.readRawVarint32();
                int oldLimit = in.pushLimit(size);
                in.incrementRecursionDepth();
                Object nested = readMessage(planFor(fb.elementType), in);
                in.decrementRecursionDepth();
                in.popLimit(oldLimit);
                yield nested;
            }
            case MAP -> throw new IllegalStateException(
                    "MAP type should be handled via readInto MAP branch");
        };
    }

    /** Lit une valeur scalaire ou message d'une map entry. */
    private static Object readScalarTyped(FieldType type, Class<?> messageClass,
                                          CodedInputStream in) throws IOException {
        return switch (type) {
            case INT32 -> in.readInt32();
            case INT64 -> in.readInt64();
            case UINT32 -> in.readUInt32();
            case UINT64 -> in.readUInt64();
            case SINT32 -> in.readSInt32();
            case SINT64 -> in.readSInt64();
            case BOOL -> in.readBool();
            case ENUM -> {
                int value = in.readEnum();
                if (messageClass == null || !messageClass.isEnum()) yield null;
                // Same lookup as an enum field: @ProtoEnumValue numbers, ordinal fallback, null if unknown.
                yield EnumValueMap.forClass(messageClass).byValue(value);
            }
            case FIXED32 -> in.readFixed32();
            case SFIXED32 -> in.readSFixed32();
            case FLOAT -> in.readFloat();
            case FIXED64 -> in.readFixed64();
            case SFIXED64 -> in.readSFixed64();
            case DOUBLE -> in.readDouble();
            case STRING -> in.readStringRequireUtf8();
            case BYTES -> in.readBytes();
            case MESSAGE -> {
                int size = in.readRawVarint32();
                int oldLimit = in.pushLimit(size);
                in.incrementRecursionDepth();
                Object nested = readMessage(planFor(messageClass), in);
                in.decrementRecursionDepth();
                in.popLimit(oldLimit);
                yield nested;
            }
            case MAP -> throw new IllegalStateException("Nested MAP is forbidden");
        };
    }

    private static Object defaultFor(FieldBinding fb) {
        if (fb.repeated) return List.of();
        // explicitPresence (oneof / proto2 / Edition 2023 EXPLICIT): a field
        // absent from the wire remains null in Java to distinguish "absent" from "present
        // with default value". The caller uses null to avoid writing the field.
        // Requires the elementType not to be a primitive — this is guaranteed
        // for oneofs (Integer/Long/etc. wrapper types).
        if (fb.explicitPresence && !fb.elementType.isPrimitive()) {
            return null;
        }
        return switch (fb.type) {
            case INT32, UINT32, SINT32, FIXED32, SFIXED32 -> 0;
            case INT64, UINT64, SINT64, FIXED64, SFIXED64 -> 0L;
            case BOOL -> false;
            case FLOAT -> 0.0f;
            case DOUBLE -> 0.0;
            case STRING -> "";
            case BYTES -> new byte[0];
            case ENUM -> fb.elementType.getEnumConstants()[0];
            case MESSAGE -> null;
            case MAP -> new java.util.LinkedHashMap<>();
        };
    }

    // ================================================================== Data classes

    record BindingPlan(Class<?> recordType,
                       List<FieldBinding> fields,
                       Map<Integer, FieldBinding> byNumber,
                       MethodHandle constructor,
                       Class<?>[] componentTypes,
                       int unknownFieldsIndex,
                       MethodHandle unknownFieldsGetter) {

        // A plan is derived from its record type alone, and is cached once per type:
        // the type identifies it, the arrays derived from it add nothing.
        @Override public boolean equals(Object o) { return o instanceof BindingPlan other && recordType == other.recordType; }
        @Override public int hashCode() { return recordType.hashCode(); }
        @Override public String toString() { return "BindingPlan[" + recordType.getName() + "]"; }

        /** Compat ctor pour les call-sites anciens (sans support UnknownFieldSet). */
        BindingPlan(Class<?> recordType, List<FieldBinding> fields,
                    Map<Integer, FieldBinding> byNumber, MethodHandle constructor,
                    Class<?>[] componentTypes) {
            this(recordType, fields, byNumber, constructor, componentTypes, -1, null);
        }
    }

    static final class FieldBinding {
        final int number;
        final FieldType type;
        final boolean repeated;
        final boolean packed;
        final boolean explicitPresence;
        final String oneofGroup;
        final Class<?> elementType;
        final int componentIndex;
        final MethodHandle getter;
        final byte[] tagBytes;
        final byte[] packedTagBytes;
        // FieldType.MAP only — null pour les autres types
        final FieldType mapKeyType;
        final FieldType mapValueType;
        final Class<?> mapValueClass;
        final byte[] mapKeyTag;
        final byte[] mapValueTag;

        /**
         * Binds a record component from its {@code @ProtobufField}; {@code mapValueClass} is the
         * value class of a MAP field (null for the other types).
         */
        FieldBinding(ProtobufField pf, boolean repeated, Class<?> elementType, Class<?> mapValueClass,
                     int componentIndex, MethodHandle getter) {
            this.number = pf.number();
            this.type = pf.type();
            this.repeated = repeated;
            this.packed = repeated && pf.packed() && pf.type().packable();
            this.explicitPresence = pf.explicitPresence();
            this.oneofGroup = pf.oneofGroup();
            this.elementType = elementType;
            this.componentIndex = componentIndex;
            this.getter = getter;
            this.tagBytes = encodeTagBytes(pf.number(), pf.type().wireType());
            this.packedTagBytes = encodeTagBytes(pf.number(), WireFormat.WIRETYPE_LENGTH_DELIMITED);
            if (pf.type() == FieldType.MAP) {
                this.mapKeyType = pf.mapKey();
                this.mapValueType = pf.mapValue();
                this.mapValueClass = mapValueClass;
                this.mapKeyTag = encodeTagBytes(1, pf.mapKey().wireType());
                this.mapValueTag = encodeTagBytes(2, pf.mapValue().wireType());
            } else {
                this.mapKeyType = null;
                this.mapValueType = null;
                this.mapValueClass = null;
                this.mapKeyTag = null;
                this.mapValueTag = null;
            }
        }
    }
}
