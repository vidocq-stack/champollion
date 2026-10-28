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
package io.vidocq.champollion.protobuf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.champollion.protobuf.Descriptors.Cardinality;
import io.vidocq.champollion.protobuf.Descriptors.Descriptor;
import io.vidocq.champollion.protobuf.Descriptors.EnumDescriptor;
import io.vidocq.champollion.protobuf.Descriptors.EnumValueDescriptor;
import io.vidocq.champollion.protobuf.Descriptors.FieldDescriptor;
import io.vidocq.champollion.protobuf.Descriptors.FileDescriptor;
import io.vidocq.champollion.protobuf.Descriptors.MethodDescriptor;
import io.vidocq.champollion.protobuf.Descriptors.ServiceDescriptor;
import io.vidocq.champollion.protobuf.Descriptors.Syntax;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Lookups of the descriptor model, and the descriptor synthesized from an annotated record. */
class DescriptorLookupTest {

    private static final String GREETER = "Greeter";

    public enum Mood { CALM, ANGRY }

    @ProtobufMessage("demo.Typed")
    public record Typed(
            @ProtobufField(number = 2, type = FieldType.ENUM) Mood mood,
            @ProtobufField(number = 1, type = FieldType.MESSAGE) Typed child,
            @ProtobufField(number = 3, type = FieldType.INT32, packed = false) List<Integer> values,
            @ProtobufField(number = 4, type = FieldType.STRING) List<String> tags,
            int ignored
    ) {}

    @ProtobufMessage
    public record Wildcard(@ProtobufField(number = 1, type = FieldType.STRING) List<? extends CharSequence> values) {}

    @Test
    void a_synthesized_descriptor_names_its_message_and_enum_types() {
        Descriptor d = Descriptors.forRecord(Typed.class);
        assertEquals("demo.Typed", d.name());
        assertEquals("io.vidocq.champollion.protobuf.demo.Typed", d.fullName());
        assertEquals(List.of(1, 2, 3, 4), List.copyOf(d.fieldsByNumber().keySet()));
        assertEquals(Typed.class.getName(), d.findFieldByNumber(1).messageTypeName());
        assertEquals(Mood.class.getName(), d.findFieldByNumber(2).enumTypeName());
        assertFalse(d.findFieldByNumber(3).packed());
        assertTrue(d.findFieldByNumber(3).isRepeated());
        assertFalse(d.findFieldByNumber(4).packed());
        assertNull(d.findFieldByName("ignored"));
        assertNull(d.findFieldByJsonName("nothing"));
    }

    @Test
    void a_list_element_type_must_be_a_class() {
        assertThrows(IllegalArgumentException.class, () -> Descriptors.forRecord(Wildcard.class));
    }

    @Test
    void file_descriptor_lookups() {
        Descriptor msg = new Descriptor("Hello", "demo.Hello", List.of(), List.of(), List.of());
        MethodDescriptor say = new MethodDescriptor("Say", "demo.Hello", "demo.Hello", false, false);
        MethodDescriptor stream = new MethodDescriptor("Stream", "demo.Hello", "demo.Hello", false, true);
        ServiceDescriptor svc = new ServiceDescriptor(GREETER, "demo.Greeter", List.of(say, stream));
        FileDescriptor file = new FileDescriptor("demo.proto", null, null, List.of(msg), List.of(), List.of(svc));

        assertEquals("", file.packageName());
        assertEquals(Syntax.PROTO3, file.syntax());
        assertSame(msg, file.findMessageType("Hello"));
        assertSame(msg, file.findMessageType("demo.Hello"));
        assertNull(file.findMessageType("Bye"));
        assertSame(svc, file.findService(GREETER));
        assertSame(svc, file.findService("demo.Greeter"));
        assertNull(file.findService("Other"));
        assertSame(say, svc.findMethod("Say"));
        assertNull(svc.findMethod("Shout"));
        assertTrue(say.isUnary());
        assertFalse(stream.isUnary());
        assertFalse(new MethodDescriptor("Up", "a", "b", true, false).isUnary());
    }

    @Test
    void the_five_argument_file_descriptor_has_no_service() {
        FileDescriptor file = new FileDescriptor("x.proto", "x", Syntax.PROTO2, List.of(), List.of());
        assertTrue(file.services().isEmpty());
        assertNull(file.findService(GREETER));
    }

    @Test
    void enum_values_are_found_by_number() {
        EnumValueDescriptor calm = new EnumValueDescriptor("CALM", 0);
        EnumDescriptor e = new EnumDescriptor("Mood", "demo.Mood", List.of(calm));
        assertSame(calm, e.findValueByNumber(0));
        assertNull(e.findValueByNumber(1));
    }

    @Test
    void a_field_descriptor_derives_its_defaults() {
        FieldDescriptor fd = new FieldDescriptor("first_name", null, 1, FieldType.STRING, null, false, null, null, null);
        assertEquals("firstName", fd.jsonName());
        assertEquals(Cardinality.IMPLICIT, fd.cardinality());
        assertSame(Descriptors.Features.PROTO3_DEFAULTS, fd.features());
        assertThrows(NullPointerException.class,
                () -> new FieldDescriptor("x", "x", 1, null, Cardinality.IMPLICIT, false, null, null));
    }
}
