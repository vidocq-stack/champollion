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
package io.vidocq.champollion.protobuf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Binary encoding of every field type through the runtime binding: singular, repeated (packed and expanded) and
 * map fields, presence, oneofs, and the bindings it refuses.
 */
class BinaryMappingTest {

    private static final String KEY = "k";

    public enum Status { NONE, ACTIVE }

    @ProtobufMessage
    public record Point(
            @ProtobufField(number = 1, type = FieldType.INT32) int x,
            @ProtobufField(number = 2, type = FieldType.STRING) String label
    ) implements Message {}

    @ProtobufMessage
    public record Singles(
            @ProtobufField(number = 1, type = FieldType.INT32) int int32,
            @ProtobufField(number = 2, type = FieldType.INT64) long int64,
            @ProtobufField(number = 3, type = FieldType.UINT32) int uint32,
            @ProtobufField(number = 4, type = FieldType.UINT64) long uint64,
            @ProtobufField(number = 5, type = FieldType.SINT32) int sint32,
            @ProtobufField(number = 6, type = FieldType.SINT64) long sint64,
            @ProtobufField(number = 7, type = FieldType.BOOL) boolean bool,
            @ProtobufField(number = 8, type = FieldType.ENUM) Status status,
            @ProtobufField(number = 9, type = FieldType.FIXED32) int fixed32,
            @ProtobufField(number = 10, type = FieldType.SFIXED32) int sfixed32,
            @ProtobufField(number = 11, type = FieldType.FLOAT) float flt,
            @ProtobufField(number = 12, type = FieldType.FIXED64) long fixed64,
            @ProtobufField(number = 13, type = FieldType.SFIXED64) long sfixed64,
            @ProtobufField(number = 14, type = FieldType.DOUBLE) double dbl,
            @ProtobufField(number = 15, type = FieldType.STRING) String str,
            @ProtobufField(number = 16, type = FieldType.MESSAGE) Point point
    ) implements Message {}

    @ProtobufMessage
    public record Packed(
            @ProtobufField(number = 1, type = FieldType.INT32) List<Integer> int32,
            @ProtobufField(number = 2, type = FieldType.INT64) List<Long> int64,
            @ProtobufField(number = 3, type = FieldType.UINT32) List<Integer> uint32,
            @ProtobufField(number = 4, type = FieldType.UINT64) List<Long> uint64,
            @ProtobufField(number = 5, type = FieldType.SINT32) List<Integer> sint32,
            @ProtobufField(number = 6, type = FieldType.SINT64) List<Long> sint64,
            @ProtobufField(number = 7, type = FieldType.BOOL) List<Boolean> bool,
            @ProtobufField(number = 8, type = FieldType.ENUM) List<Status> status,
            @ProtobufField(number = 9, type = FieldType.FIXED32) List<Integer> fixed32,
            @ProtobufField(number = 10, type = FieldType.SFIXED32) List<Integer> sfixed32,
            @ProtobufField(number = 11, type = FieldType.FLOAT) List<Float> flt,
            @ProtobufField(number = 12, type = FieldType.FIXED64) List<Long> fixed64,
            @ProtobufField(number = 13, type = FieldType.SFIXED64) List<Long> sfixed64,
            @ProtobufField(number = 14, type = FieldType.DOUBLE) List<Double> dbl
    ) implements Message {}

    @ProtobufMessage
    public record Expanded(
            @ProtobufField(number = 1, type = FieldType.INT32, packed = false) List<Integer> int32,
            @ProtobufField(number = 2, type = FieldType.INT64, packed = false) List<Long> int64,
            @ProtobufField(number = 3, type = FieldType.UINT32, packed = false) List<Integer> uint32,
            @ProtobufField(number = 4, type = FieldType.UINT64, packed = false) List<Long> uint64,
            @ProtobufField(number = 5, type = FieldType.SINT32, packed = false) List<Integer> sint32,
            @ProtobufField(number = 6, type = FieldType.SINT64, packed = false) List<Long> sint64,
            @ProtobufField(number = 7, type = FieldType.BOOL, packed = false) List<Boolean> bool,
            @ProtobufField(number = 8, type = FieldType.ENUM, packed = false) List<Status> status,
            @ProtobufField(number = 9, type = FieldType.FIXED32, packed = false) List<Integer> fixed32,
            @ProtobufField(number = 10, type = FieldType.SFIXED32, packed = false) List<Integer> sfixed32,
            @ProtobufField(number = 11, type = FieldType.FLOAT, packed = false) List<Float> flt,
            @ProtobufField(number = 12, type = FieldType.FIXED64, packed = false) List<Long> fixed64,
            @ProtobufField(number = 13, type = FieldType.SFIXED64, packed = false) List<Long> sfixed64,
            @ProtobufField(number = 14, type = FieldType.DOUBLE, packed = false) List<Double> dbl
    ) implements Message {}

    @ProtobufMessage
    public record Maps(
            @ProtobufField(number = 1, type = FieldType.MAP, mapKey = FieldType.INT32, mapValue = FieldType.INT64)
            Map<Integer, Long> int32ToInt64,
            @ProtobufField(number = 2, type = FieldType.MAP, mapKey = FieldType.INT64, mapValue = FieldType.UINT32)
            Map<Long, Integer> int64ToUint32,
            @ProtobufField(number = 3, type = FieldType.MAP, mapKey = FieldType.UINT32, mapValue = FieldType.UINT64)
            Map<Integer, Long> uint32ToUint64,
            @ProtobufField(number = 4, type = FieldType.MAP, mapKey = FieldType.UINT64, mapValue = FieldType.SINT32)
            Map<Long, Integer> uint64ToSint32,
            @ProtobufField(number = 5, type = FieldType.MAP, mapKey = FieldType.SINT32, mapValue = FieldType.SINT64)
            Map<Integer, Long> sint32ToSint64,
            @ProtobufField(number = 6, type = FieldType.MAP, mapKey = FieldType.SINT64, mapValue = FieldType.BOOL)
            Map<Long, Boolean> sint64ToBool,
            @ProtobufField(number = 7, type = FieldType.MAP, mapKey = FieldType.BOOL, mapValue = FieldType.FIXED32)
            Map<Boolean, Integer> boolToFixed32,
            @ProtobufField(number = 8, type = FieldType.MAP, mapKey = FieldType.FIXED32, mapValue = FieldType.SFIXED32)
            Map<Integer, Integer> fixed32ToSfixed32,
            @ProtobufField(number = 9, type = FieldType.MAP, mapKey = FieldType.SFIXED32, mapValue = FieldType.FLOAT)
            Map<Integer, Float> sfixed32ToFloat,
            @ProtobufField(number = 10, type = FieldType.MAP, mapKey = FieldType.FIXED64, mapValue = FieldType.SFIXED64)
            Map<Long, Long> fixed64ToSfixed64,
            @ProtobufField(number = 11, type = FieldType.MAP, mapKey = FieldType.SFIXED64, mapValue = FieldType.FIXED64)
            Map<Long, Long> sfixed64ToFixed64,
            @ProtobufField(number = 12, type = FieldType.MAP, mapKey = FieldType.STRING, mapValue = FieldType.DOUBLE)
            Map<String, Double> stringToDouble,
            @ProtobufField(number = 13, type = FieldType.MAP, mapKey = FieldType.STRING, mapValue = FieldType.BYTES)
            Map<String, byte[]> stringToBytes,
            @ProtobufField(number = 14, type = FieldType.MAP, mapKey = FieldType.STRING, mapValue = FieldType.ENUM)
            Map<String, Status> stringToEnum,
            @ProtobufField(number = 15, type = FieldType.MAP, mapKey = FieldType.STRING, mapValue = FieldType.MESSAGE)
            Map<String, Point> stringToPoint,
            @ProtobufField(number = 16, type = FieldType.MAP, mapKey = FieldType.INT32, mapValue = FieldType.STRING)
            Map<Integer, String> int32ToString
    ) implements Message {}

    @ProtobufMessage
    public record Choice(
            @ProtobufField(number = 1, type = FieldType.STRING, explicitPresence = true, oneofGroup = "kind")
            String name,
            @ProtobufField(number = 2, type = FieldType.INT32, explicitPresence = true, oneofGroup = "kind")
            Integer id,
            @ProtobufField(number = 3, type = FieldType.MESSAGE, explicitPresence = true, oneofGroup = "kind")
            Point point,
            @ProtobufField(number = 4, type = FieldType.INT32, explicitPresence = true)
            Integer count
    ) implements Message {}

    @ProtobufMessage
    public record Repeated(
            @ProtobufField(number = 1, type = FieldType.STRING) List<String> strings,
            @ProtobufField(number = 2, type = FieldType.BYTES) List<byte[]> bytes,
            @ProtobufField(number = 3, type = FieldType.MESSAGE) List<Point> points
    ) implements Message {}

    private static Singles singles() {
        return new Singles(-1, Long.MIN_VALUE, -1, -1L, -2, -3L, true, Status.ACTIVE, -4, -5, 1.5f, -6L, -7L, -0.25,
                "é", new Point(3, "p"));
    }

    private static Packed packed() {
        return new Packed(List.of(-1, 0, 1), List.of(-2L, 2L), List.of(-1, 3), List.of(-1L, 4L), List.of(-5, 5),
                List.of(-6L, 6L), List.of(true, false), List.of(Status.ACTIVE, Status.NONE), List.of(-7, 7),
                List.of(-8, 8), List.of(1.5f, -1.5f), List.of(-9L, 9L), List.of(-10L, 10L), List.of(0.5, -0.5));
    }

    private static Expanded expanded() {
        Packed p = packed();
        return new Expanded(p.int32(), p.int64(), p.uint32(), p.uint64(), p.sint32(), p.sint64(), p.bool(), p.status(),
                p.fixed32(), p.sfixed32(), p.flt(), p.fixed64(), p.sfixed64(), p.dbl());
    }

    private static <T> T roundTrip(T message, Class<T> type) throws IOException {
        byte[] bytes = Protobuf.toByteArray(message);
        assertEquals(bytes.length, Protobuf.getSerializedSize(message));
        return Protobuf.parser(type).parseFrom(bytes);
    }

    @Test
    void every_singular_type_round_trips() throws IOException {
        assertEquals(singles(), roundTrip(singles(), Singles.class));
    }

    @Test
    void default_values_are_not_written() {
        Singles defaults = new Singles(0, 0, 0, 0, 0, 0, false, Status.NONE, 0, 0, 0f, 0, 0, 0.0, "", null);
        assertEquals(0, Protobuf.toByteArray(defaults).length);
    }

    @Test
    void every_packable_type_round_trips_packed() throws IOException {
        assertEquals(packed(), roundTrip(packed(), Packed.class));
    }

    @Test
    void every_packable_type_round_trips_expanded() throws IOException {
        assertEquals(expanded(), roundTrip(expanded(), Expanded.class));
    }

    @Test
    void a_reader_accepts_the_packed_and_the_expanded_form_alike() throws IOException {
        Expanded fromPacked = Protobuf.parser(Expanded.class).parseFrom(Protobuf.toByteArray(packed()));
        assertEquals(expanded(), fromPacked);
        Packed fromExpanded = Protobuf.parser(Packed.class).parseFrom(Protobuf.toByteArray(expanded()));
        assertEquals(packed(), fromExpanded);
    }

    @Test
    void packed_and_expanded_encodings_differ_in_size() {
        assertTrue(Protobuf.toByteArray(packed()).length < Protobuf.toByteArray(expanded()).length);
    }

    @Test
    void repeated_length_delimited_values_round_trip() throws IOException {
        Repeated r = new Repeated(List.of("a", "b"), List.of(new byte[] {1}, new byte[0]),
                List.of(new Point(1, "x"), new Point(0, "")));
        Repeated back = roundTrip(r, Repeated.class);
        assertEquals(r.strings(), back.strings());
        assertEquals(2, back.bytes().size());
        assertArrayEquals(new byte[] {1}, back.bytes().get(0));
        assertEquals(r.points(), back.points());
    }

    private static Maps maps() {
        return new Maps(Map.of(-1, -2L), Map.of(-3L, -4), Map.of(-1, -1L), Map.of(-1L, -5), Map.of(-6, -7L),
                Map.of(-8L, true), Map.of(true, -9), Map.of(-10, -11), Map.of(-12, 1.5f), Map.of(-13L, -14L),
                Map.of(-15L, -16L), Map.of(KEY, 2.5), Map.of(KEY, new byte[] {7}), Map.of(KEY, Status.ACTIVE),
                Map.of(KEY, new Point(4, "m")), Map.of(17, "v"));
    }

    @Test
    void every_map_key_and_value_type_round_trips() throws IOException {
        Maps m = maps();
        Maps back = roundTrip(m, Maps.class);
        assertEquals(m.int32ToInt64(), back.int32ToInt64());
        assertEquals(m.int64ToUint32(), back.int64ToUint32());
        assertEquals(m.uint32ToUint64(), back.uint32ToUint64());
        assertEquals(m.uint64ToSint32(), back.uint64ToSint32());
        assertEquals(m.sint32ToSint64(), back.sint32ToSint64());
        assertEquals(m.sint64ToBool(), back.sint64ToBool());
        assertEquals(m.boolToFixed32(), back.boolToFixed32());
        assertEquals(m.fixed32ToSfixed32(), back.fixed32ToSfixed32());
        assertEquals(m.sfixed32ToFloat(), back.sfixed32ToFloat());
        assertEquals(m.fixed64ToSfixed64(), back.fixed64ToSfixed64());
        assertEquals(m.sfixed64ToFixed64(), back.sfixed64ToFixed64());
        assertEquals(m.stringToDouble(), back.stringToDouble());
        assertArrayEquals(new byte[] {7}, back.stringToBytes().get(KEY));
        assertEquals(m.stringToEnum(), back.stringToEnum());
        assertEquals(m.stringToPoint(), back.stringToPoint());
        assertEquals(m.int32ToString(), back.int32ToString());
    }

    private static Maps onlyStrings(Map<Integer, String> int32ToString, Map<String, Point> points,
                                    Map<String, Status> statuses) {
        return new Maps(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), statuses, points, int32ToString);
    }

    @Test
    void a_null_key_or_scalar_value_is_written_as_the_default() throws IOException {
        Map<Integer, String> strings = new HashMap<>();
        strings.put(null, null);
        Map<String, Status> statuses = new HashMap<>();
        statuses.put(KEY, null);
        Maps back = roundTrip(onlyStrings(strings, Map.of(), statuses), Maps.class);
        assertEquals(Map.of(0, ""), back.int32ToString());
        assertEquals(Map.of(KEY, Status.NONE), back.stringToEnum());
    }

    @Test
    void a_null_message_value_is_written_as_a_key_only_entry() throws IOException {
        Map<String, Point> points = new HashMap<>();
        points.put(KEY, null);
        Maps back = roundTrip(onlyStrings(Map.of(), points, Map.of()), Maps.class);
        assertTrue(back.stringToPoint().containsKey(KEY));
        assertNull(back.stringToPoint().get(KEY));
    }

    @Test
    void unknown_fields_of_a_map_entry_are_skipped() throws IOException {
        ByteArrayOutputStream entry = new ByteArrayOutputStream();
        CodedOutputStream e = CodedOutputStream.newInstance(entry);
        e.writeInt32(1, 5);
        e.writeString(3, "ignored");
        e.writeString(2, "five");
        e.flush();
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        CodedOutputStream m = CodedOutputStream.newInstance(message);
        m.writeBytes(16, entry.toByteArray());
        m.flush();
        Maps back = Protobuf.parser(Maps.class).parseFrom(message.toByteArray());
        assertEquals(Map.of(5, "five"), back.int32ToString());
    }

    @Test
    void the_last_member_of_a_oneof_on_the_wire_wins() throws IOException {
        byte[] name = Protobuf.toByteArray(new Choice("n", null, null, null));
        byte[] point = Protobuf.toByteArray(new Choice(null, null, new Point(1, "q"), null));
        byte[] id = Protobuf.toByteArray(new Choice(null, 0, null, 2));
        Choice back = Protobuf.parser(Choice.class).parseFrom(concat(name, point, id));
        assertEquals(new Choice(null, 0, null, 2), back);
        Choice pointLast = Protobuf.parser(Choice.class).parseFrom(concat(id, name, point));
        assertEquals(new Choice(null, null, new Point(1, "q"), 2), pointLast);
    }

    @Test
    void absent_explicit_presence_fields_stay_null() throws IOException {
        assertEquals(new Choice(null, null, null, null), Protobuf.parser(Choice.class).parseFrom(new byte[0]));
    }

    @Test
    void a_field_with_the_wrong_wire_type_is_rejected() {
        byte[] int32AsBytes = {0x0A, 0x00};
        byte[] pointAsVarint = {(byte) 0x80, 0x01, 0x01};
        byte[] mapAsVarint = {(byte) 0x80, 0x01, 0x01};
        byte[] stringsAsVarint = {0x08, 0x01};
        assertThrows(MalformedProtobufException.class, () -> Protobuf.parser(Singles.class).parseFrom(int32AsBytes));
        assertThrows(MalformedProtobufException.class, () -> Protobuf.parser(Singles.class).parseFrom(pointAsVarint));
        assertThrows(MalformedProtobufException.class, () -> Protobuf.parser(Maps.class).parseFrom(mapAsVarint));
        assertThrows(MalformedProtobufException.class,
                () -> Protobuf.parser(Repeated.class).parseFrom(stringsAsVarint));
    }

    @Test
    void the_parser_reads_slices_and_streams() throws IOException {
        byte[] bytes = Protobuf.toByteArray(singles());
        byte[] padded = new byte[bytes.length + 3];
        System.arraycopy(bytes, 0, padded, 1, bytes.length);
        assertEquals(singles(), Protobuf.parser(Singles.class).parseFrom(padded, 1, bytes.length));
        assertEquals(singles(), Protobuf.runtimeParser(Singles.class).parseFrom(new ByteArrayInputStream(bytes)));
    }

    @Test
    void message_default_methods_delegate_to_protobuf() throws IOException {
        Singles s = singles();
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        s.writeTo(stream);
        ByteArrayOutputStream coded = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(coded);
        s.writeTo(out);
        out.flush();
        byte[] expected = Protobuf.toByteArray(s);
        assertArrayEquals(expected, s.toByteArray());
        assertArrayEquals(expected, stream.toByteArray());
        assertArrayEquals(expected, coded.toByteArray());
        assertEquals(expected.length, s.getSerializedSize());
        assertEquals(s, Message.parseFrom(Singles.class, expected));
    }

    @Test
    void message_parse_from_wraps_a_malformed_input() {
        byte[] truncated = {0x0A, 0x05};
        assertThrows(UncheckedIOException.class, () -> Message.parseFrom(Singles.class, truncated));
    }

    // ------------------------------------------------------------------ bindings refused

    public record NotAnnotated(@ProtobufField(number = 1, type = FieldType.INT32) int x) {}

    @ProtobufMessage
    public record MissingField(@ProtobufField(number = 1, type = FieldType.INT32) int x, int y) {}

    @ProtobufMessage
    public record MapOnList(@ProtobufField(number = 1, type = FieldType.MAP) List<String> values) {}

    @ProtobufMessage
    public record WildcardList(@ProtobufField(number = 1, type = FieldType.STRING) List<? extends CharSequence> values) {}

    @ProtobufMessage
    public record EnumOnInt(@ProtobufField(number = 1, type = FieldType.ENUM) int value) {}

    @Test
    void only_annotated_records_can_be_bound() {
        assertThrows(IllegalArgumentException.class, () -> Protobuf.runtimeParser(String.class));
        assertThrows(IllegalArgumentException.class, () -> Protobuf.runtimeParser(NotAnnotated.class));
        assertThrows(IllegalArgumentException.class, () -> Protobuf.runtimeParser(MissingField.class));
    }

    @Test
    void field_java_types_must_match_their_declaration() {
        assertThrows(IllegalArgumentException.class, () -> Protobuf.runtimeParser(MapOnList.class));
        assertThrows(IllegalArgumentException.class, () -> Protobuf.runtimeParser(WildcardList.class));
    }

    @Test
    void an_enum_field_must_be_bound_to_an_enum() {
        Parser<EnumOnInt> parser = Protobuf.runtimeParser(EnumOnInt.class);
        byte[] one = {0x08, 0x01};
        assertThrows(IOException.class, () -> parser.parseFrom(one));
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }
}
