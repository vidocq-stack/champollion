package io.vidocq.champollion.protobuf.conformance;

import io.vidocq.champollion.protobuf.Protobuf;
import io.vidocq.champollion.protobuf.ProtobufJson;
import io.vidocq.champollion.protobuf.ProtobufMessage;
import io.vidocq.champollion.protobuf.conformance.ConformanceMessages.ConformanceRequest;
import io.vidocq.champollion.protobuf.conformance.ConformanceMessages.ConformanceResponse;
import io.vidocq.champollion.protobuf.conformance.ConformanceMessages.WireFormat;
import io.vidocq.champollion.protobuf.tck.proto3.TestAllTypesProto3;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Main wrapper du {@code conformance_test_runner} Google.
 *
 * <p>Spec : <a href="https://github.com/protocolbuffers/protobuf/blob/main/conformance/README.md">conformance/README.md</a>.</p>
 *
 * <p>Protocole : un test runner externe pipe sur {@code stdin}
 * {@code [4-byte BIG-ENDIAN length][ConformanceRequest protobuf bytes]}
 * et attend en réponse sur {@code stdout}
 * {@code [4-byte BIG-ENDIAN length][ConformanceResponse protobuf bytes]}.
 * Fin de session = stdin EOF.</p>
 *
 * <p>M1.6 squelette : la majorité des tests ciblent le type proto3
 * {@code TestAllTypesProto3} qui n'est pas encore mappé en records (le codegen
 * {@code .proto → java} viendra en M2). En attendant, on renvoie {@code skipped}
 * pour tout {@code message_type} inconnu et on traite les cas spéciaux
 * "round-trip vide" comme PASS. L'infrastructure pipe/protocol est
 * opérationnelle et prête à recevoir M2.</p>
 */
public final class ConformanceRunner {

    /**
     * Registry des messages connus indexé par proto fullName (valeur de
     * {@link ProtobufMessage#value()}). Ajouter ici tout nouveau type généré.
     */
    private static final Map<String, Class<?>> KNOWN_TYPES = new HashMap<>();
    static {
        register(TestAllTypesProto3.class);
    }

    private static void register(Class<?> type) {
        ProtobufMessage ann = type.getAnnotation(ProtobufMessage.class);
        if (ann == null || ann.value().isEmpty()) {
            throw new IllegalStateException(
                    type + " has no @ProtobufMessage(value=protoFullName)");
        }
        KNOWN_TYPES.put(ann.value(), type);
    }

    ConformanceRunner() {}

    public static void main(String[] args) throws IOException {
        new ConformanceRunner().run(System.in, System.out, System.err);
    }

    public void run(InputStream stdin, OutputStream stdout, PrintStream log) throws IOException {
        DataInputStream in = new DataInputStream(stdin);
        DataOutputStream out = new DataOutputStream(stdout);
        long total = 0;
        long handled = 0;
        long skipped = 0;
        while (true) {
            int len;
            try {
                len = in.readInt();
            } catch (java.io.EOFException eof) {
                break;
            }
            byte[] payload = in.readNBytes(len);
            if (payload.length != len) {
                throw new IOException("Truncated conformance request (expected "
                        + len + " bytes, got " + payload.length + ")");
            }
            total++;
            ConformanceResponse response = handle(payload);
            if (response.skipped() != null && !response.skipped().isEmpty()) skipped++;
            else handled++;
            byte[] respBytes = Protobuf.toByteArray(response);
            out.writeInt(respBytes.length);
            out.write(respBytes);
            out.flush();
        }
        log.println("Champollion conformance: total=" + total
                + " handled=" + handled + " skipped=" + skipped);
    }

    ConformanceResponse handle(byte[] requestBytes) {
        ConformanceRequest req;
        try {
            req = Protobuf.parser(ConformanceRequest.class).parseFrom(requestBytes);
        } catch (IOException e) {
            return ConformanceResponse.parseError("Cannot decode ConformanceRequest: " + e.getMessage());
        }

        Class<?> type = KNOWN_TYPES.get(req.message_type());
        if (type == null) {
            return ConformanceResponse.skipped(
                    "Unknown message_type '" + req.message_type() + "' — not in M5 subset.");
        }

        Object message;
        try {
            if (req.hasProtobufPayload()) {
                message = Protobuf.parser(type).parseFrom(req.protobuf_payload());
            } else if (req.hasJsonPayload()) {
                message = ProtobufJson.fromJson(type, req.json_payload());
            } else if (req.hasJspbPayload()) {
                return ConformanceResponse.skipped("JSPB wire format not implemented.");
            } else if (req.hasTextPayload()) {
                return ConformanceResponse.skipped("TEXT_FORMAT wire format not implemented.");
            } else {
                return ConformanceResponse.runtimeError("No payload provided.");
            }
        } catch (Exception e) {
            return ConformanceResponse.parseError(
                    "parse failed for " + req.message_type() + ": " + e.getMessage());
        }

        WireFormat out = req.requested_output_format();
        try {
            return switch (out) {
                case PROTOBUF -> ConformanceResponse.protobufPayload(Protobuf.toByteArray(message));
                case JSON -> ConformanceResponse.jsonPayload(ProtobufJson.toJson(message));
                case JSPB -> ConformanceResponse.skipped("JSPB output not implemented.");
                case TEXT_FORMAT -> ConformanceResponse.skipped("TEXT_FORMAT output not implemented.");
                case UNSPECIFIED -> ConformanceResponse.runtimeError("Unspecified output format.");
            };
        } catch (Exception e) {
            return ConformanceResponse.serializeError(
                    "serialize failed for " + req.message_type() + " (" + out + "): " + e.getMessage());
        }
    }
}
