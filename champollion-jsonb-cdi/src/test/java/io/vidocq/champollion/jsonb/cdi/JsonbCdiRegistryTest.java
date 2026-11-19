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

import io.vidocq.champollion.jsonb.cdi.registry.JsonbCdiRegistry;
import io.vidocq.champollion.jsonb.spi.JsonbInstances;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two containers, one after the other, in one JVM: each serves its own instances to the generated bindings and the
 * runtime, and destroys them when it stops, a {@code @Dependent} one included.
 */
class JsonbCdiRegistryTest {

    @BeforeEach
    void reset() {
        DependentAdapter.DESTROYED.set(0);
        BoundedAdapter.CREATED.set(0);
        BoundedAdapter.DESTROYED.set(0);
        ReentrantBounded.CREATED.set(0);
        ReentrantBounded.DESTROYED.set(0);
        MarkedBounded.DESTROYED.set(0);
        MarkedShared.DESTROYED.set(0);
    }

    private static WeldContainer start(String id) {
        return new Weld(id).initialize();
    }

    @Test
    void eachContainerServesAndDestroysItsOwnInstances() {
        Supplier<DependentAdapter> generated = JsonbInstances.of(DependentAdapter.class, DependentAdapter::new);
        DependentAdapter first;
        try (var _ = start("a")) {
            first = generated.get();
            assertEquals("<x>", first.adaptToJson("x"), "injected by the container");
            assertSame(first, generated.get(), "one instance per container");
        }
        assertEquals(1, DependentAdapter.DESTROYED.get(), "destroyed with its container");
        assertNull(JsonbInstances.sourceFor(DependentAdapter.class), "the stopped container serves no more");
        try (var _ = start("b")) {
            DependentAdapter second = generated.get();
            assertNotSame(first, second, "the second container's own instance");
            assertEquals("<y>", second.adaptToJson("y"));
        }
        assertEquals(2, DependentAdapter.DESTROYED.get());
    }

    @Test
    void theRuntimeUsesTheInstanceOfTheRunningContainer() throws Exception {
        Tagged tagged = new Tagged();
        tagged.name = "n";
        // One Jsonb across both containers, as a JVM-wide one is.
        try (Jsonb jsonb = JsonbBuilder.create()) {
            try (var _ = start("ra")) {
                assertEquals("{\"name\":\"<n>\"}", jsonb.toJson(tagged));
            }
            assertEquals(1, DependentAdapter.DESTROYED.get(), "the runtime's instance went with its container");
            try (var _ = start("rb")) {
                assertEquals("{\"name\":\"<n>\"}", jsonb.toJson(tagged));
                assertEquals("n", jsonb.fromJson("{\"name\":\"<n>\"}", Tagged.class).name);
            }
        }
        assertEquals(2, DependentAdapter.DESTROYED.get());
    }

    /**
     * Without the registry (a container that runs no build compatible extension), the runtime takes the bean from
     * {@code CDI.current()}, and destroys it when the {@code Jsonb} closes (BUG-20261101-12).
     */
    @Test
    void withoutTheRegistryTheJsonbDestroysWhatItObtained() throws Exception {
        Tagged tagged = new Tagged();
        tagged.name = "n";
        try (var _ = new Weld("plain").disableDiscovery()
                .addBeanClasses(DependentAdapter.class, Formatter.class).initialize()) {
            assertNull(JsonbInstances.sourceFor(DependentAdapter.class), "no registry in this container");
            Jsonb jsonb = JsonbBuilder.create();
            assertEquals("{\"name\":\"<n>\"}", jsonb.toJson(tagged));
            assertEquals(0, DependentAdapter.DESTROYED.get());
            jsonb.close();
            assertEquals(1, DependentAdapter.DESTROYED.get(), "destroyed with the Jsonb that obtained it");
        }
    }

    /**
     * Two containers alive at once for the same application loader, both with a bean of the adapter: nothing tells
     * which one serves, so JSON-B says so instead of picking one; once one stops, the other serves.
     */
    @Test
    void twoContainersAliveForOneLoaderAreAnErrorNotAGuess() {
        Supplier<DependentAdapter> generated = JsonbInstances.of(DependentAdapter.class, DependentAdapter::new);
        try (var _ = start("alive-a")) {
            DependentAdapter fromA;
            try (var _ = start("alive-b")) {
                jakarta.json.bind.JsonbException e = org.junit.jupiter.api.Assertions.assertThrows(
                        jakarta.json.bind.JsonbException.class, generated::get);
                org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("2 running CDI containers"),
                        e.getMessage());
            }
            fromA = generated.get();
            assertEquals("<z>", fromA.adaptToJson("z"), "the remaining container serves");
        }
    }

    /**
     * An adapter whose class has a bounded type variable is a bean that its raw class does not look up (CDI 4.1
     * section 5.2.4). Its container serves it all the same, one instance, and destroys it when it stops
     * (BUG-20261117-02).
     */
    @Test
    @SuppressWarnings("rawtypes")
    void aBoundedGenericAdapterIsServedAndDestroyedByItsContainer() {
        Supplier<BoundedAdapter> generated = JsonbInstances.of(BoundedAdapter.class, BoundedAdapter::new);
        try (WeldContainer container = start("bounded")) {
            BoundedAdapter first = generated.get();
            assertEquals("<x>", first.adaptToJson("x"), "injected by the container");
            JsonbCdiRegistry registry = container.select(JsonbCdiRegistry.class).get();
            assertTrue(registry.serves(BoundedAdapter.class));
            assertSame(first, registry.get(BoundedAdapter.class), "one instance per container");
            assertEquals(1, BoundedAdapter.CREATED.get(), "created once");
            assertEquals(0, BoundedAdapter.DESTROYED.get(), "alive as long as its container");
        }
        assertEquals(1, BoundedAdapter.DESTROYED.get(), "destroyed with its container");
    }

    @Test
    void theRuntimeUsesTheBoundedGenericAdapterOfTheRunningContainer() throws Exception {
        BoundedTagged tagged = new BoundedTagged();
        tagged.name = "n";
        try (Jsonb jsonb = JsonbBuilder.create()) {
            try (var _ = start("bounded-runtime")) {
                assertEquals("{\"name\":\"<n>\"}", jsonb.toJson(tagged));
                assertEquals("n", jsonb.fromJson("{\"name\":\"<n>\"}", BoundedTagged.class).name);
            }
            assertEquals(1, BoundedAdapter.DESTROYED.get(), "the runtime's instance went with its container");
        }
    }

    /** Saying that a class is served creates no instance of it. */
    @Test
    void aBoundedGenericAdapterNobodyAsksForIsNeverCreated() {
        try (WeldContainer container = start("bounded-unused")) {
            assertTrue(container.select(JsonbCdiRegistry.class).get().serves(BoundedAdapter.class));
        }
        assertEquals(0, BoundedAdapter.CREATED.get(), "serving is not creating");
        assertEquals(0, BoundedAdapter.DESTROYED.get());
    }

    @Test
    void aClassWithoutABeanIsNotServed() {
        try (WeldContainer container = start("no-bean")) {
            JsonbCdiRegistry registry = container.select(JsonbCdiRegistry.class).get();
            assertFalse(registry.serves(Tagged.class));
            assertNull(registry.get(Tagged.class), "the class is created with new");
        }
    }

    @Test
    void aBoundedGenericClassWithoutABeanIsNotServed() {
        try (WeldContainer container = start("bounded-plain")) {
            JsonbCdiRegistry registry = container.select(JsonbCdiRegistry.class).get();
            assertFalse(registry.serves(PlainBounded.class));
            assertNull(registry.get(PlainBounded.class), "the class is created with new");
        }
    }

    /** The beans are walked once for a class with type variables that has no bean: the answer is kept. */
    @Test
    void aBoundedGenericClassWithoutABeanIsDecidedOnce() {
        try (WeldContainer container = start("bounded-plain-once")) {
            BeanManager manager = container.getBeanManager();
            CountingBeanContainer counting = new CountingBeanContainer(manager);
            JsonbCdiRegistry registry = new JsonbCdiRegistry(manager.createInstance(), counting);
            assertFalse(registry.serves(PlainBounded.class));
            assertNull(registry.get(PlainBounded.class));
            assertFalse(registry.serves(PlainBounded.class));
            assertEquals(1, counting.beanWalks.get(), "the beans are walked once for the class");
        }
    }

    /**
     * A producer the class declares is a bean of that class too, of another parameterization of it: the bean of the
     * class is the one whose type has the class's own type variables, and it is served.
     */
    @Test
    void theBeanOfABoundedGenericClassIsServedNotAProducerItDeclares() {
        try (WeldContainer container = start("bounded-twice")) {
            JsonbCdiRegistry registry = container.select(JsonbCdiRegistry.class).get();
            assertTrue(registry.serves(TwiceBounded.class));
            assertFalse(registry.get(TwiceBounded.class).produced, "the bean of the class, not the producer's");
        }
    }

    /**
     * The bean of the class has none of its types ({@code @Typed()}): the producer of {@code TypedAwayBounded<Integer>}
     * it declares is not served for it, so the class is created with new.
     */
    @Test
    void aProducerOfABoundedGenericClassIsNotServedForThatClass() {
        try (WeldContainer container = start("bounded-typed-away")) {
            JsonbCdiRegistry registry = container.select(JsonbCdiRegistry.class).get();
            assertFalse(registry.serves(TypedAwayBounded.class));
            assertNull(registry.get(TypedAwayBounded.class), "the class is created with new");
        }
    }

    /** Once its container stops, the registry creates and keeps nothing: what it would create, nobody destroys. */
    @Test
    void aStoppedRegistryCreatesNothing() {
        try (WeldContainer container = start("bounded-stopped")) {
            BeanManager manager = container.getBeanManager();
            JsonbCdiRegistry registry = new JsonbCdiRegistry(manager.createInstance(), manager);
            assertSame(registry.get(BoundedAdapter.class), registry.get(BoundedAdapter.class));
            registry.uninstall(null);
            assertEquals(1, BoundedAdapter.DESTROYED.get(), "destroyed when the registry stops");
            assertFalse(registry.serves(BoundedAdapter.class), "a stopped registry serves nothing");
            assertNull(registry.get(BoundedAdapter.class), "the class is created with new");
            assertNull(registry.get(DependentAdapter.class), "the class is created with new");
            assertEquals(1, BoundedAdapter.CREATED.get(), "nothing created after the registry stopped");
        }
    }

    /** Two beans of the type: ambiguous, whatever the class of each, so the class is created with new. */
    @Test
    void aGenericClassWithTwoBeansOfItsTypeIsNotServed() {
        try (WeldContainer container = start("two-of-a-type")) {
            JsonbCdiRegistry registry = container.select(JsonbCdiRegistry.class).get();
            assertFalse(registry.serves(TwoOfAType.class));
            assertNull(registry.get(TwoOfAType.class));
        }
    }

    /** Two creations of one bean that overlap leave one instance: the other is destroyed at once. */
    @Test
    void aBoundedGenericBeanCreatedTwiceAtOnceLeavesOneInstance() {
        try (WeldContainer container = start("bounded-reentrant")) {
            JsonbCdiRegistry registry = container.select(JsonbCdiRegistry.class).get();
            Object first = registry.get(ReentrantBounded.class);
            assertEquals(2, ReentrantBounded.CREATED.get(), "the bean asked for itself while it was created");
            assertEquals(1, ReentrantBounded.DESTROYED.get(), "the instance that came second is destroyed");
            assertSame(first, registry.get(ReentrantBounded.class), "one instance per container");
        }
        assertEquals(2, ReentrantBounded.DESTROYED.get(), "the instance kept went with its container");
    }

    /**
     * A bean that has only a qualifier of its own does not have {@code @Default}: its container serves it all the
     * same, as the only bean of its type (BUG-20261117-07).
     */
    @Test
    void anAdapterWithAnotherQualifierIsServedByItsContainer() {
        Supplier<MarkedAdapter> generated = JsonbInstances.of(MarkedAdapter.class, MarkedAdapter::new);
        try (WeldContainer container = start("marked")) {
            assertEquals("<x>", generated.get().adaptToJson("x"), "injected by the container");
            assertTrue(container.select(JsonbCdiRegistry.class).get().serves(MarkedAdapter.class));
        }
    }

    @Test
    @SuppressWarnings("rawtypes")
    void aBoundedGenericAdapterWithAnotherQualifierIsServedAndDestroyed() {
        Supplier<MarkedBounded> generated = JsonbInstances.of(MarkedBounded.class, MarkedBounded::new);
        try (var _ = start("marked-bounded")) {
            assertEquals("<x>", generated.get().adaptToJson("x"), "injected by the container");
            assertEquals(0, MarkedBounded.DESTROYED.get(), "alive as long as its container");
        }
        assertEquals(1, MarkedBounded.DESTROYED.get(), "destroyed with its container");
    }

    /** A bean of a normal scope is its container's to destroy: once, when it stops. */
    @Test
    void anAdapterOfANormalScopeWithAnotherQualifierIsServedAndDestroyedOnce() {
        Supplier<MarkedShared> generated = JsonbInstances.of(MarkedShared.class, MarkedShared::new);
        try (var _ = start("marked-shared")) {
            assertEquals("<x>", generated.get().adaptToJson("x"), "injected by the container");
            assertEquals(0, MarkedShared.DESTROYED.get(), "alive as long as its container");
        }
        assertEquals(1, MarkedShared.DESTROYED.get(), "destroyed once, with its container");
    }

    /** Two beans of the type, one with {@code @Default}: that one is served, as an injection without a qualifier. */
    @Test
    void theBeanWithDefaultIsServedBesideOneWithAnotherQualifier() {
        try (WeldContainer container = start("preferred")) {
            JsonbCdiRegistry registry = container.select(JsonbCdiRegistry.class).get();
            assertTrue(registry.serves(Preferred.class));
            assertEquals("preferred:x", registry.get(Preferred.class).adaptToJson("x"));
        }
    }

    /** The beans are asked once for a class that has no bean: the answer is kept. */
    @Test
    void aClassWithoutABeanIsDecidedOnce() {
        try (WeldContainer container = start("no-bean-once")) {
            BeanManager manager = container.getBeanManager();
            CountingBeanContainer counting = new CountingBeanContainer(manager);
            JsonbCdiRegistry registry = new JsonbCdiRegistry(manager.createInstance().select(
                    jakarta.enterprise.inject.Default.Literal.INSTANCE), counting);
            assertFalse(registry.serves(Tagged.class));
            assertNull(registry.get(Tagged.class));
            assertFalse(registry.serves(Tagged.class));
            assertEquals(1, counting.beanWalks.get(), "the beans are asked once for the class");
        }
    }

    /**
     * Without the registry, the runtime takes the bean of a class with a bounded type variable from
     * {@code CDI.current()} too, and destroys it when the {@code Jsonb} closes (BUG-20261117-06).
     */
    @Test
    void withoutTheRegistryABoundedGenericAdapterIsInjected() throws Exception {
        BoundedTagged tagged = new BoundedTagged();
        tagged.name = "n";
        try (var _ = new Weld("plain-bounded").disableDiscovery()
                .addBeanClasses(BoundedAdapter.class, Formatter.class).initialize()) {
            assertNull(JsonbInstances.sourceFor(BoundedAdapter.class), "no registry in this container");
            Jsonb jsonb = JsonbBuilder.create();
            assertEquals("{\"name\":\"<n>\"}", jsonb.toJson(tagged));
            assertEquals(0, BoundedAdapter.DESTROYED.get());
            jsonb.close();
            assertEquals(1, BoundedAdapter.DESTROYED.get(), "destroyed with the Jsonb that obtained it");
        }
    }

    /** Without the registry, a bean that has only a qualifier of its own is injected too (BUG-20261117-07). */
    @Test
    void withoutTheRegistryAnAdapterWithAnotherQualifierIsInjected() throws Exception {
        MarkedTagged tagged = new MarkedTagged();
        tagged.name = "n";
        try (var _ = new Weld("plain-marked").disableDiscovery()
                .addBeanClasses(MarkedAdapter.class, Formatter.class).initialize();
                Jsonb jsonb = JsonbBuilder.create()) {
            assertNull(JsonbInstances.sourceFor(MarkedAdapter.class), "no registry in this container");
            assertEquals("{\"name\":\"<n>\"}", jsonb.toJson(tagged));
        }
    }
}
