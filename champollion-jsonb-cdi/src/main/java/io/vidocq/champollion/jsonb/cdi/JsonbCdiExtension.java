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
package io.vidocq.champollion.jsonb.cdi;

import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.Validation;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.json.bind.annotation.JsonbCreator;
import jakarta.json.bind.annotation.JsonbTypeAdapter;
import jakarta.json.bind.annotation.JsonbTypeDeserializer;
import jakarta.json.bind.annotation.JsonbTypeSerializer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates, at build, the adapters, serializers and deserializers that {@code @JsonbTypeAdapter},
 * {@code @JsonbTypeSerializer} and {@code @JsonbTypeDeserializer} name, as the generated bindings and the runtime will
 * obtain them from the container ({@link JsonbCdiRegistry}): an error when one is ambiguous, or injects without being a
 * bean (its injections would stay null).
 *
 * <p>It registers no synthetic bean: under Vauban it runs at build and again at boot, and the registry, a plain bean of
 * this archive, depends on nothing it saw. A run that sees no JSON-B annotation (the boot, where the classes of the
 * application are no beans) validates nothing.</p>
 */
public class JsonbCdiExtension implements BuildCompatibleExtension {

    private static final Set<String> ANNOTATIONS = Set.of(JsonbTypeAdapter.class.getName(),
            JsonbTypeSerializer.class.getName(), JsonbTypeDeserializer.class.getName());
    private static final String INJECT = "jakarta.inject.Inject";

    /** A class a JSON-B annotation names, and where. */
    private record Referenced(ClassInfo type, String usedBy) {}

    private final Map<String, Referenced> referenced = new LinkedHashMap<>();
    private final Map<String, Integer> beansByType = new HashMap<>();
    /** The classes with an {@code @Inject} member, beans or not. */
    private final Set<String> injecting = new java.util.HashSet<>();

    @Enhancement(types = Object.class, withSubtypes = true, withAnnotations = {JsonbTypeAdapter.class,
            JsonbTypeSerializer.class, JsonbTypeDeserializer.class, JsonbCreator.class})
    public void collect(ClassInfo type) {
        String where = type.name();
        note(type.annotations(), where);
        type.fields().forEach(f -> note(f.annotations(), where + "." + f.name()));
        List<MethodInfo> executables = new ArrayList<>(type.methods());
        executables.addAll(type.constructors());
        for (MethodInfo m : executables) {
            note(m.annotations(), where + "." + m.name() + "()");
            m.parameters().forEach(p -> note(p.annotations(), where + "." + m.name() + "(" + p.name() + ")"));
        }
    }

    private void note(Collection<AnnotationInfo> annotations, String where) {
        for (AnnotationInfo a : annotations) {
            if (ANNOTATIONS.contains(a.name()) && a.hasValue()) {
                Type value = a.value().asType();
                if (value.isClass()) {
                    ClassInfo named = value.asClass().declaration();
                    referenced.putIfAbsent(named.name(), new Referenced(named, where));
                }
            }
        }
    }

    /**
     * Records the classes that inject: the class a JSON-B annotation names may come with no members at build (a class
     * being compiled, seen through an annotation value), so its own members cannot tell.
     */
    @Enhancement(types = Object.class, withSubtypes = true, withAnnotations = jakarta.inject.Inject.class)
    public void injecting(ClassInfo type) {
        if (injects(type)) injecting.add(type.name());
    }

    /**
     * Counts a bean for each class it can be looked up by, as the registry looks it up: by each of its types that is a
     * class, and, for the bean of a class with type variables, by that class. The only type of its class such a bean
     * has is parameterized, {@code type<T>} (CDI 4.1 section 2.2.1), and its raw class does not look it up (section
     * 5.2.4): the registry takes it by its bean class (BUG-20261117-02, BUG-20261117-05).
     */
    @Registration(types = Object.class)
    public void bean(BeanInfo bean) {
        for (Type type : bean.types()) {
            if (type.isClass()) beansByType.merge(type.asClass().declaration().name(), 1, Integer::sum);
        }
        if (bean.isClassBean() && hasTypeOfItsGenericClass(bean)) {
            beansByType.merge(bean.declaringClass().name(), 1, Integer::sum);
        }
    }

    /**
     * Whether a class bean has the type of its own class with its own type variables, {@code type<T, ...>}: the type
     * the registry looks it up by. A class without type variables, or a bean {@code @Typed} away from its class,
     * has none.
     */
    private static boolean hasTypeOfItsGenericClass(BeanInfo bean) {
        ClassInfo declaring = bean.declaringClass();
        List<String> own = declaring.typeParameters().stream().map(v -> v.name()).toList();
        if (own.isEmpty()) return false;
        for (Type type : bean.types()) {
            if (type.isParameterizedType()
                    && type.asParameterizedType().declaration().name().equals(declaring.name())
                    && type.asParameterizedType().typeArguments().stream()
                            .map(a -> a.isTypeVariable() ? a.asTypeVariable().name() : null).toList().equals(own)) {
                return true;
            }
        }
        return false;
    }

    @Validation
    public void validate(Messages messages) {
        for (var e : referenced.entrySet()) {
            String name = e.getKey();
            int beans = beansByType.getOrDefault(name, 0);
            if (beans > 1) {
                messages.error("Champollion: " + name + ", named by a JSON-B annotation on " + e.getValue().usedBy()
                        + ", is ambiguous: " + beans + " beans have its type; JSON-B needs one instance of it");
            } else if (beans == 0 && (injecting.contains(name) || injects(e.getValue().type()))) {
                messages.error("Champollion: " + name + ", named by a JSON-B annotation on " + e.getValue().usedBy()
                        + ", has @Inject members but is no bean: its injections would stay null; give it a"
                        + " bean-defining annotation (@Dependent, @ApplicationScoped...)");
            }
        }
    }

    private static boolean injects(ClassInfo type) {
        return type.fields().stream().anyMatch(f -> f.hasAnnotation(a -> INJECT.equals(a.name())))
                || type.constructors().stream().anyMatch(c -> c.hasAnnotation(a -> INJECT.equals(a.name())))
                || type.methods().stream().anyMatch(m -> m.hasAnnotation(a -> INJECT.equals(a.name())));
    }
}
