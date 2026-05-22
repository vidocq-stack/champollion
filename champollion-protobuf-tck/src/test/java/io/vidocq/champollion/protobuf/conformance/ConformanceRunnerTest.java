package io.vidocq.champollion.protobuf.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.champollion.protobuf.Protobuf;
import io.vidocq.champollion.protobuf.conformance.ConformanceMessages.ConformanceRequest;
import io.vidocq.champollion.protobuf.conformance.ConformanceMessages.ConformanceResponse;
import io.vidocq.champollion.protobuf.conformance.ConformanceMessages.TestCategory;
import io.vidocq.champollion.protobuf.conformance.ConformanceMessages.WireFormat;
import io.vidocq.champollion.protobuf.tck.proto3.TestAllTypesProto3;

import java.util.List;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.io.IOException;
import java.io.PrintStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link ConformanceRunner} — squelette M1.6 du protocole conformance
 * test runner (Google). Vérifie le pipe stdin/stdout, le decoding du
 * {@code ConformanceRequest} et la production de {@code ConformanceResponse}
 * cohérents pour les cas non encore supportés (skipped).
 */
class ConformanceRunnerTest {

    /** Encode {@code len} en 4 octets little-endian (le format attendu par
     * le runner Google et donc consommé par {@link ConformanceRunner}). */
    private static void writeLenLE(ByteArrayOutputStream out, int len) {
        out.write(len & 0xFF);
        out.write((len >>> 8) & 0xFF);
        out.write((len >>> 16) & 0xFF);
        out.write((len >>> 24) & 0xFF);
    }

    private static int readLenLE(DataInputStream in) throws IOException {
        return ByteBuffer.wrap(in.readNBytes(4)).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    @Nested
    @DisplayName("ConformanceMessages — round-trip via runtime binding")
    class Messages {

        @Test
        void request_roundtrip() throws IOException {
            ConformanceRequest req = new ConformanceRequest(
                    new byte[]{1, 2, 3},
                    "",
                    WireFormat.PROTOBUF,
                    "io.vidocq.champollion.protobuf.test.Foo",
                    TestCategory.BINARY_TEST,
                    "", "", false);
            byte[] bytes = Protobuf.toByteArray(req);
            ConformanceRequest back = Protobuf.parser(ConformanceRequest.class).parseFrom(bytes);
            assertEquals(req.message_type(), back.message_type());
            assertEquals(req.test_category(), back.test_category());
            assertEquals(req.requested_output_format(), back.requested_output_format());
        }

        @Test
        void response_skipped_factory_only_sets_skipped_field() {
            ConformanceResponse r = ConformanceResponse.skipped("not yet");
            assertEquals("not yet", r.skipped());
            // Les autres champs sont null en mémoire (oneof simulé) mais
            // après round-trip wire ils seront aux defaults Java ("" / new byte[0])
            // — la distinction null/default n'est pas portée par le wire format.
            assertNull(r.parse_error());
            assertNull(r.protobuf_payload());
        }

        @Test
        void response_roundtrip_lost_null_distinction() throws IOException {
            ConformanceResponse src = ConformanceResponse.skipped("not yet");
            byte[] bytes = Protobuf.toByteArray(src);
            ConformanceResponse back = Protobuf.parser(ConformanceResponse.class).parseFrom(bytes);
            // skipped est présent
            assertEquals("not yet", back.skipped());
            // Les champs absents sur le wire deviennent les defaults Java après parse
            assertEquals("", back.parse_error());
            assertEquals(0, back.protobuf_payload().length);
        }
    }

    @Nested
    @DisplayName("ConformanceRunner — pipe protocol stdin/stdout")
    class Pipe {

        @Test
        void single_skipped_request_yields_skipped_response() throws IOException {
            // Construit un pipe avec une seule requête mockée
            ConformanceRequest req = new ConformanceRequest(
                    new byte[0], "", WireFormat.JSON, "external.TestAllTypesProto3",
                    TestCategory.JSON_TEST, "", "", false);
            byte[] reqBytes = Protobuf.toByteArray(req);

            ByteArrayOutputStream input = new ByteArrayOutputStream();
            writeLenLE(input, reqBytes.length);
            input.write(reqBytes);
            // Pas d'octet suivant → EOF naturel

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
            new ConformanceRunner().run(
                    new ByteArrayInputStream(input.toByteArray()),
                    output,
                    new PrintStream(errBuf));

            // Vérifie la réponse
            DataInputStream din = new DataInputStream(new ByteArrayInputStream(output.toByteArray()));
            int respLen = readLenLE(din);
            byte[] respBytes = din.readNBytes(respLen);
            ConformanceResponse resp = Protobuf.parser(ConformanceResponse.class).parseFrom(respBytes);
            assertTrue(resp.skipped().contains("Unknown message_type"),
                    "skipped doit mentionner le message inconnu, got: " + resp.skipped());
            // round-trip wire → defaults Java pour les champs absents
            assertEquals("", resp.parse_error());
        }

        @Test
        void multiple_requests_all_processed() throws IOException {
            ByteArrayOutputStream input = new ByteArrayOutputStream();
            for (int i = 0; i < 3; i++) {
                ConformanceRequest req = new ConformanceRequest(
                        new byte[]{(byte) i}, "", WireFormat.PROTOBUF, "ex.T",
                        TestCategory.BINARY_TEST, "", "", false);
                byte[] reqBytes = Protobuf.toByteArray(req);
                writeLenLE(input, reqBytes.length);
                input.write(reqBytes);
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
            new ConformanceRunner().run(
                    new ByteArrayInputStream(input.toByteArray()),
                    output,
                    new PrintStream(errBuf));

            DataInputStream din = new DataInputStream(new ByteArrayInputStream(output.toByteArray()));
            int count = 0;
            while (din.available() > 0) {
                int respLen = readLenLE(din);
                din.readNBytes(respLen);
                count++;
            }
            assertEquals(3, count);
            String log = errBuf.toString();
            assertTrue(log.contains("total=3"), "log doit contenir le compteur: " + log);
        }

        @Test
        void known_type_protobuf_roundtrip() throws IOException {
            // Construit un TestAllTypesProto3 minimal, l'encode, demande au runner
            // de le re-sérialiser en protobuf — round-trip identité.
            TestAllTypesProto3 src = new TestAllTypesProto3(
                    42, 0L, 0, 0L, 0, 0L, 0, 0L, 0, 0L, 0.0f, 0.0d, false,
                    "hello", new byte[0],
                    List.of(), List.of(), List.of(), List.of(),
                    null, null, null, null, null, null, null, null, null,  // wrappers
                    null, null, null, null);                                // duration/timestamp/fieldmask/any
            byte[] payload = Protobuf.toByteArray(src);

            ConformanceRequest req = new ConformanceRequest(
                    payload, "", WireFormat.PROTOBUF,
                    "protobuf_test_messages.proto3.TestAllTypesProto3",
                    TestCategory.BINARY_TEST, "", "", false);
            byte[] reqBytes = Protobuf.toByteArray(req);

            ByteArrayOutputStream input = new ByteArrayOutputStream();
            writeLenLE(input, reqBytes.length);
            input.write(reqBytes);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            new ConformanceRunner().run(
                    new ByteArrayInputStream(input.toByteArray()),
                    output, new PrintStream(new ByteArrayOutputStream()));

            DataInputStream din = new DataInputStream(new ByteArrayInputStream(output.toByteArray()));
            byte[] respBytes = din.readNBytes(readLenLE(din));
            ConformanceResponse resp = Protobuf.parser(ConformanceResponse.class).parseFrom(respBytes);
            // round-trip wire perd la distinction null/default proto3
            assertEquals("", resp.parse_error(), "parse_error must be empty after wire round-trip");
            assertEquals("", resp.serialize_error(), "serialize_error must be empty");
            assertEquals("", resp.skipped(), "must not be skipped");
            // Re-parse le payload réponse en TestAllTypesProto3 et vérifie identité.
            TestAllTypesProto3 back = Protobuf.parser(TestAllTypesProto3.class)
                    .parseFrom(resp.protobuf_payload());
            assertEquals(42, back.optional_int32());
            assertEquals("hello", back.optional_string());
        }

        @Test
        void known_type_json_output() throws IOException {
            TestAllTypesProto3 src = new TestAllTypesProto3(
                    7, 0L, 0, 0L, 0, 0L, 0, 0L, 0, 0L, 0.0f, 0.0d, false,
                    "", new byte[0],
                    List.of(), List.of(), List.of(), List.of(),
                    null, null, null, null, null, null, null, null, null,
                    null, null, null, null);
            byte[] payload = Protobuf.toByteArray(src);

            ConformanceRequest req = new ConformanceRequest(
                    payload, "", WireFormat.JSON,
                    "protobuf_test_messages.proto3.TestAllTypesProto3",
                    TestCategory.BINARY_TEST, "", "", false);
            byte[] reqBytes = Protobuf.toByteArray(req);

            ByteArrayOutputStream input = new ByteArrayOutputStream();
            writeLenLE(input, reqBytes.length);
            input.write(reqBytes);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            new ConformanceRunner().run(
                    new ByteArrayInputStream(input.toByteArray()),
                    output, new PrintStream(new ByteArrayOutputStream()));

            DataInputStream din = new DataInputStream(new ByteArrayInputStream(output.toByteArray()));
            byte[] respBytes = din.readNBytes(readLenLE(din));
            ConformanceResponse resp = Protobuf.parser(ConformanceResponse.class).parseFrom(respBytes);
            assertEquals("", resp.parse_error());
            assertTrue(resp.json_payload().contains("\"optionalInt32\":7"),
                    "JSON canonical doit utiliser camelCase et inclure optionalInt32:7 — got: "
                            + resp.json_payload());
        }

        @Test
        void empty_stdin_produces_no_response() throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            new ConformanceRunner().run(
                    new ByteArrayInputStream(new byte[0]),
                    output,
                    new PrintStream(err));
            assertEquals(0, output.size());
            assertTrue(err.toString().contains("total=0"));
        }
    }
}
