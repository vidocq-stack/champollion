package io.vidocq.champollion.protobuf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests M4.3.4 — {@code features.field_presence = EXPLICIT} (proto2 / Editions 2023
 * avec override). Avec EXPLICIT, le serializer écrit le champ même si sa valeur
 * est égale au default proto3 (chaîne vide, 0, false), tandis qu'IMPLICIT (défaut
 * proto3) l'omet.
 *
 * <p>Spec : <a href="https://protobuf.dev/editions/features/#field_presence">
 * features.field_presence</a>.</p>
 */
class ExplicitPresenceTest {

    @ProtobufMessage
    public record ImplicitMessage(
            @ProtobufField(number = 1, type = FieldType.STRING) String name) {}

    @ProtobufMessage
    public record ExplicitMessage(
            @ProtobufField(number = 1, type = FieldType.STRING, explicitPresence = true)
            String name) {}

    @Nested
    @DisplayName("Default proto3 IMPLICIT — défaut omis sur le wire")
    class Implicit {

        @Test
        void empty_string_default_is_omitted() throws Exception {
            byte[] wire = Protobuf.toByteArray(new ImplicitMessage(""));
            assertEquals(0, wire.length, "champ proto3 = default ⇒ omis");
        }

        @Test
        void non_default_value_is_written() throws Exception {
            byte[] wire = Protobuf.toByteArray(new ImplicitMessage("hi"));
            assertTrue(wire.length > 0);
        }
    }

    @Nested
    @DisplayName("EXPLICIT — default toujours écrit sur le wire")
    class Explicit {

        @Test
        void empty_string_default_is_written_when_explicit() throws Exception {
            byte[] wire = Protobuf.toByteArray(new ExplicitMessage(""));
            // tag = (1 << 3) | 2 = 0x0A, len = 0
            assertArrayEquals(new byte[] { 0x0A, 0x00 }, wire);
        }

        @Test
        void non_default_value_is_written_when_explicit() throws Exception {
            byte[] wire = Protobuf.toByteArray(new ExplicitMessage("hi"));
            assertArrayEquals(new byte[] { 0x0A, 0x02, 'h', 'i' }, wire);
        }

        @Test
        void null_field_is_still_omitted_even_when_explicit() throws Exception {
            // null ≠ default — c'est l'absence Java, pas la présence d'une valeur défaut.
            byte[] wire = Protobuf.toByteArray(new ExplicitMessage(null));
            assertEquals(0, wire.length);
        }
    }

    @Nested
    @DisplayName("JSON canonical — explicitPresence émet le défaut en JSON")
    class JsonOutput {

        @Test
        void implicit_default_is_omitted_in_json() {
            String json = ProtobufJson.toJson(new ImplicitMessage(""));
            assertEquals("{}", json, "proto3 IMPLICIT : default omis du JSON canonical");
        }

        @Test
        void explicit_default_is_present_in_json() {
            String json = ProtobufJson.toJson(new ExplicitMessage(""));
            assertEquals("{\"name\":\"\"}", json,
                    "EXPLICIT : default émis comme champ JSON présent");
        }
    }
}
