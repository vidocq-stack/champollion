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
package io.vidocq.champollion.protobuf.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.champollion.protobuf.Descriptors;
import io.vidocq.champollion.protobuf.codegen.internal.ProtoParser;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests M2.6 — résolveur + emitter de services gRPC.
 *
 * <p>Spec : <a href="https://protobuf.dev/programming-guides/proto3/#services">Proto3 §Services</a>.</p>
 */
class ServiceCodegenTest {

    private Descriptors.FileDescriptor resolve(String src) {
        return SchemaResolver.resolve(ProtoParser.parse("inline.proto", src));
    }

    @Nested
    @DisplayName("Resolver — ServiceDescriptor + MethodDescriptor")
    class Resolver {

        @Test
        void unary_rpc_resolved() {
            Descriptors.FileDescriptor f = resolve("""
                    syntax = "proto3";
                    package svc;
                    message Hello { string name = 1; }
                    message Bye { string text = 1; }
                    service Greeter {
                      rpc Greet (Hello) returns (Bye);
                    }
                    """);
            assertEquals(1, f.services().size());
            Descriptors.ServiceDescriptor s = f.findService("Greeter");
            assertNotNull(s);
            assertEquals("svc.Greeter", s.fullName());
            assertEquals(1, s.methods().size());
            Descriptors.MethodDescriptor m = s.findMethod("Greet");
            assertEquals("svc.Hello", m.inputType());
            assertEquals("svc.Bye", m.outputType());
            assertTrue(m.isUnary());
        }

        @Test
        void streaming_flags_preserved() {
            Descriptors.FileDescriptor f = resolve("""
                    syntax = "proto3";
                    message Req {}
                    message Resp {}
                    service S {
                      rpc ServerStream (Req) returns (stream Resp);
                      rpc ClientStream (stream Req) returns (Resp);
                      rpc BidiStream (stream Req) returns (stream Resp);
                    }
                    """);
            Descriptors.ServiceDescriptor s = f.findService("S");
            Descriptors.MethodDescriptor ss = s.findMethod("ServerStream");
            assertTrue(ss.serverStreaming());
            assertEquals(false, ss.clientStreaming());

            Descriptors.MethodDescriptor cs = s.findMethod("ClientStream");
            assertTrue(cs.clientStreaming());
            assertEquals(false, cs.serverStreaming());

            Descriptors.MethodDescriptor bs = s.findMethod("BidiStream");
            assertTrue(bs.clientStreaming());
            assertTrue(bs.serverStreaming());
        }

        @Test
        void unresolved_input_type_throws() {
            assertThrows(SchemaResolver.SchemaResolutionException.class, () -> resolve("""
                    syntax = "proto3";
                    message Resp {}
                    service S { rpc Foo (Missing) returns (Resp); }
                    """));
        }

        @Test
        void scalar_as_rpc_param_throws() {
            assertThrows(Exception.class, () -> resolve("""
                    syntax = "proto3";
                    message Resp {}
                    service S { rpc Foo (int32) returns (Resp); }
                    """));
        }
    }

    @Nested
    @DisplayName("Emitter — interface Java avec annotations")
    class Emitter {

        @Test
        void emits_interface_for_unary_service() {
            Descriptors.FileDescriptor f = resolve("""
                    syntax = "proto3";
                    package app;
                    message Req { string q = 1; }
                    message Resp { string a = 1; }
                    service Greeter {
                      rpc Greet (Req) returns (Resp);
                    }
                    """);
            Map<String, String> emitted = new JavaEmitter("io.test").emit(f);
            String src = emitted.get("io.test.Greeter");
            assertNotNull(src);
            assertTrue(src.contains("@ProtobufService(\"app.Greeter\")"), src);
            assertTrue(src.contains("public interface Greeter {"), src);
            assertTrue(src.contains("@ProtobufRpc(value = \"Greet\")"), src);
            assertTrue(src.contains("Resp greet(Req request);"), src);
        }

        @Test
        void emits_streaming_marker_for_streaming_rpc() {
            Descriptors.FileDescriptor f = resolve("""
                    syntax = "proto3";
                    message Req {}
                    message Resp {}
                    service S { rpc Sub (Req) returns (stream Resp); }
                    """);
            Map<String, String> emitted = new JavaEmitter("io.x").emit(f);
            String src = emitted.get("io.x.S");
            assertTrue(src.contains("serverStreaming = true"), src);
            assertTrue(src.contains("Flow.Publisher"), src);
        }

        @Test
        void service_message_and_enum_all_emitted_in_one_file_per_top_level() {
            Descriptors.FileDescriptor f = resolve("""
                    syntax = "proto3";
                    enum Status { OK = 0; ERR = 1; }
                    message R { string v = 1; }
                    service Svc { rpc Do (R) returns (R); }
                    """);
            Map<String, String> emitted = new JavaEmitter("io.test").emit(f);
            assertNotNull(emitted.get("io.test.Status"));
            assertNotNull(emitted.get("io.test.R"));
            assertNotNull(emitted.get("io.test.Svc"));
        }
    }
}
