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

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Resolves the type variables of a member's generic type against the class being bound: in
 * {@code Dto extends Base<Address>}, the field {@code T value} of {@code Base} is an {@code Address} for {@code Dto}
 * (JSON-B 3.0 §3.11). A variable no superclass nor interface binds is left as it is.
 */
final class GenericTypes {

    /** The variables each class's superclasses and interfaces bind, read once per class. */
    private static final ClassValue<Map<TypeVariable<?>, Type>> BINDINGS = new ClassValue<>() {
        @Override
        protected Map<TypeVariable<?>, Type> computeValue(Class<?> type) {
            Map<TypeVariable<?>, Type> bindings = new HashMap<>();
            collect(type, bindings);
            return Map.copyOf(bindings);
        }
    };

    private GenericTypes() {}

    /** {@code type} with every variable {@code context}'s hierarchy binds replaced by its binding. */
    static Type resolve(Type type, Class<?> context) {
        Map<TypeVariable<?>, Type> bindings = BINDINGS.get(context);
        return bindings.isEmpty() ? type : substitute(type, bindings);
    }

    /** Adds the bindings of {@code type}'s generic superclass and interfaces, then of theirs, nearest first. */
    private static void collect(Class<?> type, Map<TypeVariable<?>, Type> bindings) {
        Type superclass = type.getGenericSuperclass();
        if (superclass != null) bind(superclass, bindings);
        for (Type itf : type.getGenericInterfaces()) bind(itf, bindings);
    }

    private static void bind(Type supertype, Map<TypeVariable<?>, Type> bindings) {
        Class<?> raw;
        if (supertype instanceof ParameterizedType p) {
            raw = (Class<?>) p.getRawType();
            TypeVariable<?>[] variables = raw.getTypeParameters();
            Type[] arguments = p.getActualTypeArguments();
            for (int i = 0; i < variables.length && i < arguments.length; i++) {
                // A nearer class may pass its own variable on (Middle<U> extends Base<U>): resolve it first.
                bindings.putIfAbsent(variables[i], substitute(arguments[i], bindings));
            }
        } else if (supertype instanceof Class<?> c) {
            raw = c;
        } else {
            return;
        }
        collect(raw, bindings);
    }

    private static Type substitute(Type type, Map<TypeVariable<?>, Type> bindings) {
        return switch (type) {
            case TypeVariable<?> v -> bindings.getOrDefault(v, v);
            case ParameterizedType p -> {
                Type[] arguments = p.getActualTypeArguments();
                Type[] resolved = new Type[arguments.length];
                boolean changed = false;
                for (int i = 0; i < arguments.length; i++) {
                    resolved[i] = substitute(arguments[i], bindings);
                    changed |= resolved[i] != arguments[i];
                }
                yield changed ? new Parameterized((Class<?>) p.getRawType(), resolved, p.getOwnerType()) : p;
            }
            case GenericArrayType a -> {
                Type component = substitute(a.getGenericComponentType(), bindings);
                if (component == a.getGenericComponentType()) yield a;
                yield component instanceof Class<?> c ? c.arrayType() : new GenericArray(component);
            }
            default -> type;
        };
    }

    /** A parameterized type with resolved arguments, equal to the JDK's for the same raw type and arguments. */
    private record Parameterized(Class<?> raw, Type[] arguments, Type owner) implements ParameterizedType {

        @Override
        public Type[] getActualTypeArguments() {
            return arguments.clone();
        }

        @Override
        public Type getRawType() {
            return raw;
        }

        @Override
        public Type getOwnerType() {
            return owner;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ParameterizedType p
                    && raw.equals(p.getRawType())
                    && Objects.equals(owner, p.getOwnerType())
                    && Arrays.equals(arguments, p.getActualTypeArguments());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(arguments) ^ Objects.hashCode(owner) ^ raw.hashCode();
        }

        @Override
        public String toString() {
            return getTypeName();
        }

        @Override
        public String getTypeName() {
            return raw.getTypeName() + Arrays.stream(arguments).map(Type::getTypeName)
                    .collect(Collectors.joining(", ", "<", ">"));
        }
    }

    /** A generic array whose component type was resolved to another parameterized type. */
    private record GenericArray(Type component) implements GenericArrayType {

        @Override
        public Type getGenericComponentType() {
            return component;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof GenericArrayType a && component.equals(a.getGenericComponentType());
        }

        @Override
        public int hashCode() {
            return component.hashCode();
        }

        @Override
        public String toString() {
            return getTypeName();
        }

        @Override
        public String getTypeName() {
            return component.getTypeName() + "[]";
        }
    }
}
