package io.vidocq.champollion.protobuf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link WireFormat}.
 *
 * <p>Spec : <a href="https://protobuf.dev/programming-guides/encoding/#structure">Protocol Buffers Encoding §Structure</a>.</p>
 */
class WireFormatTest {

    @Nested
    @DisplayName("Encoding §Structure — wire type constants")
    class WireTypeConstants {

        @Test
        void varint_is_zero() {
            assertEquals(0, WireFormat.WIRETYPE_VARINT);
        }

        @Test
        void i64_is_one() {
            assertEquals(1, WireFormat.WIRETYPE_FIXED64);
        }

        @Test
        void len_is_two() {
            assertEquals(2, WireFormat.WIRETYPE_LENGTH_DELIMITED);
        }

        @Test
        void sgroup_is_three() {
            assertEquals(3, WireFormat.WIRETYPE_START_GROUP);
        }

        @Test
        void egroup_is_four() {
            assertEquals(4, WireFormat.WIRETYPE_END_GROUP);
        }

        @Test
        void i32_is_five() {
            assertEquals(5, WireFormat.WIRETYPE_FIXED32);
        }
    }

    @Nested
    @DisplayName("Encoding §Structure — tag = (field_number << 3) | wire_type")
    class Tag {

        @Test
        void field_1_varint() {
            // (1 << 3) | 0 = 8
            assertEquals(0x08, WireFormat.makeTag(1, WireFormat.WIRETYPE_VARINT));
        }

        @Test
        void field_2_length_delimited() {
            // (2 << 3) | 2 = 18 = 0x12
            assertEquals(0x12, WireFormat.makeTag(2, WireFormat.WIRETYPE_LENGTH_DELIMITED));
        }

        @Test
        void field_15_varint_fits_in_one_byte() {
            // (15 << 3) | 0 = 120 = 0x78. 120 < 128 donc 1 octet en varint.
            int tag = WireFormat.makeTag(15, WireFormat.WIRETYPE_VARINT);
            assertEquals(0x78, tag);
            assertEquals(1, CodedOutputStream.computeRawVarint32Size(tag));
        }

        @Test
        void field_16_varint_requires_two_bytes() {
            // (16 << 3) | 0 = 128. 128 ≥ 128 donc 2 octets en varint.
            int tag = WireFormat.makeTag(16, WireFormat.WIRETYPE_VARINT);
            assertEquals(128, tag);
            assertEquals(2, CodedOutputStream.computeRawVarint32Size(tag));
        }

        @Test
        void decompose_tag() {
            int tag = WireFormat.makeTag(42, WireFormat.WIRETYPE_FIXED64);
            assertEquals(42, WireFormat.getTagFieldNumber(tag));
            assertEquals(WireFormat.WIRETYPE_FIXED64, WireFormat.getTagWireType(tag));
        }

        @Test
        void max_field_number_is_2pow29_minus_1() {
            assertEquals((1 << 29) - 1, WireFormat.MAX_FIELD_NUMBER);
        }
    }
}
