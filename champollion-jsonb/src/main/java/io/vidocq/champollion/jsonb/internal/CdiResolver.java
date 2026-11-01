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
    private record Cdi(MethodHandle current, MethodHandle select, MethodHandle getHandle, MethodHandle handleGet,
                       MethodHandle handleDestroy, MethodHandle isUnsatisfied, MethodHandle isAmbiguous) {

        static Cdi seenBy(ClassLoader loader) {
            try {
                Class<?> cdiCls = Class.forName("jakarta.enterprise.inject.spi.CDI", false, loader);
                Class<?> instCls = Class.forName("jakarta.enterprise.inject.Instance", false, loader);
                Class<?> handleCls = Class.forName("jakarta.enterprise.inject.Instance$Handle", false, loader);
                var lookup = MethodHandles.publicLookup();
                return new Cdi(
                        lookup.findStatic(cdiCls, "current", MethodType.methodType(cdiCls)),
                        lookup.findVirtual(cdiCls, "select",
                                MethodType.methodType(instCls, Class.class, java.lang.annotation.Annotation[].class)),
                        lookup.findVirtual(instCls, "getHandle", MethodType.methodType(handleCls)),
                        lookup.findVirtual(handleCls, "get", MethodType.methodType(Object.class)),
                        lookup.findVirtual(handleCls, "destroy", MethodType.methodType(void.class)),
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
        return resolve(beanClass, null);
    }

    /**
     * The adapter, serializer or deserializer {@code beanClass} for a {@code Jsonb}: on each call, the instance of the
     * container that ran Champollion's build compatible extension for the application, when one did (one per
     * container, destroyed when it stops); otherwise the instance {@link #resolve} gives, resolved once, its CDI
     * handle recorded in {@code handles} for the {@code Jsonb} to destroy when it closes.
     */
    static <T> java.util.function.Supplier<T> supplier(Class<T> beanClass, Handles handles)
            throws ReflectiveOperationException {
        var source = io.vidocq.champollion.jsonb.spi.JsonbInstances.sourceFor(beanClass);
        T served = source == null ? null : source.get(beanClass);
        // Resolved now, as before, so that a class that cannot be created fails when the mapping is built.
        T own = served == null ? resolve(beanClass, handles) : null;
        var fallback = new java.util.concurrent.atomic.AtomicReference<>(own);
        return () -> {
            var current = io.vidocq.champollion.jsonb.spi.JsonbInstances.sourceFor(beanClass);
            T bean = current == null ? null : current.get(beanClass);
            if (bean != null) return bean;
            T kept = fallback.get();
            if (kept == null) {
                try {
                    fallback.compareAndSet(null, resolve(beanClass, handles));
                } catch (ReflectiveOperationException e) {
                    throw new jakarta.json.bind.JsonbException("Cannot instantiate " + beanClass.getName(), e);
                }
                kept = fallback.get();
            }
            return kept;
        };
    }

    /** The CDI handles of the instances a {@code Jsonb} obtained through {@code CDI.current()}, destroyed on close. */
    static final class Handles {

        private final java.util.List<Runnable> destroyers = new java.util.concurrent.CopyOnWriteArrayList<>();

        void add(Runnable destroy) {
            destroyers.add(destroy);
        }

        /** Destroys them, a {@code @Dependent} instance with its dependents. */
        void destroy() {
            for (Runnable d : destroyers) {
                try {
                    d.run();
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "A CDI instance of JSON-B could not be destroyed", e);
                }
            }
            destroyers.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T resolve(Class<T> beanClass, Handles handles) throws ReflectiveOperationException {
        // A container that ran Champollion's build compatible extension serves its own instances (one per container,
        // destroyed when it stops).
        var source = io.vidocq.champollion.jsonb.spi.JsonbInstances.sourceFor(beanClass);
        T served = source == null ? null : source.get(beanClass);
        if (served != null) return served;
        // Static pre-check: only attempt CDI if the class is explicitly a managed
        // bean (annotated with @*Scoped, @Singleton, or bearing @Inject).
        // Without this filter, a CDI container with bean-discovery-mode=all can
        // produce "synthetic" instances for ordinary classes (state-free TCK
        // Deserializer/Adapter) and alter parser state in critical paths.
        Cdi cdi = isLikelyManagedBean(beanClass) ? CDI.get(beanClass) : null;
        if (cdi != null) {
            T bean = (T) fromContainer(cdi, beanClass, handles);
            if (bean != null) return bean;
        }
        return newInstanceFallback(beanClass);
    }

    /**
     * The CDI bean of {@code beanClass} when it looks like one and a container provides it, as {@link #resolve} finds
     * it; {@code null} otherwise. For a generated binding, which creates the instance itself when this gives none.
     */
    @SuppressWarnings("unchecked")
    static <T> T bean(Class<T> beanClass) {
        Cdi cdi = isLikelyManagedBean(beanClass) ? CDI.get(beanClass) : null;
        return cdi == null ? null : (T) fromContainer(cdi, beanClass, null);
    }

    /** The CDI bean of {@code beanClass}, or {@code null} when there is no container or no single bean. */
    private static Object fromContainer(Cdi cdi, Class<?> beanClass, Handles handles) {
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
            if (unsatisfied || ambiguous) return null;
            Object handle = cdi.getHandle().invoke(inst);
            Object bean = cdi.handleGet().invoke(handle);
            if (handles != null) handles.add(() -> destroy(cdi, handle));
            return bean;
        } catch (Throwable e) {
            LOG.log(System.Logger.Level.WARNING, () -> "CDI could not provide " + beanClass.getName()
                    + "; it is instantiated with its no-argument constructor instead", e);
            return null;
        }
    }

    private static void destroy(Cdi cdi, Object handle) {
        try {
            cdi.handleDestroy().invoke(handle);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
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
