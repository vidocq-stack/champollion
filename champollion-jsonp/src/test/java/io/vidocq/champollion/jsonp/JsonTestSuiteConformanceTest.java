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
package io.vidocq.champollion.jsonp;

import jakarta.json.Json;
import jakarta.json.stream.JsonParser;
import jakarta.json.stream.JsonParsingException;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * RFC 8259 adversarial conformance harness: the nst/JSONTestSuite corpus
 * (MIT, vendored under {@code src/test/resources/jsontestsuite/}).
 *
 * <p>Contract per the corpus naming convention:</p>
 * <ul>
 *   <li>{@code y_*.json} — valid JSON, the parser MUST accept;</li>
 *   <li>{@code n_*.json} — invalid JSON, the parser MUST reject with the
 *       JSON-P contractual exception ({@link JsonParsingException}) — never a
 *       crash ({@code StackOverflowError}, NPE, AIOOBE...) and never silent
 *       acceptance;</li>
 *   <li>{@code i_*.json} — implementation-defined: accept or reject are both
 *       fine, but the parser must stay in control (contractual exception or
 *       success, no crash).</li>
 * </ul>
 *
 * <p>The whole document is drained through the streaming API (every event
 * consumed, strings and numbers materialized) so lazily-validated content is
 * actually exercised.</p>
 */
class JsonTestSuiteConformanceTest {

    @TestFactory
    Stream<DynamicTest> jsonTestSuite() throws Exception {
        List<Path> files = corpusFiles();
        if (files.size() < 300) {
            fail("JSONTestSuite corpus incomplete: " + files.size() + " files");
        }
        return files.stream().map(p -> DynamicTest.dynamicTest(p.getFileName().toString(), () -> {
            String name = p.getFileName().toString();
            byte[] content = Files.readAllBytes(p);
            switch (name.charAt(0)) {
                case 'y' -> assertDoesNotThrow(() -> parseFully(content),
                        "valid JSON rejected");
                case 'n' -> {
                    try {
                        parseFully(content);
                        fail("invalid JSON accepted");
                    } catch (JsonParsingException expected) {
                        // contractual rejection
                    }
                    // any other Throwable (StackOverflowError, NPE...) propagates and fails
                }
                case 'i' -> {
                    try {
                        parseFully(content);
                    } catch (JsonParsingException acceptable) {
                        // implementation-defined: rejection is fine too
                    } catch (NumberFormatException acceptable) {
                        // i_number_huge_exp & friends: the document parses fine but
                        // materializing the number exceeds BigDecimal's int scale
                        // ("Too many nonzero exponent digits"). Parsson defers to
                        // new BigDecimal(String) exactly the same way — matching the
                        // reference implementation on implementation-defined input.
                    }
                }
                default -> fail("unexpected corpus file: " + name);
            }
        }));
    }

    /** Drains the document through the streaming parser, materializing values. */
    private static void parseFully(byte[] content) {
        // Bytes, not String: the corpus includes invalid-UTF-8 cases the
        // parser must handle (encoding detection per RFC 8259 §8.1 / JSON-P).
        try (JsonParser parser = Json.createParser(new ByteArrayInputStream(content))) {
            while (parser.hasNext()) {
                JsonParser.Event e = parser.next();
                switch (e) {
                    case VALUE_STRING, KEY_NAME -> parser.getString();
                    case VALUE_NUMBER -> parser.getBigDecimal();
                    default -> { /* structural / literal events carry no payload */ }
                }
            }
        }
    }

    private static List<Path> corpusFiles() throws IOException {
        try {
            URI uri = JsonTestSuiteConformanceTest.class.getResource("/jsontestsuite").toURI();
            Path dir;
            if ("jar".equals(uri.getScheme())) {
                FileSystem fs = FileSystems.newFileSystem(uri, Map.of());
                dir = fs.getPath("/jsontestsuite");
            } else {
                dir = Path.of(uri);
            }
            try (Stream<Path> s = Files.list(dir)) {
                return s.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .sorted()
                        .toList();
            }
        } catch (java.net.URISyntaxException e) {
            throw new IOException(e);
        }
    }
}
