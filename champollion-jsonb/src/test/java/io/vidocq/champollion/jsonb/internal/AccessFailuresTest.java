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
package io.vidocq.champollion.jsonb.internal;

import jakarta.json.bind.JsonbException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A binding that fails because the type's module does not open its package names that cause and the directive to
 * add (BUG-20261031-01): the tutorial's {@code Failed to read property author} and
 * {@code ctor failed for class com.example.lib.book.Book} said nothing about it.
 */
class AccessFailuresTest {

    /** A class of {@code java.base} in a package it opens to nobody. */
    private static Class<?> closedType() throws ClassNotFoundException {
        return Class.forName("sun.nio.ch.Net");
    }

    @Test
    void anAccessFailureNamesTheModuleThePackageAndTheOpensLine() throws Exception {
        var cause = new IllegalAccessException("cannot access class sun.nio.ch.Net");

        JsonbException e = AccessFailures.wrap("Failed to read property author", closedType(), cause);

        assertEquals("Failed to read property author: module java.base does not open package sun.nio.ch to "
                + "io.vidocq.champollion.jsonb — add `opens sun.nio.ch;` to its module-info.java", e.getMessage());
        assertSame(cause, e.getCause());
    }

    @Test
    void anInaccessibleObjectDeepInTheChainIsRecognised() throws Exception {
        var cause = new RuntimeException("wrapped", new java.lang.reflect.InaccessibleObjectException("no"));

        assertEquals("ctor failed for class sun.nio.ch.Net: module java.base does not open package sun.nio.ch to "
                        + "io.vidocq.champollion.jsonb — add `opens sun.nio.ch;` to its module-info.java",
                AccessFailures.wrap("ctor failed for " + closedType(), closedType(), cause).getMessage());
    }

    @Test
    void anotherFailureKeepsItsMessage() throws Exception {
        assertEquals("Failed to read property author",
                AccessFailures.wrap("Failed to read property author", closedType(),
                        new IllegalStateException("boom")).getMessage());
    }

    @Test
    void aTypeWhosePackageIsReachableKeepsTheMessage() {
        // This class is on the class path, or in Champollion's own module: either way its package is open to it.
        assertEquals("ctor failed for x", AccessFailures.wrap("ctor failed for x", AccessFailuresTest.class,
                new IllegalAccessException("no")).getMessage());
    }
}
