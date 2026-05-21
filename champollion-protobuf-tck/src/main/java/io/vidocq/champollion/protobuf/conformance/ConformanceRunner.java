package io.vidocq.champollion.protobuf.conformance;

import io.vidocq.champollion.protobuf.Protobuf;
import io.vidocq.champollion.protobuf.ProtobufJson;
import io.vidocq.champollion.protobuf.conformance.ConformanceMessages.ConformanceRequest;
import io.vidocq.champollion.protobuf.conformance.ConformanceMessages.ConformanceResponse;
import io.vidocq.champollion.protobuf.conformance.ConformanceMessages.WireFormat;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;

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

        // En M1.6, on ne connaît aucun message_type "TestAllTypesProto3" — le
        // codegen .proto viendra en M2. On répond skipped pour permettre au
        // harness de continuer.
        String type = req.message_type();
        if (type == null || !type.startsWith("io.vidocq.champollion.protobuf.")) {
            return ConformanceResponse.skipped(
                    "M1.6 skeleton: message_type '" + type + "' not bound — awaiting M2 codegen.");
        }

        // Réservé pour M2/M3 : dispatch sur Class<?> par message_type et appel
        // de Protobuf.parser / ProtobufJson.fromJson selon la WireFormat demandée.
        return ConformanceResponse.skipped("M1.6 skeleton: in-tree types pending.");
    }
}
