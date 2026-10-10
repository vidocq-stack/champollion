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
package io.vidocq.champollion.it.openliberty;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Champollion jars, unchanged, inside a WAR on Open Liberty (vidocq-workspace#15,
 * champollion#19): the application's JSON-P and JSON-B providers win over the server's, and a JSON-B
 * adapter is resolved as a CDI bean by Liberty's CDI, with injection (JSON-B 3.0 §5).
 */
class OpenLibertyPortabilityIT {

    private static final String BASE = System.getProperty("champollion.it.base");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    void applicationProvidersAreChampollion() throws Exception {
        String[] providers = send(HttpRequest.newBuilder(URI.create(BASE + "/json/providers")).GET()).split("\\|");
        assertTrue(providers[0].startsWith("io.vidocq.champollion."), providers[0]);
        assertTrue(providers[1].startsWith("io.vidocq.champollion."), providers[1]);
    }

    @Test
    void adapterIsACdiBeanWithInjection() throws Exception {
        String json = send(HttpRequest.newBuilder(URI.create(BASE + "/json/order")).GET());
        assertTrue(json.contains("\"customer\":\"<ALICE>\""), json);
    }

    @Test
    void orderRoundTrips() throws Exception {
        String json = send(HttpRequest.newBuilder(URI.create(BASE + "/json/order")).GET());
        String read = send(HttpRequest.newBuilder(URI.create(BASE + "/json/order"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)));
        assertEquals("alice|3", read);
    }

    private static String send(HttpRequest.Builder request) throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }
}
