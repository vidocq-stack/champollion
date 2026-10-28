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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.champollion.protobuf.Descriptors;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.ProtoFile;
import io.vidocq.champollion.protobuf.codegen.SchemaResolver.SchemaResolutionException;
import io.vidocq.champollion.protobuf.codegen.internal.ProtoParser;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Type references resolved across files and through the enclosing package scopes, as protoc resolves them. */
class SchemaScopeResolutionTest {

    private static final String BASE = """
            syntax = "proto3";
            package acme;
            message Money { int64 cents = 1; }
            enum Kind { A = 0; }
            """;

    private static ProtoFile parse(String name, String src) {
        return ProtoParser.parse(name, src);
    }

    @Test
    void references_are_found_in_an_enclosing_package_of_another_file() {
        ProtoFile billing = parse("billing.proto", """
                syntax = "proto3";
                package acme.billing;
                message Invoice { Money total = 1; Kind kind = 2; }
                service Billing { rpc Charge(Money) returns (Invoice); }
                """);
        Map<String, Descriptors.FileDescriptor> resolved =
                SchemaResolver.resolveAll(List.of(parse("base.proto", BASE), billing));
        Descriptors.FileDescriptor file = resolved.get("billing.proto");
        Descriptors.Descriptor invoice = file.findMessageType("Invoice");
        assertEquals("acme.Money", invoice.findFieldByName("total").messageTypeName());
        assertEquals("acme.Kind", invoice.findFieldByName("kind").enumTypeName());
        Descriptors.MethodDescriptor charge = file.findService("Billing").findMethod("Charge");
        assertEquals("acme.Money", charge.inputType());
        assertEquals("acme.billing.Invoice", charge.outputType());
    }

    @Test
    void a_symbol_defined_twice_is_rejected() {
        List<ProtoFile> files = List.of(parse("a.proto", BASE), parse("b.proto", BASE));
        SchemaResolutionException e = assertThrows(SchemaResolutionException.class,
                () -> SchemaResolver.resolveAll(files));
        assertTrue(e.getMessage().startsWith("Duplicate symbol: acme."), e.getMessage());
    }

    @Test
    void an_rpc_must_take_and_return_messages() {
        ProtoFile enumInput = parse("svc.proto", """
                syntax = "proto3";
                package acme;
                enum Kind { A = 0; }
                message M {}
                service S { rpc Call(Kind) returns (M); }
                """);
        assertThrows(SchemaResolutionException.class, () -> SchemaResolver.resolve(enumInput));
        ProtoFile unknownOutput = parse("svc.proto", """
                syntax = "proto3";
                message M {}
                service S { rpc Call(M) returns (Missing); }
                """);
        assertThrows(SchemaResolutionException.class, () -> SchemaResolver.resolve(unknownOutput));
    }
}
