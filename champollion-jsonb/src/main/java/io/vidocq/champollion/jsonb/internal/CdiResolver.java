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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Instance resolver for JSON-B beans (Adapter / Serializer / Deserializer)
 * according to Jakarta JSON-B 3.0 §5:
 *
 * <blockquote>If the CDI container is available, the JSON Binding implementation
 * MUST resolve adapters, serializers and deserializers as CDI beans before
 * falling back to no-arg instantiation.</blockquote>
 *
 * <p>The CDI lookup is performed reflectively via {@link MethodHandle} (resolved
 * once at class load time) so as to <strong>avoid a hard runtime dependency</strong>
 * on {@code jakarta.enterprise.cdi-api}. If CDI is not on the classpath, or if
 * {@code CDI.current()} throws {@link IllegalStateException} (no active container),
 * we fall back to no-arg instantiation via the declared constructor.</p>
 */
final class CdiResolver {

    private static final MethodHandle CDI_CURRENT;
    private static final MethodHandle CDI_SELECT;
    private static final MethodHandle INSTANCE_GET;
    private static final MethodHandle INSTANCE_IS_UNSATISFIED;
    private static final MethodHandle INSTANCE_IS_AMBIGUOUS;
    private static final boolean CDI_AVAILABLE;
    private static final java.lang.annotation.Annotation[] EMPTY_QUALIFIERS = new java.lang.annotation.Annotation[0];

    static {
        MethodHandle current = null, select = null, get = null,
                isUnsatisfied = null, isAmbiguous = null;
        boolean available = false;
        try {
            Class<?> cdiCls = Class.forName("jakarta.enterprise.inject.spi.CDI");
            Class<?> instCls = Class.forName("jakarta.enterprise.inject.Instance");
            var lookup = MethodHandles.publicLookup();
            current = lookup.findStatic(cdiCls, "current", MethodType.methodType(cdiCls));
            select = lookup.findVirtual(cdiCls, "select",
                    MethodType.methodType(instCls, Class.class, java.lang.annotation.Annotation[].class));
            get = lookup.findVirtual(instCls, "get", MethodType.methodType(Object.class));
            isUnsatisfied = lookup.findVirtual(instCls, "isUnsatisfied", MethodType.methodType(boolean.class));
            isAmbiguous = lookup.findVirtual(instCls, "isAmbiguous", MethodType.methodType(boolean.class));
            available = true;
        } catch (Throwable ignore) {
            // CDI absent from the classpath — fallback newInstance will always be used.
        }
        CDI_CURRENT = current;
        CDI_SELECT = select;
        INSTANCE_GET = get;
        INSTANCE_IS_UNSATISFIED = isUnsatisfied;
        INSTANCE_IS_AMBIGUOUS = isAmbiguous;
        CDI_AVAILABLE = available;
    }

    private CdiResolver() {}

    /**
     * Resolves an instance of {@code beanClass} via CDI if available, otherwise
     * via the no-arg constructor.
     */
    @SuppressWarnings("unchecked")
    static <T> T resolve(Class<T> beanClass) throws ReflectiveOperationException {
        // Static pre-check: only attempt CDI if the class is explicitly a managed
        // bean (annotated with @*Scoped, @Singleton, or bearing @Inject).
        // Without this filter, a CDI container with bean-discovery-mode=all can
        // produce "synthetic" instances for ordinary classes (state-free TCK
        // Deserializer/Adapter) and alter parser state in critical paths.
        if (CDI_AVAILABLE && isLikelyManagedBean(beanClass)) {
            try {
                Object cdi = CDI_CURRENT.invoke();
                Object inst = CDI_SELECT.invoke(cdi, beanClass, EMPTY_QUALIFIERS);
                boolean unsatisfied = (boolean) INSTANCE_IS_UNSATISFIED.invoke(inst);
                boolean ambiguous = (boolean) INSTANCE_IS_AMBIGUOUS.invoke(inst);
                if (!unsatisfied && !ambiguous) {
                    Object bean = INSTANCE_GET.invoke(inst);
                    if (bean != null) return (T) bean;
                }
            } catch (Throwable t) {
                // Container not started → fallback newInstance.
            }
        }
        return newInstanceFallback(beanClass);
    }

    /**
     * Conservative heuristic: the class is a managed bean if it carries a
     * {@code jakarta.enterprise.context.*Scoped} annotation,
     * {@code jakarta.inject.Singleton}, or if one of its members carries
     * {@code jakarta.inject.Inject} (field, constructor, or setter).
     */
    private static boolean isLikelyManagedBean(Class<?> cls) {
        for (var ann : cls.getAnnotations()) {
            String n = ann.annotationType().getName();
            if (n.startsWith("jakarta.enterprise.context.") && n.endsWith("Scoped")) return true;
            if ("jakarta.inject.Singleton".equals(n)) return true;
        }
        for (var f : cls.getDeclaredFields()) {
            for (var ann : f.getAnnotations()) {
                if ("jakarta.inject.Inject".equals(ann.annotationType().getName())) return true;
            }
        }
        for (var ctor : cls.getDeclaredConstructors()) {
            for (var ann : ctor.getAnnotations()) {
                if ("jakarta.inject.Inject".equals(ann.annotationType().getName())) return true;
            }
        }
        for (var m : cls.getDeclaredMethods()) {
            for (var ann : m.getAnnotations()) {
                if ("jakarta.inject.Inject".equals(ann.annotationType().getName())) return true;
            }
        }
        return false;
    }

    private static <T> T newInstanceFallback(Class<T> beanClass) throws ReflectiveOperationException {
        var ctor = beanClass.getDeclaredConstructor();
        try { ctor.setAccessible(true); } catch (Exception ignore) {}
        return ctor.newInstance();
    }
}
