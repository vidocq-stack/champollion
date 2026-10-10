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

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.spi.JsonProvider;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** JSON in and out of the application, for the test to check over HTTP. */
@Path("/json")
public class JsonResource {

    /** The JSON-P and JSON-B implementations the application gets from the API. */
    @GET
    @Path("/providers")
    @Produces(MediaType.TEXT_PLAIN)
    public String providers() throws Exception {
        try (Jsonb jsonb = JsonbBuilder.create()) {
            return JsonProvider.provider().getClass().getName() + "|" + jsonb.getClass().getName();
        }
    }

    /** Serialises an order with the application's JSON-B, through the CDI-managed adapter. */
    @GET
    @Path("/order")
    @Produces(MediaType.APPLICATION_JSON)
    public String order() throws Exception {
        Order order = new Order();
        order.customer = "alice";
        order.quantity = 3;
        try (Jsonb jsonb = JsonbBuilder.create()) {
            return jsonb.toJson(order);
        }
    }

    /** Reads an order with the application's JSON-B and answers what it understood. */
    @POST
    @Path("/order")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public String readOrder(String body) throws Exception {
        try (Jsonb jsonb = JsonbBuilder.create()) {
            Order order = jsonb.fromJson(body, Order.class);
            return order.customer + "|" + order.quantity;
        }
    }
}
