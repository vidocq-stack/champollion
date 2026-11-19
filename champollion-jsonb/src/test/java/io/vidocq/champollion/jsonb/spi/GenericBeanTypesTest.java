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
package io.vidocq.champollion.jsonb.spi;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The bean type of a generic class is the class with its own type variables (CDI 4.1 section 2.2.1). */
class GenericBeanTypesTest {

    /** A class with a bounded type variable, and fields whose types are the bean types the tests give. */
    static class Bounded<T extends Number> {
        Bounded<T> own;
        Bounded<Integer> ofInteger;
        Other<T> otherClass;
        Pair<T, T> twice;
    }

    static class Other<T extends Number> {
        Bounded<T> variableOfAnotherClass;
    }

    static class Pair<A, B> {
        Pair<A, B> own;
        Pair<B, A> swapped;
    }

    private static Type typeOf(Class<?> declaring, String field) throws NoSuchFieldException {
        return declaring.getDeclaredField(field).getGenericType();
    }

    @Test
    void theTypeWithTheOwnTypeVariablesOfTheClassIsFound() throws Exception {
        Type own = typeOf(Bounded.class, "own");
        List<Type> beanTypes = List.of(Object.class, typeOf(Bounded.class, "ofInteger"), own);
        assertSame(own, GenericBeanTypes.ofClass(beanTypes, Bounded.class));
    }

    /** The type of a producer the class declares: another parameterization of the class. */
    @Test
    void anotherParameterizationOfTheClassIsNotItsBeanType() throws Exception {
        List<Type> beanTypes = List.of(Object.class, typeOf(Bounded.class, "ofInteger"));
        assertNull(GenericBeanTypes.ofClass(beanTypes, Bounded.class));
    }

    @Test
    void theRawClassAndTheTypesOfOtherClassesAreNotItsBeanType() throws Exception {
        List<Type> beanTypes = List.of(Bounded.class, typeOf(Bounded.class, "otherClass"),
                typeOf(Bounded.class, "twice"));
        assertNull(GenericBeanTypes.ofClass(beanTypes, Bounded.class));
    }

    @Test
    void aTypeVariableOfAnotherDeclarationIsNotTheOwnOne() throws Exception {
        List<Type> beanTypes = List.of(typeOf(Other.class, "variableOfAnotherClass"));
        assertNull(GenericBeanTypes.ofClass(beanTypes, Bounded.class));
    }

    @Test
    void theTypeVariablesAreComparedInOrder() throws Exception {
        Type own = typeOf(Pair.class, "own");
        assertNull(GenericBeanTypes.ofClass(List.of(typeOf(Pair.class, "swapped")), Pair.class));
        assertSame(own, GenericBeanTypes.ofClass(List.of(typeOf(Pair.class, "swapped"), own), Pair.class));
    }
}
