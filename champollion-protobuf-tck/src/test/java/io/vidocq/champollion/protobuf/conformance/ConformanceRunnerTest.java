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

    /** Encodes {@code len} in 4 bytes little-endian (the format expected by
     * the Google runner and thus consumed by {@link ConformanceRunner}). */
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
            // skipped is present
            assertEquals("not yet", back.skipped());
            // Fields absent on wire become Java defaults after parse
            assertEquals("", back.parse_error());
            assertEquals(0, back.protobuf_payload().length);
        }
    }

    @Nested
    @DisplayName("ConformanceRunner — pipe protocol stdin/stdout")
    class Pipe {

        @Test
        void single_skipped_request_yields_skipped_response() throws IOException {
            // Construct a pipe with a single mocked request
            ConformanceRequest req = new ConformanceRequest(
                    new byte[0], "", WireFormat.JSON, "external.TestAllTypesProto3",
                    TestCategory.JSON_TEST, "", "", false);
            byte[] reqBytes = Protobuf.toByteArray(req);

            ByteArrayOutputStream input = new ByteArrayOutputStream();
            writeLenLE(input, reqBytes.length);
            input.write(reqBytes);
            // No following byte → natural EOF

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
            new ConformanceRunner().run(
                    new ByteArrayInputStream(input.toByteArray()),
                    output,
                    new PrintStream(errBuf));

            // Verify the response
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
            // AllTypes a ~97 champs — on construit le payload en raw wire pour rester concis.
            // tag 1 (int32 optional_int32 = 42) : 0x08 0x2A
            // tag 14 (string optional_string = "hello") : 0x72 0x05 'h' 'e' 'l' 'l' 'o'
            byte[] payload = new byte[] { 0x08, 0x2A, 0x72, 0x05, 'h', 'e', 'l', 'l', 'o' };

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
            assertEquals("", resp.parse_error(), "parse_error must be empty after wire round-trip");
            assertEquals("", resp.serialize_error(), "serialize_error must be empty");
            assertEquals("", resp.skipped(), "must not be skipped");
            TestAllTypesProto3 back = Protobuf.parser(TestAllTypesProto3.class).parseFrom(resp.protobuf_payload());
            assertEquals(42, back.optional_int32());
            assertEquals("hello", back.optional_string());
        }

        @Test
        void known_type_json_output() throws IOException {
            // optional_int32 = 7
            byte[] payload = new byte[] { 0x08, 0x07 };

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
