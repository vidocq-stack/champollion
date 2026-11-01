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

import io.vidocq.champollion.jsonb.spi.JsonbBinding;
import jakarta.json.Json;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbException;
import jakarta.json.stream.JsonGenerator;
import jakarta.json.stream.JsonParser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The types a JAX-RS resource takes or returns get a generated binding, with no annotation: the output and the
 * objects read are the runtime's, and the types the generated code cannot map keep the runtime with a note saying why.
 */
class RestBindingsTest {

    /** The JAX-RS annotations the processor looks for, as sources: Champollion does not depend on the API. */
    private static final Map<String, String> JAXRS = Map.of(
            "jakarta.ws.rs.HttpMethod", """
                    package jakarta.ws.rs;
                    import java.lang.annotation.*;
                    @Target(ElementType.ANNOTATION_TYPE) @Retention(RetentionPolicy.RUNTIME)
                    public @interface HttpMethod { String value(); }
                    """,
            "jakarta.ws.rs.Path", """
                    package jakarta.ws.rs;
                    import java.lang.annotation.*;
                    @Target({ElementType.TYPE, ElementType.METHOD}) @Retention(RetentionPolicy.RUNTIME)
                    public @interface Path { String value(); }
                    """,
            "jakarta.ws.rs.GET", """
                    package jakarta.ws.rs;
                    import java.lang.annotation.*;
                    @Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME) @HttpMethod("GET")
                    public @interface GET {}
                    """,
            "jakarta.ws.rs.POST", """
                    package jakarta.ws.rs;
                    import java.lang.annotation.*;
                    @Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME) @HttpMethod("POST")
                    public @interface POST {}
                    """,
            "jakarta.ws.rs.PathParam", """
                    package jakarta.ws.rs;
                    import java.lang.annotation.*;
                    @Target(ElementType.PARAMETER) @Retention(RetentionPolicy.RUNTIME)
                    public @interface PathParam { String value(); }
                    """);

    private static final Map<String, String> SHOP = Map.ofEntries(
            Map.entry("shop.BookResource", """
                    package shop;
                    import jakarta.ws.rs.*;
                    import java.util.List;
                    @Path("/books")
                    public class BookResource {
                        @GET public List<Book> all() { return List.of(); }
                        @GET @Path("{id}") public Book one(@PathParam("id") long id) { return null; }
                        @POST public void add(NewBook book) { }
                        @GET @Path("legacy") public Legacy legacy() { return null; }
                        @GET @Path("dated") public Dated dated() { return null; }
                        @GET @Path("shelf") public Shelf shelf() { return null; }
                        public Ignored notAResourceMethod() { return null; }
                    }
                    """),
            Map.entry("shop.Book", """
                    package shop;
                    import java.math.BigDecimal;
                    import java.time.LocalDate;
                    import java.util.*;
                    import jakarta.json.bind.annotation.*;
                    public record Book(String title, Author author, LocalDate published, BigDecimal price,
                                       Genre genre, List<String> tags, Optional<String> isbn,
                                       Map<String, Integer> stock, int pages, boolean available,
                                       @JsonbProperty("summary") String blurb, @JsonbTransient String secret,
                                       List<Author> coAuthors, @JsonbNillable String note) {}
                    """),
            Map.entry("shop.Author", """
                    package shop;
                    public final class Author {
                        private String name;
                        private int born;
                        public Author() { }
                        public Author(String name, int born) { this.name = name; this.born = born; }
                        public String getName() { return name; }
                        public void setName(String name) { this.name = name; }
                        public int getBorn() { return born; }
                        public void setBorn(int born) { this.born = born; }
                        public String getDisplay() { return name + " (" + born + ")"; }
                        @Override public boolean equals(Object o) {
                            return o instanceof Author a && java.util.Objects.equals(name, a.name) && born == a.born;
                        }
                        @Override public int hashCode() { return java.util.Objects.hash(name, born); }
                    }
                    """),
            Map.entry("shop.NewBook", """
                    package shop;
                    public final class NewBook {
                        public String title;
                        public long authorId;
                        @Override public boolean equals(Object o) {
                            return o instanceof NewBook n && java.util.Objects.equals(title, n.title)
                                    && authorId == n.authorId;
                        }
                        @Override public int hashCode() { return java.util.Objects.hash(title, authorId); }
                    }
                    """),
            Map.entry("shop.Genre", """
                    package shop;
                    public enum Genre { NOVEL, ESSAY }
                    """),
            Map.entry("shop.Legacy", """
                    package shop;
                    public class Legacy { public String name; }
                    """),
            Map.entry("shop.Dated", """
                    package shop;
                    import jakarta.json.bind.annotation.JsonbDateFormat;
                    public record Dated(@JsonbDateFormat("dd/MM/yyyy") java.time.LocalDate day) {}
                    """),
            Map.entry("shop.Shelf", """
                    package shop;
                    public record Shelf(String label, Legacy legacy) {}
                    """),
            Map.entry("shop.Catalog", """
                    package shop;
                    import jakarta.ws.rs.*;
                    import java.util.*;
                    @Path("/catalog")
                    public class Catalog {
                        public record Entry(char grade, java.util.UUID id, Set<Genre> genres, Collection<Integer> ranks,
                                            java.time.Instant at, java.time.LocalDateTime local, java.time.OffsetDateTime offset,
                                            java.time.ZonedDateTime zoned, java.time.LocalTime time, java.time.Duration took,
                                            java.time.Period span, java.time.YearMonth month, java.math.BigInteger big,
                                            java.net.URI link, Character initial, Double ratio, Optional<other.Label> next,
                                            other.Label label) {}
                        @GET public java.util.concurrent.CompletionStage<Map<String, Entry>> entries() { return null; }
                        @POST public Counter count(Counter c) { return c; }
                        public record Node(String name, List<Node> children) {}
                        @GET @Path("tree") public Node tree() { return null; }
                    }
                    """),
            Map.entry("shop.Counter", """
                    package shop;
                    public final class Counter {
                        private int value;
                        private Long boxed;
                        public int getValue() { return value; }
                        public void setValue(int value) { this.value = value; }
                        public Long getBoxed() { return boxed; }
                        public void setBoxed(Long boxed) { this.boxed = boxed; }
                        @Override public boolean equals(Object o) {
                            return o instanceof Counter c && value == c.value && java.util.Objects.equals(boxed, c.boxed);
                        }
                        @Override public int hashCode() { return value; }
                    }
                    """),
            Map.entry("other.Label", """
                    package other;
                    public record Label(String text) {}
                    """),
            Map.entry("shop.Ignored", """
                    package shop;
                    public record Ignored(String x) {}
                    """));

    private static Path out;
    private static URLClassLoader loader;
    private static final List<String> notes = new ArrayList<>();
    private static Jsonb runtime;

    @BeforeAll
    static void compileTheShop(@TempDir Path tmp) throws Exception {
        out = tmp.resolve("out");
        Files.createDirectories(out);
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var fm = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
        List<JavaFileObject> sources = new ArrayList<>();
        Map<String, String> all = new LinkedHashMap<>(JAXRS);
        all.putAll(SHOP);
        all.forEach((fqn, source) -> sources.add(new SimpleJavaFileObject(
                URI.create("string:///" + fqn.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension),
                JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return source; }
        }));
        String cp = System.getProperty("java.class.path");
        String mp = System.getProperty("jdk.module.path", "");
        boolean ok = compiler.getTask(null, fm, diagnostics,
                List.of("-d", out.toString(), "--release", "25",
                        "-cp", mp.isEmpty() ? cp : cp + java.io.File.pathSeparator + mp,
                        "-processor", JsonbStaticProcessor.class.getName()),
                null, sources).call();
        fm.close();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.NOTE) notes.add(d.getMessage(null));
        }
        assertTrue(ok, () -> "compilation must succeed: " + diagnostics.getDiagnostics());
        loader = new URLClassLoader(new URL[] {out.toUri().toURL()}, RestBindingsTest.class.getClassLoader());
        // The test's loader does not see the generated services file: this Jsonb maps every type by introspection.
        runtime = JsonbBuilder.create();
    }

    @AfterAll
    static void close() throws Exception {
        runtime.close();
        loader.close();
    }

    @SuppressWarnings("unchecked")
    private static JsonbBinding<Object> binding(String type) throws Exception {
        return (JsonbBinding<Object>) loader.loadClass(type + "$$Binding").getDeclaredConstructor().newInstance();
    }

    private static boolean hasBinding(String type) {
        return Files.exists(out.resolve(type.replace('.', '/') + "$$Binding.class"));
    }

    private static Object newInstance(String type, Object... args) throws Exception {
        for (var c : loader.loadClass(type).getDeclaredConstructors()) {
            if (c.getParameterCount() == args.length) return c.newInstance(args);
        }
        throw new AssertionError("no constructor");
    }

    private static Object enumConstant(String type, String name) throws Exception {
        for (Object c : loader.loadClass(type).getEnumConstants()) {
            if (((Enum<?>) c).name().equals(name)) return c;
        }
        throw new AssertionError(name);
    }

    private static String writeStatic(String type, Object value) throws Exception {
        var sw = new StringWriter();
        try (JsonGenerator g = Json.createGenerator(sw)) {
            binding(type).write(g, value);
        }
        return sw.toString();
    }

    private static Object readStatic(String type, String json) throws Exception {
        try (JsonParser p = Json.createParser(new StringReader(json))) {
            return binding(type).read(p);
        }
    }

    private static Object book(String note) throws Exception {
        Object author = newInstance("shop.Author", "Hugo", 1802);
        return newInstance("shop.Book", "Les Misérables", author, LocalDate.of(1862, 4, 3), new BigDecimal("12.50"),
                enumConstant("shop.Genre", "NOVEL"), List.of("classic", "france"), java.util.Optional.of("978-2"),
                new LinkedHashMap<>(Map.of("paris", 3)), 1488, true, "A story", "hidden",
                List.of(newInstance("shop.Author", "Dumas", 1802)), note);
    }

    @Test
    void theTypesResourcesReachGetABinding() {
        assertTrue(hasBinding("shop.Book"));
        assertTrue(hasBinding("shop.Author"), "a type held by a reached type");
        assertTrue(hasBinding("shop.NewBook"), "the entity parameter of a POST");
    }

    @Test
    void theOthersKeepTheRuntimeWithANoteSayingWhy() {
        assertFalse(hasBinding("shop.Legacy"));
        assertFalse(hasBinding("shop.Dated"));
        assertFalse(hasBinding("shop.Shelf"), "it holds a type that keeps the runtime mapping");
        assertFalse(hasBinding("shop.Ignored"), "not reached by a resource method");
        assertTrue(notes.stream().anyMatch(n -> n.contains("shop.Legacy") && n.contains("not final")), notes::toString);
        assertTrue(notes.stream().anyMatch(n -> n.contains("shop.Dated") && n.contains("@JsonbDateFormat")),
                notes::toString);
        assertTrue(notes.stream().anyMatch(n -> n.contains("shop.Shelf") && n.contains("shop.Legacy")),
                notes::toString);
    }

    @Test
    void theServicesFileListsThem() throws Exception {
        String services = Files.readString(out.resolve("META-INF/services/io.vidocq.champollion.jsonb.spi.JsonbBinding"));
        assertTrue(services.contains("shop.Book$$Binding"), services);
        assertTrue(services.contains("shop.Author$$Binding"), services);
        assertTrue(services.contains("shop.NewBook$$Binding"), services);
    }

    @Test
    void aRecordIsWrittenAsTheRuntimeWritesIt() throws Exception {
        for (Object book : List.of(book("a note"), book(null))) {
            assertEquals(runtime.toJson(book, (Type) loader.loadClass("shop.Book")), writeStatic("shop.Book", book));
        }
    }

    @Test
    void aRecordIsReadAsTheRuntimeReadsIt() throws Exception {
        Class<?> type = loader.loadClass("shop.Book");
        String json = runtime.toJson(book("x"), (Type) type);
        assertEquals(runtime.fromJson(json, type), readStatic("shop.Book", json));
        String partial = "{\"title\":\"T\",\"unknown\":{\"a\":[1,2]},\"tags\":null,\"isbn\":null}";
        assertEquals(runtime.fromJson(partial, type), readStatic("shop.Book", partial));
        String nullPrimitive = "{\"pages\":null}";
        assertThrows(JsonbException.class, () -> runtime.fromJson(nullPrimitive, type));
        assertThrows(JsonbException.class, () -> readStatic("shop.Book", nullPrimitive));
    }

    @Test
    void aFinalClassWithAccessorsIsWrittenAndReadAsTheRuntimeDoes() throws Exception {
        Class<?> type = loader.loadClass("shop.Author");
        Object author = newInstance("shop.Author", "Sand", 1804);
        assertEquals(runtime.toJson(author, (Type) type), writeStatic("shop.Author", author));
        String json = "{\"born\":1804,\"display\":\"ignored\",\"name\":\"Sand\"}";
        assertEquals(runtime.fromJson(json, type), readStatic("shop.Author", json));
    }

    @Test
    void aFinalClassWithPublicFieldsIsWrittenAndReadAsTheRuntimeDoes() throws Exception {
        Class<?> type = loader.loadClass("shop.NewBook");
        Object newBook = type.getDeclaredConstructor().newInstance();
        type.getField("title").set(newBook, "Notre-Dame");
        type.getField("authorId").set(newBook, 7L);
        assertEquals(runtime.toJson(newBook, (Type) type), writeStatic("shop.NewBook", newBook));
        String json = "{\"authorId\":7,\"title\":\"Notre-Dame\"}";
        assertEquals(runtime.fromJson(json, type), readStatic("shop.NewBook", json));
    }

    private static Object entry(Object next) throws Exception {
        return newInstance("shop.Catalog$Entry", 'A', java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                new java.util.LinkedHashSet<>(List.of(enumConstant("shop.Genre", "ESSAY"))), List.of(3, 1),
                java.time.Instant.parse("2026-10-31T10:15:30Z"), java.time.LocalDateTime.of(2026, 10, 31, 10, 15),
                java.time.OffsetDateTime.parse("2026-10-31T10:15:30+01:00"),
                java.time.ZonedDateTime.parse("2026-10-31T10:15:30+01:00[Europe/Paris]"), java.time.LocalTime.of(9, 5),
                java.time.Duration.ofMinutes(90), java.time.Period.ofDays(3), java.time.YearMonth.of(2026, 10),
                new java.math.BigInteger("123456789012345678901234567890"), URI.create("https://vidocq.dev/x"), 'Z', 0.5,
                java.util.Optional.ofNullable(next == null ? null : newInstance("other.Label", "next")),
                newInstance("other.Label", "hello"));
    }

    @Test
    void nestedRecordsSetsDatesAndOtherPackagesAreMappedAsTheRuntimeDoes() throws Exception {
        assertTrue(hasBinding("shop.Catalog$Entry"));
        assertTrue(hasBinding("other.Label"), "a public type of another package");
        Class<?> type = loader.loadClass("shop.Catalog$Entry");
        Object entry = entry("next");
        String json = runtime.toJson(entry, (Type) type);
        assertEquals(json, writeStatic("shop.Catalog$Entry", entry));
        assertEquals(runtime.fromJson(json, type), readStatic("shop.Catalog$Entry", json));
    }

    /** The runtime overflows its stack on a self-referencing record (BUG-20261101-01): the binding is checked alone. */
    @Test
    void aSelfReferencingRecordRoundTrips() throws Exception {
        Object leaf = newInstance("shop.Catalog$Node", "leaf", List.of());
        Object root = newInstance("shop.Catalog$Node", "root", java.util.Arrays.asList(leaf, null));
        String json = writeStatic("shop.Catalog$Node", root);
        assertEquals("{\"name\":\"root\",\"children\":[{\"name\":\"leaf\",\"children\":[]},null]}", json);
        assertEquals(root, readStatic("shop.Catalog$Node", json));
    }

    @Test
    void aMissingOptionalComponentIsReadAsTheRuntimeReadsIt() throws Exception {
        Class<?> type = loader.loadClass("shop.Book");
        assertEquals(runtime.fromJson("{\"title\":\"T\"}", type), readStatic("shop.Book", "{\"title\":\"T\"}"));
    }

    @Test
    void aNullForTheBoxedAndThePrimitivePropertyOfAClass() throws Exception {
        Class<?> type = loader.loadClass("shop.Counter");
        String boxedNull = "{\"boxed\":null,\"value\":2}";
        assertEquals(runtime.fromJson(boxedNull, type), readStatic("shop.Counter", boxedNull));
        String primitiveNull = "{\"value\":null}";
        Object viaRuntime;
        try {
            viaRuntime = runtime.fromJson(primitiveNull, type);
        } catch (JsonbException _) {
            assertThrows(JsonbException.class, () -> readStatic("shop.Counter", primitiveNull));
            return;
        }
        assertEquals(viaRuntime, readStatic("shop.Counter", primitiveNull));
    }
}
