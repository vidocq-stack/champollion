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
 * <p>The CDI lookup is performed reflectively via {@link MethodHandle} so as to
 * <strong>avoid a hard runtime dependency</strong> on {@code jakarta.enterprise.cdi-api}.
 * The CDI API is looked up from the bean class's own class loader, once per class: in a
 * Vidocq application the container lives in the application layer, which Champollion's
 * loader does not see. If that loader has no CDI API, or if {@code CDI.current()} throws
 * {@link IllegalStateException} (no active container), we fall back to no-arg
 * instantiation via the declared constructor.</p>
 */
final class CdiResolver {

    private static final System.Logger LOG = System.getLogger(CdiResolver.class.getName());
    private static final String INJECT = "jakarta.inject.Inject";
    private static final java.lang.annotation.Annotation[] EMPTY_QUALIFIERS = new java.lang.annotation.Annotation[0];

    /** The CDI API seen by a bean class, or {@code null} when its loader has none. */
    private static final ClassValue<Cdi> CDI = new ClassValue<>() {
        @Override
        protected Cdi computeValue(Class<?> beanClass) {
            return Cdi.seenBy(beanClass.getClassLoader());
        }
    };

    /** The handles of {@code CDI.current().select(type).get()} and of the two {@code Instance} checks. */
    private record Cdi(MethodHandle current, MethodHandle select, MethodHandle get,
                       MethodHandle isUnsatisfied, MethodHandle isAmbiguous) {

        static Cdi seenBy(ClassLoader loader) {
            try {
                Class<?> cdiCls = Class.forName("jakarta.enterprise.inject.spi.CDI", false, loader);
                Class<?> instCls = Class.forName("jakarta.enterprise.inject.Instance", false, loader);
                var lookup = MethodHandles.publicLookup();
                return new Cdi(
                        lookup.findStatic(cdiCls, "current", MethodType.methodType(cdiCls)),
                        lookup.findVirtual(cdiCls, "select",
                                MethodType.methodType(instCls, Class.class, java.lang.annotation.Annotation[].class)),
                        lookup.findVirtual(instCls, "get", MethodType.methodType(Object.class)),
                        lookup.findVirtual(instCls, "isUnsatisfied", MethodType.methodType(boolean.class)),
                        lookup.findVirtual(instCls, "isAmbiguous", MethodType.methodType(boolean.class)));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError _) {
                return null; // no CDI API for this loader: no-arg instantiation only
            }
        }
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
        Cdi cdi = isLikelyManagedBean(beanClass) ? CDI.get(beanClass) : null;
        if (cdi != null) {
            T bean = (T) fromContainer(cdi, beanClass);
            if (bean != null) return bean;
        }
        return newInstanceFallback(beanClass);
    }

    /** The CDI bean of {@code beanClass}, or {@code null} when there is no container or no single bean. */
    private static Object fromContainer(Cdi cdi, Class<?> beanClass) {
        Object container;
        try {
            container = cdi.current().invoke();
        } catch (Throwable _) {
            return null; // no active container: no-arg instantiation
        }
        try {
            Object inst = cdi.select().invoke(container, beanClass, EMPTY_QUALIFIERS);
            boolean unsatisfied = (boolean) cdi.isUnsatisfied().invoke(inst);
            boolean ambiguous = (boolean) cdi.isAmbiguous().invoke(inst);
            return unsatisfied || ambiguous ? null : cdi.get().invoke(inst);
        } catch (Throwable e) {
            LOG.log(System.Logger.Level.WARNING, () -> "CDI could not provide " + beanClass.getName()
                    + "; it is instantiated with its no-argument constructor instead", e);
            return null;
        }
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
        return anyInjected(cls.getDeclaredFields())
                || anyInjected(cls.getDeclaredConstructors())
                || anyInjected(cls.getDeclaredMethods());
    }

    /**
     * Whether one of the members carries {@code jakarta.inject.Inject}. The annotation is matched
     * by name on purpose: {@code jakarta.inject} is not a dependency of this module, and the
     * application may load it in another class loader or module layer than this one.
     */
    private static boolean anyInjected(java.lang.reflect.AnnotatedElement[] members) {
        for (var member : members) {
            for (var ann : member.getAnnotations()) {
                String name = ann.annotationType().getName();
                if (INJECT.equals(name)) return true;
            }
        }
        return false;
    }

    private static <T> T newInstanceFallback(Class<T> beanClass) throws ReflectiveOperationException {
        var ctor = beanClass.getDeclaredConstructor();
        // Best effort: an inaccessible constructor fails below with a clear IllegalAccessException.
        ctor.trySetAccessible();
        return ctor.newInstance();
    }
}
