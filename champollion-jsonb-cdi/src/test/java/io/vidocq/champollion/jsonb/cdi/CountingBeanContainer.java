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

import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanContainer;
import jakarta.enterprise.inject.spi.InterceptionType;
import jakarta.enterprise.inject.spi.Interceptor;
import jakarta.enterprise.inject.spi.ObserverMethod;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** A running container's {@link BeanContainer}, that counts the walks of its beans by type ({@link #getBeans}). */
final class CountingBeanContainer implements BeanContainer {

    final AtomicInteger beanWalks = new AtomicInteger();
    private final BeanContainer delegate;

    CountingBeanContainer(BeanContainer delegate) {
        this.delegate = delegate;
    }

    @Override
    public Set<Bean<?>> getBeans(Type beanType, Annotation... qualifiers) {
        beanWalks.incrementAndGet();
        return delegate.getBeans(beanType, qualifiers);
    }

    @Override
    public Object getReference(Bean<?> bean, Type beanType, CreationalContext<?> ctx) {
        return delegate.getReference(bean, beanType, ctx);
    }

    @Override
    public <T> CreationalContext<T> createCreationalContext(Contextual<T> contextual) {
        return delegate.createCreationalContext(contextual);
    }

    @Override
    public Set<Bean<?>> getBeans(String name) {
        return delegate.getBeans(name);
    }

    @Override
    public <X> Bean<? extends X> resolve(Set<Bean<? extends X>> beans) {
        return delegate.resolve(beans);
    }

    @Override
    public <T> Set<ObserverMethod<? super T>> resolveObserverMethods(T event, Annotation... qualifiers) {
        return delegate.resolveObserverMethods(event, qualifiers);
    }

    @Override
    public List<Interceptor<?>> resolveInterceptors(InterceptionType type, Annotation... interceptorBindings) {
        return delegate.resolveInterceptors(type, interceptorBindings);
    }

    @Override
    public boolean isScope(Class<? extends Annotation> annotationType) {
        return delegate.isScope(annotationType);
    }

    @Override
    public boolean isNormalScope(Class<? extends Annotation> annotationType) {
        return delegate.isNormalScope(annotationType);
    }

    @Override
    public boolean isQualifier(Class<? extends Annotation> annotationType) {
        return delegate.isQualifier(annotationType);
    }

    @Override
    public boolean isStereotype(Class<? extends Annotation> annotationType) {
        return delegate.isStereotype(annotationType);
    }

    @Override
    public boolean isInterceptorBinding(Class<? extends Annotation> annotationType) {
        return delegate.isInterceptorBinding(annotationType);
    }

    @Override
    public Context getContext(Class<? extends Annotation> scopeType) {
        return delegate.getContext(scopeType);
    }

    @Override
    public Collection<Context> getContexts(Class<? extends Annotation> scopeType) {
        return delegate.getContexts(scopeType);
    }

    @Override
    public Event<Object> getEvent() {
        return delegate.getEvent();
    }

    @Override
    public Instance<Object> createInstance() {
        return delegate.createInstance();
    }

    @Override
    public boolean isMatchingBean(Set<Type> beanTypes, Set<Annotation> beanQualifiers, Type requiredType,
            Set<Annotation> requiredQualifiers) {
        return delegate.isMatchingBean(beanTypes, beanQualifiers, requiredType, requiredQualifiers);
    }

    @Override
    public boolean isMatchingEvent(Type specifiedType, Set<Annotation> specifiedQualifiers, Type observedEventType,
            Set<Annotation> observedEventQualifiers) {
        return delegate.isMatchingEvent(specifiedType, specifiedQualifiers, observedEventType,
                observedEventQualifiers);
    }
}
