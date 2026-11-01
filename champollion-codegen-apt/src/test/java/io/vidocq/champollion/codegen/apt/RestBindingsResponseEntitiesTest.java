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
package io.vidocq.champollion.codegen.apt;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The entity of a {@code Response} is only known from the method body: the processor reads it, binds its type, and
 * lists it for Vidocq's module check.
 */
class RestBindingsResponseEntitiesTest {

    /** The part of {@code jakarta.ws.rs.core} the processor reads. */
    private static final Map<String, String> JAXRS_CORE = Map.of(
            "jakarta.ws.rs.core.Response", """
                    package jakarta.ws.rs.core;
                    public abstract class Response {
                        public static ResponseBuilder ok() { return null; }
                        public static ResponseBuilder ok(Object entity) { return null; }
                        public static ResponseBuilder ok(Object entity, String type) { return null; }
                        public static ResponseBuilder accepted(Object entity) { return null; }
                        public static ResponseBuilder status(int status) { return null; }
                        public static ResponseBuilder created(java.net.URI location) { return null; }
                        public abstract static class ResponseBuilder {
                            public abstract Response build();
                            public abstract ResponseBuilder entity(Object entity);
                            public abstract ResponseBuilder header(String name, Object value);
                        }
                    }
                    """,
            "jakarta.ws.rs.core.GenericEntity", """
                    package jakarta.ws.rs.core;
                    public class GenericEntity<T> {
                        protected GenericEntity(T entity) { }
                        public GenericEntity(T entity, java.lang.reflect.Type type) { }
                    }
                    """);

    private static final Map<String, String> SOURCES = Map.ofEntries(
            Map.entry("resp.Orders", """
                    package resp;
                    import jakarta.ws.rs.*;
                    import jakarta.ws.rs.core.*;
                    @Path("/orders")
                    public class Orders {
                        @GET public Response one() { return Response.ok(new Order("o1", 2)).build(); }
                        @GET public Response typed() { return Response.ok(new Invoice(), "application/json").build(); }
                        @POST public Response create() {
                            return Response.created(java.net.URI.create("/x")).header("a", "b").entity(new Receipt("r")).build();
                        }
                        @GET public Response status() { return Response.status(202).entity(java.util.List.of(new Line(1))).build(); }
                        @GET public Response accepted() { return Response.accepted(new Ticket("t")).build(); }
                        @GET public Response generic() {
                            java.util.List<Tag> tags = java.util.List.of();
                            return Response.ok(new GenericEntity<java.util.List<Tag>>(tags) { }).build();
                        }
                        @GET public Response untyped() { Object o = new Hidden("h"); return Response.ok(o).build(); }
                        @GET public Response helper() { return build(new Note("n")); }
                        private static Response build(Note note) { return Response.ok(note).build(); }
                    }
                    """),
            Map.entry("resp.Mapper", """
                    package resp;
                    import jakarta.ws.rs.core.Response;
                    public class Mapper {
                        public Response toResponse(RuntimeException e) { return Response.status(500).entity(new Problem(e.getMessage())).build(); }
                    }
                    """),
            Map.entry("resp.Order", "package resp; public record Order(String id, int qty) {}"),
            Map.entry("resp.Invoice", "package resp; public class Invoice { public String number = \"n\"; }"),
            Map.entry("resp.Receipt", "package resp; public record Receipt(String id) {}"),
            Map.entry("resp.Line", "package resp; public record Line(int n) {}"),
            Map.entry("resp.Ticket", "package resp; public record Ticket(String id) {}"),
            Map.entry("resp.Tag", "package resp; public record Tag(String name) {}"),
            Map.entry("resp.Hidden", "package resp; public record Hidden(String x) {}"),
            Map.entry("resp.Note", "package resp; public record Note(String text) {}"),
            Map.entry("resp.Problem", "package resp; public record Problem(String detail) {}"));

    private static GeneratedBindings resp;

    @BeforeAll
    static void compile(@TempDir Path tmp) throws Exception {
        Map<String, String> all = new java.util.HashMap<>(JAXRS_CORE);
        all.putAll(SOURCES);
        resp = GeneratedBindings.compile(tmp, all);
    }

    @AfterAll
    static void close() throws Exception {
        resp.close();
    }

    @Test
    void theEntitiesGivenAResponseGetABinding() {
        for (String type : List.of("Order", "Invoice", "Receipt", "Line", "Ticket", "Tag", "Note", "Problem")) {
            assertTrue(resp.hasBinding("resp." + type), () -> type + ": " + resp.notes());
        }
    }

    @Test
    void anEntityTypedObjectKeepsTheRuntime() {
        assertFalse(resp.hasBinding("resp.Hidden"), "its static type is Object: the class is known at run time only");
    }

    @Test
    void theyAreListedForTheModuleCheck() throws Exception {
        assertEquals(List.of("resp.Invoice", "resp.Line", "resp.Note", "resp.Order", "resp.Problem", "resp.Receipt",
                        "resp.Tag", "resp.Ticket"),
                Files.readAllLines(resp.out().resolve(JsonbStaticProcessor.RESPONSE_ENTITIES_FILE)));
    }

    @Test
    void aBoundEntityIsWrittenAsTheRuntimeWritesIt() throws Exception {
        Object order = resp.newInstance("resp.Order", "o1", 2);
        assertEquals(resp.writeRuntime("resp.Order", order), resp.writeStatic("resp.Order", order));
    }
}
