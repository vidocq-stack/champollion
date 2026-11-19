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

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;

/**
 * The CDI bean type of a generic class, for the lookups of an adapter, serializer or deserializer whose class has
 * type variables: the runtime's ({@code CDI.current()}) and the registry's of {@code champollion-jsonb-cdi}.
 *
 * <p>The bean of a class {@code X<T>} has the type {@code X<T>}, with the class's own type variables (CDI 4.1 section
 * 2.2.1). When a type variable has a bound, the raw class {@code X} does not look that bean up (section 5.2.4), so
 * the lookups find it among the container's beans by this type.</p>
 */
public final class GenericBeanTypes {

    private GenericBeanTypes() {
    }

    /**
     * The type among {@code beanTypes} that is {@code type} with its own type variables, {@code type<T, ...>}, or
     * {@code null} when there is none. A producer the class declares has another parameterization of it,
     * {@code type<Integer>}, and is not the bean of the class.
     *
     * @param beanTypes the types of a bean
     * @param type      a class with type variables
     */
    public static Type ofClass(Iterable<Type> beanTypes, Class<?> type) {
        for (Type beanType : beanTypes) {
            if (beanType instanceof ParameterizedType parameterized && parameterized.getRawType() == type
                    && hasOwnTypeVariables(parameterized, type)) {
                return beanType;
            }
        }
        return null;
    }

    /** Whether the type arguments of {@code parameterized} are the type variables {@code type} declares, in order. */
    private static boolean hasOwnTypeVariables(ParameterizedType parameterized, Class<?> type) {
        Type[] arguments = parameterized.getActualTypeArguments();
        TypeVariable<?>[] own = type.getTypeParameters();
        if (arguments.length != own.length) return false;
        for (int i = 0; i < own.length; i++) {
            if (!(arguments[i] instanceof TypeVariable<?> variable) || variable.getGenericDeclaration() != type
                    || !variable.getName().equals(own[i].getName())) {
                return false;
            }
        }
        return true;
    }
}
