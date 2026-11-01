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
 * The types of a dependency that an application's resources reach: the application lists them, and the processor,
 * run over the dependency with their names (as Vidocq's {@code generate} goal does), binds them there.
 */
class RestBindingsDependencyTest {

    private static final Map<String, String> LIBRARY = Map.of(
            "lib.Book", """
                    package lib;
                    public record Book(String title, Author author, java.util.List<Author> others) {}
                    """,
            "lib.Author", """
                    package lib;
                    public final class Author {
                        private String name;
                        public String getName() { return name; }
                        public void setName(String name) { this.name = name; }
                    }
                    """,
            "lib.Unreached", """
                    package lib;
                    public record Unreached(String x) {}
                    """);

    private static final Map<String, String> APPLICATION = Map.of(
            "app.Shelf", """
                    package app;
                    @jakarta.ws.rs.Path("/books")
                    public class Shelf {
                        @jakarta.ws.rs.GET public lib.Book get() { return null; }
                        @jakarta.ws.rs.POST public void put(Local local) { }
                    }
                    """,
            "app.Local", """
                    package app;
                    public record Local(lib.Book book) {}
                    """);

    @Test
    void theApplicationListsTheDependencyTypesItsResourcesReach(@TempDir Path tmp) throws Exception {
        try (var library = GeneratedBindings.compile(tmp.resolve("lib"), LIBRARY);
             var application = GeneratedBindings.compile(tmp.resolve("app"), APPLICATION, List.of(),
                     library.out())) {
            assertFalse(application.hasBinding("app.Local"), "it holds a type that has no binding here");
            assertEquals(List.of("lib.Author", "lib.Book"),
                    Files.readAllLines(application.out().resolve(JsonbStaticProcessor.DEPENDENCY_TYPES_FILE)),
                    "with the types its properties hold");
        }
    }

    /** As Vidocq's generate goal runs it: over the compiled classes of the dependency, the named ones only. */
    @Test
    void theNamedTypesOfACompiledDependencyAreBound(@TempDir Path tmp) throws Exception {
        try (var library = GeneratedBindings.compile(tmp.resolve("lib"), LIBRARY);
             var bound = GeneratedBindings.compile(tmp.resolve("bound"), Map.of(),
                     List.of("-A" + JsonbStaticProcessor.TYPES_OPTION + "=lib.Book"), library.out(),
                     List.of("lib.Book"))) {
            assertTrue(bound.hasBinding("lib.Book"), bound.notes()::toString);
            assertTrue(bound.hasBinding("lib.Author"), "a type of the same package");
            assertTrue(Files.readString(bound.out().resolve("META-INF/services/io.vidocq.champollion.jsonb.spi.JsonbBinding"))
                    .contains("lib.Author$$Binding"));
        }
    }

    @Test
    void theNamedTypesAreBoundWithTheTypesTheyReach(@TempDir Path tmp) throws Exception {
        try (var library = GeneratedBindings.compile(tmp, LIBRARY,
                List.of("-A" + JsonbStaticProcessor.TYPES_OPTION + "=lib.Book"), null)) {
            assertTrue(library.hasBinding("lib.Book"), library.notes()::toString);
            assertTrue(library.hasBinding("lib.Author"), "a type the named one reaches");
            assertFalse(library.hasBinding("lib.Unreached"));
            Object author = library.type("lib.Author").getDeclaredConstructor().newInstance();
            library.type("lib.Author").getMethod("setName", String.class).invoke(author, "Hugo");
            Object book = library.newInstance("lib.Book", "Les Misérables", author, List.of(author));
            String json = library.writeRuntime("lib.Book", book);
            assertEquals(json, library.writeStatic("lib.Book", book));
            assertEquals(json, library.writeRuntime("lib.Book", library.readStatic("lib.Book", json)));
        }
    }
}
