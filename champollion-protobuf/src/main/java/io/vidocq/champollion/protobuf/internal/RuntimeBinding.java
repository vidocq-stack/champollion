package io.vidocq.champollion.protobuf.internal;

import io.vidocq.champollion.protobuf.CodedInputStream;
import io.vidocq.champollion.protobuf.CodedOutputStream;
import io.vidocq.champollion.protobuf.FieldType;
import io.vidocq.champollion.protobuf.MalformedProtobufException;
import io.vidocq.champollion.protobuf.Parser;
import io.vidocq.champollion.protobuf.ProtobufField;
import io.vidocq.champollion.protobuf.ProtobufMessage;
import io.vidocq.champollion.protobuf.WireFormat;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime binding reflectif pour records annotés {@link ProtobufMessage}.
 *
 * <p>Introspection à la première rencontre de la classe : on construit un
 * {@link BindingPlan} via {@link RecordComponent#getAnnotation(Class)} et des
 * {@link MethodHandle} pour le constructeur canonique et les accesseurs.
 * Plan caché dans une {@link ConcurrentHashMap} par {@link Class} — coût amorti
 * après warmup. Aucune {@code synchronized}, aucun {@code ThreadLocal}.</p>
 *
 * <p>Le mode statique ({@code champollion-protobuf-codegen}) pourra plus tard
 * produire un binding compilé qui supplantera ce runtime via {@link java.util.ServiceLoader}.</p>
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
        MethodHandles.Lookup lookup;
        try {
            lookup = MethodHandles.privateLookupIn(type, MethodHandles.lookup());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "Cannot access " + type + ". Add 'opens " + type.getPackageName()
                            + " to io.vidocq.champollion.protobuf;' in the consumer module-info.", e);
        }
        for (int i = 0; i < components.length; i++) {
            RecordComponent rc = components[i];
            componentTypes[i] = rc.getType();
            ProtobufField pf = rc.getAnnotation(ProtobufField.class);
            if (pf == null) {
                throw new IllegalArgumentException(
                        "Record component " + type.getSimpleName() + "." + rc.getName()
                                + " is missing @ProtobufField");
            }
            boolean repeated = List.class.isAssignableFrom(rc.getType());
            Class<?> elementType = repeated ? resolveListElementType(rc) : rc.getType();
            boolean packed = repeated && pf.packed() && pf.type().packable();
            MethodHandle getter;
            try {
                getter = lookup.unreflect(rc.getAccessor());
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(
                        "Cannot access record component " + rc.getName()
                                + ". Add 'opens " + type.getPackageName()
                                + " to io.vidocq.champollion.protobuf' in the consumer module-info.", e);
            }
            fields.add(new FieldBinding(
                    pf.number(),
                    pf.type(),
                    repeated,
                    packed,
                    elementType,
                    i,
                    getter,
                    encodeTagBytes(pf.number(), pf.type().wireType()),
                    encodeTagBytes(pf.number(), WireFormat.WIRETYPE_LENGTH_DELIMITED)));
        }
        fields.sort(Comparator.comparingInt(f -> f.number));
        MethodHandle constructor;
        try {
            constructor = lookup.findConstructor(type, MethodType.methodType(void.class, componentTypes));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException(
                    "Cannot find canonical constructor for record " + type, e);
        }
        Map<Integer, FieldBinding> byNumber = new HashMap<>(fields.size() * 2);
        for (FieldBinding fb : fields) byNumber.put(fb.number, fb);
        return new BindingPlan(type, fields, byNumber, constructor, componentTypes);
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
            if (value == null) continue;
            if (fb.repeated) {
                writeRepeated(fb, (List<?>) value, out);
            } else if (isDefault(fb, value)) {
                // Proto3 implicit presence : on omet les valeurs par défaut sur la wire.
                continue;
            } else {
                out.writeRawBytes(fb.tagBytes, 0, fb.tagBytes.length);
                writeScalar(fb, value, out);
            }
        }
    }

    private static void writeRepeated(FieldBinding fb, List<?> list, CodedOutputStream out) throws IOException {
        if (list.isEmpty()) return;
        if (fb.packed) {
            int payloadSize = 0;
            for (Object item : list) {
                payloadSize += scalarSize(fb, item);
            }
            out.writeRawBytes(fb.packedTagBytes, 0, fb.packedTagBytes.length);
            out.writeRawVarint32(payloadSize);
            for (Object item : list) {
                writeScalarNoTag(fb, item, out);
            }
        } else {
            for (Object item : list) {
                out.writeRawBytes(fb.tagBytes, 0, fb.tagBytes.length);
                writeScalar(fb, item, out);
            }
        }
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
            case ENUM -> out.writeEnumNoTag(((Enum<?>) value).ordinal());
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
        }
    }

    // ================================================================== Size

    private static int computeMessageSize(BindingPlan plan, Object message) {
        int total = 0;
        for (FieldBinding fb : plan.fields) {
            Object value;
            try {
                value = fb.getter.invoke(message);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
            if (value == null) continue;
            if (fb.repeated) {
                List<?> list = (List<?>) value;
                if (list.isEmpty()) continue;
                if (fb.packed) {
                    int payload = 0;
                    for (Object item : list) payload += scalarSize(fb, item);
                    total += fb.packedTagBytes.length
                            + CodedOutputStream.computeRawVarint32Size(payload)
                            + payload;
                } else {
                    for (Object item : list) {
                        total += fb.tagBytes.length + scalarSize(fb, item);
                    }
                }
            } else if (isDefault(fb, value)) {
                continue;
            } else {
                total += fb.tagBytes.length + scalarSize(fb, value);
            }
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
            case ENUM -> CodedOutputStream.computeRawVarint32Size(((Enum<?>) value).ordinal());
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
        };
    }

    private static boolean isDefault(FieldBinding fb, Object value) {
        return switch (fb.type) {
            case INT32, UINT32, SINT32, FIXED32, SFIXED32, ENUM -> {
                if (value instanceof Enum<?> e) yield e.ordinal() == 0;
                yield ((int) value) == 0;
            }
            case INT64, UINT64, SINT64, FIXED64, SFIXED64 -> ((long) value) == 0L;
            case BOOL -> !((boolean) value);
            case FLOAT -> ((float) value) == 0.0f;
            case DOUBLE -> ((double) value) == 0.0;
            case STRING -> ((String) value).isEmpty();
            case BYTES -> ((byte[]) value).length == 0;
            case MESSAGE -> false; // un message embarqué non-null est jamais "default"
        };
    }

    // ================================================================== Read

    private static Object readMessage(BindingPlan plan, CodedInputStream in) throws IOException {
        Object[] slots = new Object[plan.componentTypes.length];
        boolean[] hasValue = new boolean[plan.componentTypes.length];

        while (true) {
            int tag = in.readTag();
            if (tag == 0) break;
            int fieldNumber = WireFormat.getTagFieldNumber(tag);
            int wireType = WireFormat.getTagWireType(tag);
            FieldBinding fb = plan.byNumber.get(fieldNumber);
            if (fb == null) {
                in.skipField(tag);
                continue;
            }
            readInto(fb, wireType, slots, hasValue, in);
        }

        // Compléter les slots vides avec les défauts du record.
        for (int i = 0; i < slots.length; i++) {
            if (hasValue[i]) continue;
            FieldBinding fb = plan.fields.get(indexOfComponent(plan, i));
            slots[i] = defaultFor(fb);
        }

        try {
            return plan.constructor.invokeWithArguments(slots);
        } catch (Throwable t) {
            throw new IOException("Failed to invoke canonical constructor of "
                    + plan.recordType.getSimpleName(), t);
        }
    }

    private static int indexOfComponent(BindingPlan plan, int componentIndex) {
        for (int j = 0; j < plan.fields.size(); j++) {
            if (plan.fields.get(j).componentIndex == componentIndex) return j;
        }
        throw new IllegalStateException("No field binding for component " + componentIndex);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void readInto(FieldBinding fb, int wireType, Object[] slots, boolean[] hasValue,
                                 CodedInputStream in) throws IOException {
        int idx = fb.componentIndex;
        if (fb.repeated) {
            List list = (List) slots[idx];
            if (list == null) {
                list = new ArrayList<>();
                slots[idx] = list;
                hasValue[idx] = true;
            }
            if (fb.packed && wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED && fb.type.packable()) {
                int size = in.readRawVarint32();
                int oldLimit = in.pushLimit(size);
                while (!in.isAtEnd()) {
                    list.add(readScalar(fb, in));
                }
                in.popLimit(oldLimit);
            } else if (wireType == fb.type.wireType()) {
                list.add(readScalar(fb, in));
            } else if (fb.type == FieldType.MESSAGE && wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                list.add(readScalar(fb, in));
            } else {
                throw MalformedProtobufException.invalidWireType(wireType);
            }
        } else {
            if (fb.type == FieldType.MESSAGE) {
                if (wireType != WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                    throw MalformedProtobufException.invalidWireType(wireType);
                }
            } else if (wireType != fb.type.wireType()) {
                throw MalformedProtobufException.invalidWireType(wireType);
            }
            slots[idx] = readScalar(fb, in);
            hasValue[idx] = true;
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
                int ordinal = in.readEnum();
                Class<?> ec = fb.elementType;
                if (!ec.isEnum()) {
                    throw new IOException("ENUM field bound to non-enum class " + ec);
                }
                Object[] constants = ec.getEnumConstants();
                if (ordinal < 0 || ordinal >= constants.length) {
                    throw new IOException("Unknown enum ordinal " + ordinal + " for " + ec);
                }
                yield constants[ordinal];
            }
            case FIXED32 -> in.readFixed32();
            case SFIXED32 -> in.readSFixed32();
            case FLOAT -> in.readFloat();
            case FIXED64 -> in.readFixed64();
            case SFIXED64 -> in.readSFixed64();
            case DOUBLE -> in.readDouble();
            case STRING -> in.readString();
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
        };
    }

    private static Object defaultFor(FieldBinding fb) {
        if (fb.repeated) return List.of();
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
        };
    }

    // ================================================================== Data classes

    record BindingPlan(Class<?> recordType,
                       List<FieldBinding> fields,
                       Map<Integer, FieldBinding> byNumber,
                       MethodHandle constructor,
                       Class<?>[] componentTypes) {}

    static final class FieldBinding {
        final int number;
        final FieldType type;
        final boolean repeated;
        final boolean packed;
        final Class<?> elementType;
        final int componentIndex;
        final MethodHandle getter;
        final byte[] tagBytes;
        final byte[] packedTagBytes;

        FieldBinding(int number, FieldType type, boolean repeated, boolean packed,
                     Class<?> elementType, int componentIndex, MethodHandle getter,
                     byte[] tagBytes, byte[] packedTagBytes) {
            this.number = number;
            this.type = type;
            this.repeated = repeated;
            this.packed = packed;
            this.elementType = elementType;
            this.componentIndex = componentIndex;
            this.getter = getter;
            this.tagBytes = tagBytes;
            this.packedTagBytes = packedTagBytes;
        }
    }
}
