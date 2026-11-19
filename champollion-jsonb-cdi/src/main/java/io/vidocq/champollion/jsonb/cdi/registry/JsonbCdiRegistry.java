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

import io.vidocq.champollion.jsonb.spi.GenericBeanTypes;
import io.vidocq.champollion.jsonb.spi.JsonbInstances;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Shutdown;
import jakarta.enterprise.event.Startup;
import jakarta.enterprise.inject.AmbiguousResolutionException;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanContainer;
import jakarta.inject.Inject;

import java.lang.reflect.Type;
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
 * <p>A class is looked up by type, as an injection point without a qualifier looks it up. Two beans escape that
 * lookup, and are served all the same, obtained once and destroyed the same way: the bean of a class with a bounded
 * type variable, which its raw class does not look up (CDI 4.1 section 5.2.4), and a bean that has only a qualifier
 * of its own, so not {@code @Default} (BUG-20261117-07). A bean with {@code @Default} comes first: one with another
 * qualifier is served only when no bean of the class has {@code @Default}.</p>
 *
 * <p>A bean of any CDI container, which creates it and calls its observers. Its constructor and observers are public
 * for Vauban's adapter, {@code io.vidocq.champollion.jsonb.cdi.vauban}, the only module this package is exported to:
 * it creates the registry and calls them without reflection and without an {@code opens}. No other module reaches
 * them.</p>
 */
@ApplicationScoped
public class JsonbCdiRegistry implements JsonbInstances.Source {

    private final Map<Class<?>, Instance.Handle<?>> handles = new ConcurrentHashMap<>();
    /** The classes that the lookup by type does not find and that have no bean of their own. */
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
        return candidates.isResolvable() || (candidates.isUnsatisfied() && outsideTheLookup(type) != null);
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
            handle = candidates.isUnsatisfied() ? outsideTheLookup(type)
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
     * The handle of the bean of {@code type} that the lookup by type does not find; {@code null} when this container
     * has no such bean, or more than one. Two beans are concerned:
     * <ul>
     * <li>the bean of a class with a bounded type variable: its only type of that class is parameterized, and its raw
     * class does not look it up (CDI 4.1 section 5.2.4, BUG-20261117-02). It is the bean whose class is {@code type}
     * and that has the type {@code type<T>};</li>
     * <li>a bean that has only a qualifier of its own, so not {@code @Default} (BUG-20261117-07).</li>
     * </ul>
     * A bean with {@code @Default} comes before one without, as the lookup by type prefers it. The container is asked
     * once per class: the answer is kept, a handle or none.
     */
    private Instance.Handle<?> outsideTheLookup(Class<?> type) {
        if (withoutBean.contains(type)) return null;
        boolean generic = type.getTypeParameters().length > 0;
        Set<Bean<?>> withDefault = new HashSet<>();
        Set<Bean<?>> withAnotherQualifier = new HashSet<>();
        for (Bean<?> bean : container.getBeans(generic ? Object.class : type, Any.Literal.INSTANCE)) {
            // A producer is a bean of the class that declares it too, of another parameterization of that class.
            if (generic && (bean.getBeanClass() != type || GenericBeanTypes.ofClass(bean.getTypes(), type) == null)) {
                continue;
            }
            if (bean.getQualifiers().contains(Default.Literal.INSTANCE)) withDefault.add(bean);
            else withAnotherQualifier.add(bean);
        }
        Bean<?> bean = single(withDefault.isEmpty() ? withAnotherQualifier : withDefault);
        if (bean == null) {
            withoutBean.add(type);
            return null;
        }
        Type lookedUpBy = generic ? GenericBeanTypes.ofClass(bean.getTypes(), type) : type;
        return handles.computeIfAbsent(type, t -> new OfBean(container, bean, lookedUpBy));
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
     * The handle of a bean this registry took among the container's beans: the instance is obtained at the first
     * {@link #get()}, once, and {@link #destroy()} destroys a {@code @Dependent} one with its dependents
     * ({@link Bean#destroy}).
     *
     * <p>The registry makes this handle itself because the lookup it holds cannot give one: it asks for
     * {@code @Default}, which a bean with another qualifier does not have, and {@link Instance#select} refuses a
     * required type with a type variable, {@code type<T>}, with an {@code IllegalArgumentException} (CDI 4.1 section
     * 5.6.1).</p>
     */
    private static final class OfBean implements Instance.Handle<Object> {

        /** An instance, and the context it was created in. */
        private record Created(Object instance, CreationalContext<Object> context) {}

        private final BeanContainer container;
        private final Bean<Object> bean;
        private final Type type;
        private final AtomicReference<Created> created = new AtomicReference<>();

        @SuppressWarnings("unchecked")
        OfBean(BeanContainer container, Bean<?> bean, Type type) {
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
         * The reference of a {@code @Dependent} bean is the instance itself: {@link Bean#destroy} runs its
         * {@code @PreDestroy} callbacks and destroys its dependents, where {@link CreationalContext#release()} alone
         * is only specified to destroy the dependents. The reference of a bean of a normal scope is a client proxy,
         * and the instance behind it is its context's to destroy: only what this reference holds is released.
         */
        private void destroy(Created created) {
            if (bean.getScope() == Dependent.class) bean.destroy(created.instance(), created.context());
            else created.context().release();
        }

        @Override
        public void close() {
            destroy();
        }
    }
}
