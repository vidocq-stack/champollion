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
package io.vidocq.champollion.protobuf.wkt;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.vidocq.champollion.protobuf.FieldType;
import io.vidocq.champollion.protobuf.Message;
import io.vidocq.champollion.protobuf.ProtobufField;
import io.vidocq.champollion.protobuf.ProtobufMessage;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BUG-20261117-01: the registry of {@code Any}'s types is a static of the library, which outlives every application
 * layer of {@code vidocq dev}. A message type an application registers is a class of its layer: the registry must
 * not keep it, or the layer a hot reload discarded stays reachable through it.
 */
@DisplayName("The type registry keeps no application layer (BUG-20261117-01)")
class TypeRegistryTest {

    private static final String ORDER = "layer.Order";

    /** The application's message type, defined again by every layer. */
    @ProtobufMessage(ORDER)
    public record Order(@ProtobufField(number = 1, type = FieldType.STRING) String id) implements Message {}

    /** An application layer: a child loader of the library's that defines the application's classes itself. */
    private static final class ApplicationLayer extends ClassLoader {
        private static final String APPLICATION = TypeRegistryTest.class.getName();

        ApplicationLayer() {
            super(TypeRegistryTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(APPLICATION)) {
                return super.loadClass(name, resolve);
            }
            Class<?> loaded = findLoadedClass(name);
            return loaded != null ? loaded : define(name);
        }

        private Class<?> define(String name) throws ClassNotFoundException {
            try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                if (in == null) {
                    throw new ClassNotFoundException(name);
                }
                byte[] bytes = in.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }

        /** The layer's own copy of the application's message type. */
        Class<?> order() throws ClassNotFoundException {
            Class<?> order = loadClass(Order.class.getName());
            assertNotSame(Order.class, order, "the layer does not define the application's type itself");
            return order;
        }
    }

    /** Asks the collector until it hands a reference over: no sleeping, a bounded wait. */
    private static void collect(ReferenceQueue<Object> queue) throws InterruptedException {
        for (int i = 0; i < 30; i++) {
            System.gc();
            if (queue.remove(100) != null) {
                return;
            }
        }
    }

    /** Registers the message type of a new layer, as its application does, and returns the layer, weakly held. */
    private static WeakReference<Object> registerFromANewLayer(ReferenceQueue<Object> queue) throws Exception {
        ApplicationLayer layer = new ApplicationLayer();
        Class<?> order = layer.order();
        TypeRegistry.register(order);
        assertSame(order, TypeRegistry.lookup(ORDER));
        return new WeakReference<>(layer, queue);
    }

    @Test
    @DisplayName("a discarded layer that registered a message type is collectable, and its type is forgotten")
    void aDiscardedLayerThatRegisteredAMessageTypeIsCollectable() throws Exception {
        ReferenceQueue<Object> queue = new ReferenceQueue<>();
        WeakReference<Object> layer = registerFromANewLayer(queue);

        collect(queue);

        assertNull(layer.get(), "the layer's class loader is still reachable from the type registry");
        assertNull(TypeRegistry.lookup(ORDER), "the registry still answers with a type of a discarded layer");
    }

    @Test
    @DisplayName("a type of a live layer is still registered after a collection")
    void aTypeOfALiveLayerIsStillRegisteredAfterACollection() throws Exception {
        ApplicationLayer layer = new ApplicationLayer();
        Class<?> order = layer.order();
        TypeRegistry.register(order);
        ReferenceQueue<Object> queue = new ReferenceQueue<>();
        WeakReference<Object> canary = new WeakReference<>(new Object(), queue);

        collect(queue);

        assertNull(canary.get(), "no collection ran");
        assertSame(order, TypeRegistry.lookup(ORDER), "the layer is alive and the registry lost its type");
    }

    @Test
    @DisplayName("the type a later layer registers under the same name replaces the earlier one")
    void theTypeOfALaterLayerReplacesTheEarlierOne() throws Exception {
        ApplicationLayer first = new ApplicationLayer();
        ApplicationLayer second = new ApplicationLayer();
        TypeRegistry.register(first.order());

        TypeRegistry.register(second.order());

        assertSame(second.order(), TypeRegistry.lookup(ORDER));
    }
}
