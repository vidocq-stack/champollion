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
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Set;

import io.vidocq.champollion.jsonb.spi.GenericBeanTypes;

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
 *
 * <p>The class is looked up by type, as an injection point without a qualifier looks it up. Two beans escape that
 * lookup and are taken among the container's beans instead: the bean of a class with a bounded type variable, which
 * its raw class does not look up (CDI 4.1 section 5.2.4, BUG-20261117-06), and a bean that has only a qualifier of
 * its own, so not {@code @Default} (BUG-20261117-07). The registry of {@code champollion-jsonb-cdi} serves the same
 * beans.</p>
 */
final class CdiResolver {

    private static final System.Logger LOG = System.getLogger(CdiResolver.class.getName());
    private static final String INJECT = "jakarta.inject.Inject";
    private static final String DEFAULT = "jakarta.enterprise.inject.Default";
    private static final String DEPENDENT = "jakarta.enterprise.context.Dependent";
    private static final String AMBIGUOUS = "jakarta.enterprise.inject.AmbiguousResolutionException";
    private static final java.lang.annotation.Annotation[] EMPTY_QUALIFIERS = new java.lang.annotation.Annotation[0];

    /** The CDI API seen by a bean class, or {@code null} when its loader has none. */
    private static final ClassValue<Cdi> CDI = new ClassValue<>() {
        @Override
        protected Cdi computeValue(Class<?> beanClass) {
            return Cdi.seenBy(beanClass.getClassLoader());
        }
    };

    /**
     * The handles of {@code CDI.current().select(type).get()} and of the two {@code Instance} checks, and those of
     * the lookup among the container's beans.
     */
    private record Cdi(MethodHandle current, MethodHandle select, MethodHandle getHandle, MethodHandle handleGet,
                       MethodHandle handleDestroy, MethodHandle isUnsatisfied, MethodHandle isAmbiguous,
                       Beans beans) {

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
                        lookup.findVirtual(instCls, "isAmbiguous", MethodType.methodType(boolean.class)),
                        Beans.seenBy(loader, cdiCls));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError _) {
                return null; // no CDI API for this loader: no-arg instantiation only
            }
        }
    }

    /**
     * The handles of the lookup among the container's beans ({@code BeanManager.getBeans}, {@code resolve},
     * {@code getReference}), for a bean the lookup by type does not find.
     *
     * @param any the {@code @Any} qualifier, which every bean has
     */
    private record Beans(java.lang.annotation.Annotation any, MethodHandle manager, MethodHandle getBeans,
                         MethodHandle resolve, MethodHandle createContext, MethodHandle getReference,
                         MethodHandle beanClass, MethodHandle types, MethodHandle qualifiers, MethodHandle scope,
                         MethodHandle destroy, MethodHandle release) {

        /** The handles, or {@code null} when the loader's CDI API has no such lookup: the lookup by type alone. */
        static Beans seenBy(ClassLoader loader, Class<?> cdiCls) {
            try {
                return handles(loader, cdiCls);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable _) {
                return null;
            }
        }

        private static Beans handles(ClassLoader loader, Class<?> cdiCls) throws Throwable {
            Class<?> managerCls = Class.forName("jakarta.enterprise.inject.spi.BeanManager", false, loader);
            Class<?> beanCls = Class.forName("jakarta.enterprise.inject.spi.Bean", false, loader);
            Class<?> contextualCls = Class.forName("jakarta.enterprise.context.spi.Contextual", false, loader);
            Class<?> contextCls = Class.forName("jakarta.enterprise.context.spi.CreationalContext", false, loader);
            Class<?> anyCls = Class.forName("jakarta.enterprise.inject.Any$Literal", true, loader);
            var lookup = MethodHandles.publicLookup();
            Object any = lookup.findStaticGetter(anyCls, "INSTANCE", anyCls).invoke();
            return new Beans(
                    (java.lang.annotation.Annotation) any,
                    lookup.findVirtual(cdiCls, "getBeanManager", MethodType.methodType(managerCls)),
                    lookup.findVirtual(managerCls, "getBeans",
                            MethodType.methodType(Set.class, Type.class, java.lang.annotation.Annotation[].class)),
                    lookup.findVirtual(managerCls, "resolve", MethodType.methodType(beanCls, Set.class)),
                    lookup.findVirtual(managerCls, "createCreationalContext",
                            MethodType.methodType(contextCls, contextualCls)),
                    lookup.findVirtual(managerCls, "getReference",
                            MethodType.methodType(Object.class, beanCls, Type.class, contextCls)),
                    lookup.findVirtual(beanCls, "getBeanClass", MethodType.methodType(Class.class)),
                    lookup.findVirtual(beanCls, "getTypes", MethodType.methodType(Set.class)),
                    lookup.findVirtual(beanCls, "getQualifiers", MethodType.methodType(Set.class)),
                    lookup.findVirtual(beanCls, "getScope", MethodType.methodType(Class.class)),
                    lookup.findVirtual(contextualCls, "destroy",
                            MethodType.methodType(void.class, Object.class, contextCls)),
                    lookup.findVirtual(contextCls, "release", MethodType.methodType(void.class)));
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
        return io.vidocq.champollion.jsonb.spi.JsonbInstances.cached(beanClass, () -> {
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
        });
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
            boolean ambiguous = (boolean) cdi.isAmbiguous().invoke(inst);
            if (ambiguous) return null;
            boolean unsatisfied = (boolean) cdi.isUnsatisfied().invoke(inst);
            if (unsatisfied) return cdi.beans() == null ? null : outsideTheLookup(cdi.beans(), container, beanClass, handles);
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

    /**
     * The bean of {@code beanClass} that the lookup by type does not find, or {@code null} when the container has no
     * such bean, or more than one: the bean of a class with a bounded type variable, which is the bean whose class is
     * {@code beanClass} and that has the type {@code beanClass<T>}, or a bean that has only a qualifier of its own.
     * A bean with {@code @Default} comes before one without, as the lookup by type prefers it.
     */
    @SuppressWarnings("unchecked")
    private static Object outsideTheLookup(Beans beans, Object container, Class<?> beanClass, Handles handles)
            throws Throwable {
        Object manager = beans.manager().invoke(container);
        boolean generic = beanClass.getTypeParameters().length > 0;
        Type asked = generic ? Object.class : beanClass;
        Set<Object> withDefault = new HashSet<>();
        Set<Object> withAnotherQualifier = new HashSet<>();
        for (Object bean : (Set<Object>) beans.getBeans().invoke(manager, asked, qualifiers(beans.any()))) {
            // A producer is a bean of the class that declares it too, of another parameterization of that class.
            if (generic
                    && (beans.beanClass().invoke(bean) != beanClass || typeOfClass(beans, bean, beanClass) == null)) {
                continue;
            }
            var itsQualifiers = (Set<java.lang.annotation.Annotation>) beans.qualifiers().invoke(bean);
            if (hasDefault(itsQualifiers)) withDefault.add(bean);
            else withAnotherQualifier.add(bean);
        }
        Set<Object> candidates = withDefault.isEmpty() ? withAnotherQualifier : withDefault;
        if (candidates.isEmpty()) return null;
        Object bean = single(beans, manager, candidates);
        if (bean == null) return null;
        Type lookedUpBy = generic ? typeOfClass(beans, bean, beanClass) : beanClass;
        Object context = beans.createContext().invoke(manager, bean);
        Object instance = beans.getReference().invoke(manager, bean, lookedUpBy, context);
        if (handles != null) handles.add(() -> destroy(beans, bean, instance, context));
        return instance;
    }

    /** The bean the container resolves {@code candidates} to; {@code null} when they are ambiguous. */
    private static Object single(Beans beans, Object manager, Set<Object> candidates) throws Throwable {
        try {
            return beans.resolve().invoke(manager, candidates);
        } catch (RuntimeException e) {
            // As an ambiguous lookup by type: no-arg instantiation.
            if (AMBIGUOUS.equals(e.getClass().getName())) return null;
            throw e;
        }
    }

    private static java.lang.annotation.Annotation[] qualifiers(java.lang.annotation.Annotation qualifier) {
        return new java.lang.annotation.Annotation[] {qualifier};
    }

    @SuppressWarnings("unchecked")
    private static Type typeOfClass(Beans beans, Object bean, Class<?> beanClass) throws Throwable {
        return GenericBeanTypes.ofClass((Set<Type>) beans.types().invoke(bean), beanClass);
    }

    /** The qualifiers are matched by name: the CDI API is the application's, not this module's. */
    private static boolean hasDefault(Set<java.lang.annotation.Annotation> qualifiers) {
        for (var qualifier : qualifiers) {
            if (DEFAULT.equals(qualifier.annotationType().getName())) return true;
        }
        return false;
    }

    /**
     * A {@code @Dependent} instance is destroyed with its dependents ({@code Bean.destroy}); the instance of a bean
     * of a normal scope is its context's to destroy, and only what the reference holds is released.
     */
    private static void destroy(Beans beans, Object bean, Object instance, Object context) {
        try {
            if (DEPENDENT.equals(((Class<?>) beans.scope().invoke(bean)).getName())) {
                beans.destroy().invoke(bean, instance, context);
            } else {
                beans.release().invoke(context);
            }
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
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
