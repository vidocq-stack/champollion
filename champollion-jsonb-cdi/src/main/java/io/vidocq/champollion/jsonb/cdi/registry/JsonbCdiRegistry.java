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
package io.vidocq.champollion.jsonb.cdi.registry;

import io.vidocq.champollion.jsonb.spi.JsonbInstances;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Shutdown;
import jakarta.enterprise.event.Startup;
import jakarta.enterprise.inject.AmbiguousResolutionException;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanContainer;
import jakarta.inject.Inject;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The adapters, serializers and deserializers of one container, for Champollion's generated bindings and runtime:
 * installed when the container starts ({@link JsonbInstances#install}), for the application's class loader; each
 * instance obtained once from the container, through its own {@link Instance}, and destroyed with its dependents when
 * the container stops — a {@code @Dependent} one included.
 *
 * <p>A class is looked up by type. A class with a bounded type variable is a bean that its raw class does not look up
 * (CDI 4.1 section 5.2.4): it is served by the bean of that class, obtained once and destroyed the same way.</p>
 *
 * <p>A bean of any CDI container, which creates it and calls its observers. Its constructor and observers are public
 * for Vauban's adapter, {@code io.vidocq.champollion.jsonb.cdi.vauban}, the only module this package is exported to:
 * it creates the registry and calls them without reflection and without an {@code opens}. No other module reaches
 * them.</p>
 */
@ApplicationScoped
public class JsonbCdiRegistry implements JsonbInstances.Source {

    private final Map<Class<?>, Instance.Handle<?>> handles = new ConcurrentHashMap<>();
    /** The classes with type variables that the lookup by type does not find and that have no bean of their own. */
    private final Set<Class<?>> withoutBean = ConcurrentHashMap.newKeySet();
    /** Set when the container stops: nothing is created or kept after it. */
    private volatile boolean closed;
    private Instance<Object> beans;
    private BeanContainer container;
    private ClassLoader application;

    /** For the container's proxy. */
    protected JsonbCdiRegistry() {
    }

    /**
     * The registry of the container that injects {@code beans} and {@code container}.
     *
     * @param beans     the lookup of the container's beans
     * @param container the container, for the beans the lookup by type does not find
     */
    @Inject
    public JsonbCdiRegistry(Instance<Object> beans, BeanContainer container) {
        this.beans = beans;
        this.container = container;
    }

    /**
     * Serves the generated bindings and the runtime of the application starting.
     *
     * @param event the container's start
     */
    public void install(@Observes Startup event) {
        application = Thread.currentThread().getContextClassLoader();
        if (application == null) application = JsonbCdiRegistry.class.getClassLoader();
        JsonbInstances.install(application, this);
    }

    /**
     * Stops serving, and destroys the instances obtained; from then on, it creates and keeps nothing.
     *
     * @param event the container's stop
     */
    public void uninstall(@Observes Shutdown event) {
        closed = true;
        if (application != null) JsonbInstances.uninstall(application, this);
        destroyAll();
        withoutBean.clear();
    }

    private void destroyAll() {
        for (Class<?> type : handles.keySet()) {
            Instance.Handle<?> handle = handles.remove(type);
            if (handle != null) handle.destroy();
        }
    }

    @Override
    public boolean serves(Class<?> type) {
        if (closed) return false;
        if (handles.containsKey(type)) return true;
        Instance<?> candidates = beans.select(type);
        return candidates.isResolvable() || (candidates.isUnsatisfied() && byBeanClass(type) != null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> type) {
        if (closed) return null;
        Instance.Handle<?> handle = handles.get(type);
        if (handle == null) {
            Instance<T> candidates = beans.select(type);
            // Ambiguous: the build reported it, and the class is created with new.
            if (candidates.isAmbiguous()) return null;
            handle = candidates.isUnsatisfied() ? byBeanClass(type)
                    : handles.computeIfAbsent(type, t -> candidates.getHandle());
            // Not a bean here: the class is created with new.
            if (handle == null) return null;
        }
        T instance = (T) handle.get();
        // Stopped while this instance was obtained: it is destroyed with what the stop left, and not served.
        if (closed) {
            destroyAll();
            return null;
        }
        return instance;
    }

    /**
     * The handle of the bean whose class is {@code type}, for a class the lookup by type finds no bean of; {@code null}
     * when this container has no such bean, or more than one. A class with a bounded type variable is a bean whose
     * only type of that class is parameterized, and its raw class does not look it up (CDI 4.1 section 5.2.4,
     * BUG-20261117-02). The beans are those the lookup by type asks for: the ones with {@code @Default}. They are
     * walked once per class, and only for a class with type variables: the answer is kept, a handle or none.
     */
    private Instance.Handle<?> byBeanClass(Class<?> type) {
        if (type.getTypeParameters().length == 0 || withoutBean.contains(type)) return null;
        Set<Bean<?>> ofThatClass = new HashSet<>();
        for (Bean<?> bean : container.getBeans(Object.class)) {
            // A producer is a bean of the class that declares it too, of another parameterization of that class.
            if (bean.getBeanClass() == type && typeOfClass(bean, type) != null) ofThatClass.add(bean);
        }
        Bean<?> bean = single(ofThatClass);
        if (bean == null) {
            withoutBean.add(type);
            return null;
        }
        return handles.computeIfAbsent(type, t -> new ByBeanClass(container, bean, typeOfClass(bean, type)));
    }

    /** The bean the container resolves {@code candidates} to; {@code null} when there is none, or no single one. */
    private Bean<?> single(Set<Bean<?>> candidates) {
        if (candidates.isEmpty()) return null;
        try {
            return container.resolve(candidates);
        } catch (AmbiguousResolutionException _) {
            // As an ambiguous lookup by type: the class is created with new.
            return null;
        }
    }

    /**
     * The bean type of {@code bean} that is {@code type} with its own type variables, {@code type<T, ...>}, or
     * {@code null} when it has none. That is the type of the class the bean of a generic class has (CDI 4.1 section
     * 2.2.1); a producer the class declares has another parameterization of it, {@code type<Integer>}, and is not
     * the bean of the class.
     */
    private static Type typeOfClass(Bean<?> bean, Class<?> type) {
        for (Type beanType : bean.getTypes()) {
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

    /**
     * The handle of a bean this registry asked the container for by its class: the instance is created at the first
     * {@link #get()}, once, and {@link #destroy()} destroys it with its dependents ({@link Bean#destroy}).
     *
     * <p>The registry makes this handle itself because the container cannot give one: the type that looks the bean up
     * has a type variable, {@code type<T>}, and {@link Instance#select} refuses a required type with a type variable
     * with an {@code IllegalArgumentException} (CDI 4.1 section 5.6.1).</p>
     */
    private static final class ByBeanClass implements Instance.Handle<Object> {

        /** An instance, and the context it was created in. */
        private record Created(Object instance, CreationalContext<Object> context) {}

        private final BeanContainer container;
        private final Bean<Object> bean;
        private final Type type;
        private final AtomicReference<Created> created = new AtomicReference<>();

        @SuppressWarnings("unchecked")
        ByBeanClass(BeanContainer container, Bean<?> bean, Type type) {
            this.container = container;
            this.bean = (Bean<Object>) bean;
            this.type = type;
        }

        @Override
        public Object get() {
            Created current = created.get();
            if (current != null) return current.instance();
            CreationalContext<Object> context = container.createCreationalContext(bean);
            Created mine = new Created(container.getReference(bean, type, context), context);
            Created first = created.compareAndExchange(null, mine);
            if (first == null) return mine.instance();
            // Created twice at once: the first one stays, and this one goes.
            destroy(mine);
            return first.instance();
        }

        @Override
        public Bean<Object> getBean() {
            return bean;
        }

        @Override
        public void destroy() {
            Created current = created.getAndSet(null);
            if (current != null) destroy(current);
        }

        /**
         * The bean is {@code @Dependent} (a class with type variables can be no other, CDI 4.1 section 3.1), so the
         * reference is the instance itself: {@link Bean#destroy} runs its {@code @PreDestroy} callbacks and destroys its
         * dependents. {@link CreationalContext#release()} alone is only specified to destroy the dependents.
         */
        private void destroy(Created created) {
            bean.destroy(created.instance(), created.context());
        }

        @Override
        public void close() {
            destroy();
        }
    }
}
