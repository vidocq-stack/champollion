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

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JSON-B customisations and the shapes the generated code implements beyond plain properties:
 * {@code @JsonbDateFormat}, {@code @JsonbNumberFormat} and {@code @JsonbNillable} on a property, its type, a
 * superclass or a package, and arrays. The bindings write and read what the runtime does; the JSON-B annotations of a
 * type or a package the generated code does not implement keep the runtime mapping.
 */
class RestBindingsCustomisationsTest {

    private static final Map<String, String> SOURCES = Map.ofEntries(
            Map.entry("fmt.Resource", """
                    package fmt;
                    import jakarta.ws.rs.*;
                    @Path("/fmt")
                    public class Resource {
                        @GET public Event event() { return null; }
                        @GET public Typed typed() { return null; }
                        @GET public Invoice invoice() { return null; }
                        @GET public Special special() { return null; }
                        @GET public Fields fields() { return null; }
                        @GET public Nulls nulls() { return null; }
                        @GET public fmt.pkg.Dated dated() { return null; }
                        @GET public fmt.vis.Visible visible() { return null; }
                        @GET public Ordered ordered() { return null; }
                        @GET public Implementing implementing() { return null; }
                        @GET public Arrays arrays() { return null; }
                        @GET public Event[] events() { return null; }
                        @GET public Generic generic() { return null; }
                    }
                    """),
            Map.entry("fmt.Event", """
                    package fmt;
                    import jakarta.json.bind.annotation.*;
                    public record Event(@JsonbDateFormat("dd/MM/yyyy") java.time.LocalDate on,
                                        @JsonbNumberFormat("#0.00") java.math.BigDecimal amount,
                                        @JsonbNumberFormat("#,##0") int count,
                                        @JsonbDateFormat(value = "d MMMM yyyy", locale = "fr") java.time.LocalDate french,
                                        java.time.LocalDateTime at) {}
                    """),
            Map.entry("fmt.Typed", """
                    package fmt;
                    import jakarta.json.bind.annotation.*;
                    @JsonbDateFormat("yyyy.MM.dd") @JsonbNumberFormat("#0.0")
                    public record Typed(java.time.LocalDate on, java.util.List<java.time.LocalDate> days,
                                        java.time.Duration lasting, double ratio, String label) {}
                    """),
            Map.entry("fmt.Invoice", """
                    package fmt;
                    import jakarta.json.bind.annotation.*;
                    public class Invoice {
                        @JsonbDateFormat("dd-MM-yyyy")
                        private java.time.LocalDate issued;
                        private double total;
                        public java.time.LocalDate getIssued() { return issued; }
                        public void setIssued(java.time.LocalDate issued) { this.issued = issued; }
                        @JsonbNumberFormat("#0.0")
                        public double getTotal() { return total; }
                        @JsonbNumberFormat("#0.000")
                        public void setTotal(double total) { this.total = total; }
                    }
                    """),
            Map.entry("fmt.Base", """
                    package fmt;
                    public class Base {
                        private java.time.LocalDate created;
                        public java.time.LocalDate getCreated() { return created; }
                        public void setCreated(java.time.LocalDate created) { this.created = created; }
                    }
                    """),
            Map.entry("fmt.Special", """
                    package fmt;
                    import jakarta.json.bind.annotation.*;
                    @JsonbDateFormat("MM/dd/yyyy")
                    public class Special extends Base {
                        private java.time.LocalDate due;
                        public java.time.LocalDate getDue() { return due; }
                        public void setDue(java.time.LocalDate due) { this.due = due; }
                    }
                    """),
            Map.entry("fmt.Fields", """
                    package fmt;
                    import jakarta.json.bind.annotation.*;
                    public class Fields {
                        @JsonbDateFormat("dd/MM/yy") public java.time.LocalDate on;
                        @JsonbNumberFormat("#,##0") public Long count;
                    }
                    """),
            Map.entry("fmt.Nulls", """
                    package fmt;
                    import jakarta.json.bind.annotation.*;
                    @JsonbNillable
                    public class Nulls {
                        private String a;
                        private String b;
                        public String getA() { return a; }
                        public void setA(String a) { this.a = a; }
                        @JsonbNillable(false)
                        public String getB() { return b; }
                        public void setB(String b) { this.b = b; }
                    }
                    """),
            Map.entry("fmt.pkg.package-info", """
                    @jakarta.json.bind.annotation.JsonbDateFormat("dd.MM.yyyy")
                    @jakarta.json.bind.annotation.JsonbNillable
                    package fmt.pkg;
                    """),
            Map.entry("fmt.pkg.Dated", """
                    package fmt.pkg;
                    public record Dated(java.time.LocalDate on, String note,
                                        @jakarta.json.bind.annotation.JsonbProperty(nillable = true) String kept) {}
                    """),
            Map.entry("fmt.vis.package-info", """
                    @jakarta.json.bind.annotation.JsonbVisibility(fmt.vis.AllFields.class)
                    package fmt.vis;
                    """),
            Map.entry("fmt.vis.AllFields", """
                    package fmt.vis;
                    public class AllFields implements jakarta.json.bind.config.PropertyVisibilityStrategy {
                        public boolean isVisible(java.lang.reflect.Field f) { return true; }
                        public boolean isVisible(java.lang.reflect.Method m) { return false; }
                    }
                    """),
            Map.entry("fmt.vis.Visible", """
                    package fmt.vis;
                    public record Visible(String name) {}
                    """),
            Map.entry("fmt.OrderedBase", """
                    package fmt;
                    @jakarta.json.bind.annotation.JsonbPropertyOrder({"b", "a"})
                    public class OrderedBase {
                        public String a;
                        public String b;
                    }
                    """),
            Map.entry("fmt.Dated", """
                    package fmt;
                    public interface Dated {
                        @jakarta.json.bind.annotation.JsonbDateFormat("dd/MM/yyyy")
                        java.time.LocalDate getOn();
                    }
                    """),
            Map.entry("fmt.Implementing", """
                    package fmt;
                    public final class Implementing implements Dated {
                        public java.time.LocalDate getOn() { return null; }
                    }
                    """),
            Map.entry("fmt.Arrays", """
                    package fmt;
                    public record Arrays(int[] ints, byte[] bytes, char[] chars, double[] doubles, String[] names,
                                         java.time.LocalDate[] days, Kind[] kinds, Inner[] inners, Long[] boxed) {
                        public enum Kind { A, B }
                        public record Inner(String v) {}
                    }
                    """),
            Map.entry("fmt.Generic", """
                    package fmt;
                    public record Generic(java.util.List<String>[] lists) {}
                    """),
            Map.entry("fmt.Ordered", """
                    package fmt;
                    public class Ordered extends OrderedBase {
                        public String c;
                    }
                    """));

    private static GeneratedBindings fmt;

    @BeforeAll
    static void compile(@TempDir Path tmp) throws Exception {
        fmt = GeneratedBindings.compile(tmp, SOURCES);
    }

    @AfterAll
    static void close() throws Exception {
        fmt.close();
    }

    /** The binding writes {@code value} as the runtime does, and reads that text back as the runtime does. */
    private static String assertSameAsTheRuntime(String type, Object value) throws Exception {
        assertTrue(fmt.hasBinding(type), () -> type + " has no binding: " + fmt.notes());
        String json = fmt.writeRuntime(type, value);
        assertEquals(json, fmt.writeStatic(type, value), type);
        assertSameRead(type, json);
        return json;
    }

    /** Both read {@code json} into the same object: compared through the runtime's own output. */
    private static void assertSameRead(String type, String json) throws Exception {
        assertEquals(fmt.writeRuntime(type, fmt.readRuntime(type, json)),
                fmt.writeRuntime(type, fmt.readStatic(type, json)), json);
    }

    private static Object bean(String type, Map<String, Object> properties) throws Exception {
        Object bean = fmt.type(type).getDeclaredConstructor().newInstance();
        for (var e : properties.entrySet()) {
            String setter = "set" + Character.toUpperCase(e.getKey().charAt(0)) + e.getKey().substring(1);
            for (var m : fmt.type(type).getMethods()) {
                if (m.getName().equals(setter)) m.invoke(bean, e.getValue());
            }
        }
        return bean;
    }

    @Test
    void formatsOnRecordComponents() throws Exception {
        Object event = fmt.newInstance("fmt.Event", LocalDate.of(1862, 4, 3), new BigDecimal("12.5"), 1234,
                LocalDate.of(1862, 4, 3), LocalDateTime.of(2026, 11, 1, 10, 15));
        String json = assertSameAsTheRuntime("fmt.Event", event);
        assertTrue(json.contains("\"on\":\"03/04/1862\"") && json.contains("\"french\":\"3 avril 1862\""), json);
    }

    @Test
    void formatsOnTheRecordTypeApplyToItsDatesAndNumbersOnly() throws Exception {
        Object typed = fmt.newInstance("fmt.Typed", LocalDate.of(2026, 11, 1), List.of(LocalDate.of(2026, 1, 2)),
                Duration.ofMinutes(5), 0.25, "x");
        String json = assertSameAsTheRuntime("fmt.Typed", typed);
        assertTrue(json.contains("\"on\":\"2026.11.01\""), json);
    }

    @Test
    void aClassPropertyIsFormattedFromItsFieldOrItsAccessors() throws Exception {
        Object invoice = bean("fmt.Invoice", Map.of("issued", LocalDate.of(2026, 11, 1), "total", 2.25));
        String json = assertSameAsTheRuntime("fmt.Invoice", invoice);
        assertTrue(json.contains("\"issued\":\"01-11-2026\""), json);
        assertSameRead("fmt.Invoice", "{\"total\":\"3.125\"}");
    }

    @Test
    void aFormatOnASubclassAppliesToTheInheritedProperties() throws Exception {
        Object special = bean("fmt.Special", Map.of("created", LocalDate.of(2026, 1, 2), "due", LocalDate.of(2026, 3, 4)));
        assertSameAsTheRuntime("fmt.Special", special);
    }

    @Test
    void formatsOnPublicFields() throws Exception {
        Object fields = fmt.type("fmt.Fields").getDeclaredConstructor().newInstance();
        fmt.type("fmt.Fields").getField("on").set(fields, LocalDate.of(2026, 11, 1));
        fmt.type("fmt.Fields").getField("count").set(fields, 1234567L);
        assertSameAsTheRuntime("fmt.Fields", fields);
    }

    @Test
    void nillableOnTheTypeAndOnAMember() throws Exception {
        String json = assertSameAsTheRuntime("fmt.Nulls", bean("fmt.Nulls", Map.of()));
        assertEquals("{\"a\":null}", json);
    }

    @Test
    void theAnnotationsOfAPackageApplyToItsTypes() throws Exception {
        Object dated = fmt.newInstance("fmt.pkg.Dated", LocalDate.of(2026, 11, 1), null, null);
        String json = assertSameAsTheRuntime("fmt.pkg.Dated", dated);
        assertEquals("{\"on\":\"01.11.2026\",\"note\":null,\"kept\":null}", json);
    }

    @Test
    void arraysOfPrimitivesLeavesEnumsAndBoundTypes() throws Exception {
        Class<?> kind = fmt.type("fmt.Arrays$Kind");
        Object kinds = java.lang.reflect.Array.newInstance(kind, 2);
        java.lang.reflect.Array.set(kinds, 0, fmt.enumConstant("fmt.Arrays$Kind", "B"));
        Object inners = java.lang.reflect.Array.newInstance(fmt.type("fmt.Arrays$Inner"), 2);
        java.lang.reflect.Array.set(inners, 1, fmt.newInstance("fmt.Arrays$Inner", "x"));
        Object arrays = fmt.newInstance("fmt.Arrays", new int[] {1, 2}, new byte[] {-3, 4}, new char[] {'c'},
                new double[] {0.5}, new String[] {"a", null}, new LocalDate[] {LocalDate.of(2026, 11, 1)}, kinds,
                inners, new Long[] {7L, null});
        String json = assertSameAsTheRuntime("fmt.Arrays", arrays);
        assertTrue(json.contains("\"ints\":[1,2]") && json.contains("\"kinds\":[\"B\",null]"), json);
        assertSameRead("fmt.Arrays", "{\"ints\":null,\"names\":[],\"inners\":[null,{\"v\":\"y\"}]}");
    }

    @Test
    void anArrayAsTheResultOfAResourceMethod() throws Exception {
        assertTrue(fmt.hasBinding("fmt.Event"));
        assertFalse(fmt.hasBinding("fmt.Generic"));
        assertTrue(fmt.noted("fmt.Generic", "an array of the generic type"), fmt.notes()::toString);
    }

    @Test
    void theAnnotationsOfATypeOrAPackageTheGeneratedCodeDoesNotImplementKeepTheRuntime() {
        assertFalse(fmt.hasBinding("fmt.vis.Visible"));
        assertTrue(fmt.noted("fmt.vis.Visible", "package fmt.vis carries @JsonbVisibility"), fmt.notes()::toString);
        assertFalse(fmt.hasBinding("fmt.Ordered"));
        assertTrue(fmt.noted("fmt.Ordered", "superclass fmt.OrderedBase carries @JsonbPropertyOrder"),
                fmt.notes()::toString);
        assertFalse(fmt.hasBinding("fmt.Implementing"));
        assertTrue(fmt.noted("fmt.Implementing", "fmt.Dated.getOn() it implements"), fmt.notes()::toString);
    }
}
