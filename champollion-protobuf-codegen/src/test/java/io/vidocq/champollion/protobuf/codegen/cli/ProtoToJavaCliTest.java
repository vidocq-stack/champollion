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
package io.vidocq.champollion.protobuf.codegen.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The command line generator: every {@code .proto} under a directory, one Java source per top-level type. */
class ProtoToJavaCliTest {

    private static final String PACKAGE = "demo.gen";

    private static final String SHOP_PROTO = """
            syntax = "proto3";
            package demo.v1;

            message Order {
              message Line { int64 qty = 1; }
              enum State { NEW = 0; DONE = 1; }
              repeated Line lines = 1;
              State state = 2;
              double d = 3; float f = 4; int32 i32 = 5; int64 i64 = 6; uint32 u32 = 7; uint64 u64 = 8;
              sint32 s32 = 9; sint64 s64 = 10; fixed32 f32 = 11; fixed64 f64 = 12; sfixed32 sf32 = 13;
              sfixed64 sf64 = 14; bool b = 15; string s = 16; bytes bs = 17;
              repeated int64 counts = 18;
            }

            enum Channel { WEB = 0; SHOP = 1; }

            service Shop {
              rpc Place(Order) returns (Order.Line);
              rpc Track(.demo.v1.Order) returns (stream Order);
            }
            """;

    private static final String LEGACY_PROTO = """
            edition = "2023";
            package demo.legacy;
            option features.repeated_field_encoding = EXPANDED;

            message Codes { repeated int32 values = 1; }
            """;

    private static Path write(Path dir, String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    @Test
    void every_proto_file_becomes_java_sources_in_the_target_package(@TempDir Path tmp) throws IOException {
        Path src = tmp.resolve("proto");
        Path out = tmp.resolve("java");
        write(src, "shop/shop.proto", SHOP_PROTO);
        write(src, "legacy/codes.proto", LEGACY_PROTO);
        write(src, "README.txt", "not a proto file");

        ProtoToJavaCli.main(new String[] {src.toString(), out.toString(), PACKAGE, "--static-parser"});

        Path pkg = out.resolve("demo/gen");
        String order = Files.readString(pkg.resolve("Order.java"));
        assertTrue(order.contains("public record Order("), order);
        assertTrue(order.contains("List<Order.Line> lines"), order);
        assertTrue(order.contains("byte[] bs"), order);
        assertTrue(order.contains("List<Long> counts"), order);
        assertTrue(order.contains("public record Line("), order);
        assertTrue(order.contains("public enum State {"), order);
        assertTrue(Files.readString(pkg.resolve("Channel.java")).contains("public enum Channel {"));

        String codes = Files.readString(pkg.resolve("Codes.java"));
        assertTrue(codes.contains("packed = false"), codes);
        assertFalse(Files.exists(pkg.resolve("README.java")));
    }

    @Test
    void a_directory_without_proto_files_writes_nothing(@TempDir Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("empty"));
        Path out = tmp.resolve("java");
        ProtoToJavaCli.main(new String[] {src.toString(), out.toString(), PACKAGE});
        assertFalse(Files.exists(out));
    }
}
