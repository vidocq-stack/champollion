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
 * superclass or a package, arrays, {@code @JsonbTypeAdapter} and {@code @JsonbCreator}. The bindings write and read what the runtime does; the JSON-B annotations of a
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
                        @GET public Order order() { return null; }
                        @GET public Account account() { return null; }
                        @GET public Point point() { return null; }
                        @GET public Unnamed unnamed() { return null; }
                        @GET public Injected injected() { return null; }
                        @GET public Celsius celsius() { return null; }
                        @GET public Wallet wallet() { return null; }
                        @GET public Purse purse() { return null; }
                        @GET public Drawing drawing() { return null; }
                        @GET public Circle circle() { return null; }
                        @GET public Open open() { return null; }
                        @GET public OpenA openA() { return null; }
                        @GET public Zoo zoo() { return null; }
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
            Map.entry("fmt.Money", """
                    package fmt;
                    public final class Money {
                        public final java.math.BigDecimal amount;
                        public final String currency;
                        public Money(java.math.BigDecimal amount, String currency) {
                            this.amount = amount;
                            this.currency = currency;
                        }
                        @Override public boolean equals(Object o) {
                            return o instanceof Money m && m.amount.equals(amount) && m.currency.equals(currency);
                        }
                        @Override public int hashCode() { return amount.hashCode(); }
                    }
                    """),
            Map.entry("fmt.MoneyAdapter", """
                    package fmt;
                    public class MoneyAdapter implements jakarta.json.bind.adapter.JsonbAdapter<Money, String> {
                        public String adaptToJson(Money m) { return m.amount + " " + m.currency; }
                        public Money adaptFromJson(String s) {
                            if (s.isEmpty()) throw new IllegalArgumentException("empty");
                            String[] parts = s.split(" ");
                            return new Money(new java.math.BigDecimal(parts[0]), parts[1]);
                        }
                    }
                    """),
            Map.entry("fmt.TagsAdapter", """
                    package fmt;
                    public class TagsAdapter
                            implements jakarta.json.bind.adapter.JsonbAdapter<java.util.List<String>, Label> {
                        public Label adaptToJson(java.util.List<String> tags) { return new Label(String.join(",", tags)); }
                        public java.util.List<String> adaptFromJson(Label l) { return java.util.List.of(l.text().split(",")); }
                    }
                    """),
            Map.entry("fmt.Label", """
                    package fmt;
                    public record Label(String text) {}
                    """),
            Map.entry("fmt.Order", """
                    package fmt;
                    import jakarta.json.bind.annotation.*;
                    public record Order(String id, @JsonbTypeAdapter(MoneyAdapter.class) Money total,
                                        @JsonbTypeAdapter(TagsAdapter.class) java.util.List<String> tags) {}
                    """),
            Map.entry("fmt.Account", """
                    package fmt;
                    import jakarta.json.bind.annotation.*;
                    public class Account {
                        private final String owner;
                        private final long number;
                        private String note;
                        @JsonbCreator
                        public Account(@JsonbProperty("owner") String owner, @JsonbProperty("number") long number) {
                            this.owner = owner;
                            this.number = number;
                        }
                        public String getOwner() { return owner; }
                        public long getNumber() { return number; }
                        public String getNote() { return note; }
                        public void setNote(String note) { this.note = note; }
                    }
                    """),
            Map.entry("fmt.Point", """
                    package fmt;
                    import jakarta.json.bind.annotation.*;
                    public final class Point {
                        private final int x;
                        private final int y;
                        private Point(int x, int y) { this.x = x; this.y = y; }
                        @JsonbCreator
                        public static Point of(@JsonbProperty("x") int x, @JsonbProperty("y") int y) {
                            if (x < 0) throw new IllegalArgumentException("negative");
                            return new Point(x, y);
                        }
                        public int getX() { return x; }
                        public int getY() { return y; }
                    }
                    """),
            Map.entry("fmt.Celsius", """
                    package fmt;
                    import jakarta.json.bind.annotation.*;
                    public class Celsius {
                        private double degrees;
                        private String origin = "constructor";
                        public Celsius() { }
                        @JsonbCreator
                        public static Celsius of(@JsonbProperty("degrees") double degrees) {
                            Celsius c = new Celsius();
                            c.degrees = degrees;
                            c.origin = "factory";
                            return c;
                        }
                        public double getDegrees() { return degrees; }
                        public String getOrigin() { return origin; }
                    }
                    """),
            Map.entry("fmt.MoneySerializer", """
                    package fmt;
                    public class MoneySerializer implements jakarta.json.bind.serializer.JsonbSerializer<Money> {
                        public void serialize(Money m, jakarta.json.stream.JsonGenerator g,
                                              jakarta.json.bind.serializer.SerializationContext ctx) {
                            g.writeStartObject();
                            ctx.serialize("amt", m.amount, g);
                            ctx.serialize("cur", new Label(m.currency), g);
                            g.writeEnd();
                        }
                    }
                    """),
            Map.entry("fmt.MoneyDeserializer", """
                    package fmt;
                    public class MoneyDeserializer implements jakarta.json.bind.serializer.JsonbDeserializer<Money> {
                        public Money deserialize(jakarta.json.stream.JsonParser p,
                                                 jakarta.json.bind.serializer.DeserializationContext ctx,
                                                 java.lang.reflect.Type type) {
                            if (p.currentEvent() == jakarta.json.stream.JsonParser.Event.VALUE_NULL) return null;
                            java.math.BigDecimal amount = null;
                            String currency = null;
                            while (p.next() == jakarta.json.stream.JsonParser.Event.KEY_NAME) {
                                String key = p.getString();
                                if (key.equals("amt")) amount = ctx.deserialize(java.math.BigDecimal.class, p);
                                else currency = ctx.deserialize(Label.class, p).text();
                            }
                            return new Money(amount, type.getTypeName().endsWith("Money") ? currency : "?");
                        }
                    }
                    """),
            Map.entry("fmt.Wallet", """
                    package fmt;
                    import jakarta.json.bind.annotation.*;
                    public class Wallet {
                        private Money cash;
                        @JsonbTypeSerializer(MoneySerializer.class)
                        public Money getCash() { return cash; }
                        @JsonbTypeDeserializer(MoneyDeserializer.class)
                        public void setCash(Money cash) { this.cash = cash; }
                    }
                    """),
            Map.entry("fmt.Purse", """
                    package fmt;
                    import jakarta.json.bind.annotation.*;
                    public record Purse(@JsonbTypeSerializer(MoneySerializer.class) Money cash) {}
                    """),
            Map.entry("fmt.Shape", """
                    package fmt;
                    import jakarta.json.bind.annotation.*;
                    @JsonbTypeInfo(key = "kind", value = {
                            @JsonbSubtype(alias = "circle", type = Circle.class),
                            @JsonbSubtype(alias = "square", type = Square.class)})
                    public sealed interface Shape permits Circle, Square {}
                    """),
            Map.entry("fmt.Circle", """
                    package fmt;
                    public record Circle(double radius) implements Shape {}
                    """),
            Map.entry("fmt.Square", """
                    package fmt;
                    public record Square(double side, String color) implements Shape {}
                    """),
            Map.entry("fmt.Drawing", """
                    package fmt;
                    public record Drawing(String name, java.util.List<Shape> shapes, Shape main, Circle circle) {}
                    """),
            Map.entry("fmt.Open", """
                    package fmt;
                    import jakarta.json.bind.annotation.*;
                    @JsonbTypeInfo({@JsonbSubtype(alias = "a", type = OpenA.class)})
                    public interface Open {}
                    """),
            Map.entry("fmt.OpenA", """
                    package fmt;
                    public record OpenA(String x) implements Open {}
                    """),
            Map.entry("fmt.OpenB", """
                    package fmt;
                    public class OpenB implements Open { public String getY() { return "y"; } }
                    """),
            Map.entry("fmt.Animal", "package fmt; public interface Animal { String getName(); }"),
            Map.entry("fmt.Dog", """
                    package fmt;
                    public class Dog implements Animal {
                        public String getName() { return "rex"; }
                        public int getLegs() { return 4; }
                    }
                    """),
            Map.entry("fmt.Vehicle", "package fmt; public abstract class Vehicle { public String getMake() { return \"m\"; } }"),
            Map.entry("fmt.Car", "package fmt; public class Car extends Vehicle { public int getDoors() { return 4; } }"),
            Map.entry("fmt.Zoo", """
                    package fmt;
                    public record Zoo(Animal animal, Vehicle vehicle, java.util.List<Animal> all, Object anything) {}
                    """),
            Map.entry("fmt.Unnamed", """
                    package fmt;
                    import jakarta.json.bind.annotation.*;
                    public class Unnamed {
                        private final String name;
                        @JsonbCreator
                        public Unnamed(String name) { this.name = name; }
                        public String getName() { return name; }
                    }
                    """),
            Map.entry("fmt.Injected", """
                    package fmt;
                    import jakarta.json.bind.annotation.*;
                    public record Injected(@JsonbTypeAdapter(ScopedAdapter.class) Money total) {}
                    """),
            Map.entry("fmt.ScopedAdapter", """
                    package fmt;
                    @jakarta.inject.Singleton
                    public class ScopedAdapter extends MoneyAdapter {}
                    """),
            Map.entry("jakarta.inject.Singleton", """
                    package jakarta.inject;
                    import java.lang.annotation.*;
                    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
                    public @interface Singleton {}
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
        // As a Vidocq application is compiled: the runtime reads a creator's parameter names, the binding too.
        fmt = GeneratedBindings.compile(tmp, SOURCES, List.of("-parameters"), null);
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
    void anArrayAsTheResultOfAResourceMethod() {
        assertTrue(fmt.hasBinding("fmt.Event"));
        assertFalse(fmt.hasBinding("fmt.Generic"));
        assertTrue(fmt.noted("fmt.Generic", "an array of the generic type"), fmt.notes()::toString);
    }

    @Test
    void anAdapterIsCalledDirectly() throws Exception {
        Object order = fmt.newInstance("fmt.Order", "o1",
                fmt.newInstance("fmt.Money", new BigDecimal("12.50"), "EUR"), List.of("a", "b"));
        String json = assertSameAsTheRuntime("fmt.Order", order);
        assertEquals("{\"id\":\"o1\",\"total\":\"12.50 EUR\",\"tags\":{\"text\":\"a,b\"}}", json);
        assertSameAsTheRuntime("fmt.Order", fmt.newInstance("fmt.Order", "o2", null, null));
        assertSameRead("fmt.Order", "{\"total\":null}");
        assertBothFail("fmt.Order", "{\"total\":\"\"}");
    }

    @Test
    void aCreatorConstructorThenTheOtherProperties() throws Exception {
        assertTrue(fmt.hasBinding("fmt.Account"), fmt.notes()::toString);
        assertSameRead("fmt.Account", "{\"note\":\"n\",\"owner\":\"Ann\",\"number\":7,\"other\":1}");
        assertSameRead("fmt.Account", "{\"owner\":\"Ann\"}");
        assertBothFail("fmt.Account", "{\"number\":null}");
    }

    @Test
    void aCreatorFactory() throws Exception {
        assertTrue(fmt.hasBinding("fmt.Point"), fmt.notes()::toString);
        assertSameRead("fmt.Point", "{\"y\":2,\"x\":1}");
        assertBothFail("fmt.Point", "{\"x\":-1}");
    }

    /** A class with a no-argument constructor is still created through its factory (BUG-20261101-09). */
    @Test
    void aCreatorFactoryWinsOverTheNoArgumentConstructor() throws Exception {
        assertSameRead("fmt.Celsius", "{\"degrees\":21.5}");
        assertTrue(fmt.writeStatic("fmt.Celsius", fmt.readStatic("fmt.Celsius", "{}")).contains("factory"));
    }

    @Test
    void aSerializerAndADeserializerAreCalledDirectly() throws Exception {
        Object wallet = bean("fmt.Wallet", Map.of("cash", fmt.newInstance("fmt.Money", new BigDecimal("3.5"), "EUR")));
        String json = assertSameAsTheRuntime("fmt.Wallet", wallet);
        assertEquals("{\"cash\":{\"amt\":3.5,\"cur\":{\"text\":\"EUR\"}}}", json);
        assertSameAsTheRuntime("fmt.Wallet", bean("fmt.Wallet", Map.of()));
        assertSameRead("fmt.Wallet", "{\"cash\":null}");
        // Through a Jsonb, the binding hands the serializer the Jsonb's own context.
        try (var jsonb = fmt.jsonbWithBindings()) {
            assertEquals(json, jsonb.toJson(wallet));
            assertEquals(json, jsonb.toJson(jsonb.fromJson(json, fmt.type("fmt.Wallet"))));
        }
    }

    @Test
    void aSealedInterfaceWithTypeInfoDispatchesOnTheValueAndOnTheDiscriminator() throws Exception {
        assertTrue(fmt.hasBinding("fmt.Shape"), fmt.notes()::toString);
        Object circle = fmt.newInstance("fmt.Circle", 1.5);
        Object square = fmt.newInstance("fmt.Square", 2.0, "red");
        Object drawing = fmt.newInstance("fmt.Drawing", "d", java.util.Arrays.asList(circle, square, null), square,
                circle);
        String json = assertSameAsTheRuntime("fmt.Drawing", drawing);
        assertTrue(json.contains("{\"kind\":\"square\",\"side\":2.0,\"color\":\"red\"}"), json);
        assertSameAsTheRuntime("fmt.Circle", circle);
        assertSameAsTheRuntime("fmt.Shape", square);
        assertBothFail("fmt.Shape", "{\"kind\":\"triangle\"}");
        assertBothFail("fmt.Shape", "{\"radius\":1.0,\"kind\":\"circle\"}");
    }

    /**
     * An interface with {@code @JsonbTypeInfo} that is not sealed: the listed records go through their binding, any
     * other class to the runtime, by its class (it writes no discriminator for it, as the runtime does).
     */
    @Test
    void anOpenInterfaceWithTypeInfoDispatchesAndFallsBackToTheRuntime() throws Exception {
        assertTrue(fmt.hasBinding("fmt.Open"), fmt.notes()::toString);
        assertSameAsTheRuntime("fmt.Open", fmt.newInstance("fmt.OpenA", "x"));
        Object other = fmt.type("fmt.OpenB").getDeclaredConstructor().newInstance();
        assertEquals(fmt.writeRuntime("fmt.Open", other), fmt.writeStatic("fmt.Open", other));
        assertSameAsTheRuntime("fmt.OpenA", fmt.newInstance("fmt.OpenA", "x"));
    }

    /** An interface or an abstract class as a property type: each value is mapped by its class, as the runtime does. */
    @Test
    void anInterfaceOrAbstractPropertyIsMappedByTheValuesClass() throws Exception {
        Object dog = fmt.type("fmt.Dog").getDeclaredConstructor().newInstance();
        Object car = fmt.type("fmt.Car").getDeclaredConstructor().newInstance();
        Object zoo = fmt.newInstance("fmt.Zoo", dog, car, List.of(dog), car);
        assertTrue(fmt.hasBinding("fmt.Zoo"), fmt.notes()::toString);
        assertTrue(fmt.hasBinding("fmt.Dog") && fmt.hasBinding("fmt.Car"), "the implementations get theirs");
        assertEquals(fmt.writeRuntime("fmt.Zoo", zoo), fmt.writeStatic("fmt.Zoo", zoo));
        // The runtime cannot create an Animal: it refuses to read a Zoo at all, the binding when an animal comes.
        assertBothFail("fmt.Zoo", "{\"animal\":{\"name\":\"x\"}}");
    }

    @Test
    void aCreatorParameterWithoutJsonbPropertyIsNamedByItsName() throws Exception {
        assertTrue(fmt.hasBinding("fmt.Unnamed"), fmt.notes()::toString);
        assertSameRead("fmt.Unnamed", "{\"name\":\"n\"}");
    }

    @Test
    void whatTheGeneratedCodeCannotCallKeepsTheRuntime() {
        assertFalse(fmt.hasBinding("fmt.Purse"));
        assertTrue(fmt.noted("fmt.Purse", "record component"), fmt.notes()::toString);
        assertFalse(fmt.hasBinding("fmt.Injected"));
        assertTrue(fmt.noted("fmt.Injected", "a CDI bean"), fmt.notes()::toString);
    }

    /** Both refuse {@code json} with a {@code JsonbException}. */
    private static void assertBothFail(String type, String json) {
        org.junit.jupiter.api.Assertions.assertThrows(jakarta.json.bind.JsonbException.class,
                () -> fmt.readRuntime(type, json));
        org.junit.jupiter.api.Assertions.assertThrows(jakarta.json.bind.JsonbException.class,
                () -> fmt.readStatic(type, json));
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
