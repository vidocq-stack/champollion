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

import io.vidocq.champollion.protobuf.ProtobufMessage;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Registry of types accepted by {@link Any#unpack(Class)} and by the canonical
 * JSON mapping of {@code Any}.
 *
 * <p>Proto convention: a {@code type_url} has the form
 * {@code "<base>/<full.name>"}, with the typical {@code base} being
 * {@code "type.googleapis.com"} (cf.
 * <a href="https://protobuf.dev/reference/protobuf/google.protobuf/#any">Any spec</a>).
 * Only the {@code full.name} part is used for lookup here — the base
 * is optional for the runtime's needs.</p>
 *
 * <p>Mutable, thread-safe, module-static. Users register their
 * types via {@link #register(Class)} (the {@code fullName} comes from
 * {@link ProtobufMessage#value()}) or directly via {@code typeFullName}.</p>
 */
public final class TypeRegistry {

    public static final String DEFAULT_BASE = "type.googleapis.com";

    private static final ConcurrentMap<String, Class<?>> BY_FULL_NAME = new ConcurrentHashMap<>();

    static {
        // Automatic registration of WKT exposed by Champollion.
        register(Timestamp.class);
        register(Duration.class);
        register(Empty.class);
        register(FieldMask.class);
        register(Any.class);
        register(Struct.class);
        register(Value.class);
        register(ListValue.class);
        register(Wrappers.DoubleValue.class);
        register(Wrappers.FloatValue.class);
        register(Wrappers.Int64Value.class);
        register(Wrappers.UInt64Value.class);
        register(Wrappers.Int32Value.class);
        register(Wrappers.UInt32Value.class);
        register(Wrappers.BoolValue.class);
        register(Wrappers.StringValue.class);
        register(Wrappers.BytesValue.class);
    }

    private TypeRegistry() {}

    public static void register(Class<?> type) {
        Objects.requireNonNull(type, "type");
        ProtobufMessage pm = type.getAnnotation(ProtobufMessage.class);
        if (pm == null) {
            throw new IllegalArgumentException(type + " is missing @ProtobufMessage");
        }
        String full = pm.value().isEmpty() ? type.getSimpleName() : pm.value();
        BY_FULL_NAME.put(full, type);
    }

    public static void register(String typeFullName, Class<?> type) {
        Objects.requireNonNull(typeFullName, "typeFullName");
        Objects.requireNonNull(type, "type");
        BY_FULL_NAME.put(typeFullName, type);
    }

    public static Class<?> lookup(String typeFullName) {
        return BY_FULL_NAME.get(typeFullName);
    }

    /** Extrait la portion {@code <full.name>} d'un {@code type_url}. */
    public static String fullNameFromTypeUrl(String typeUrl) {
        int slash = typeUrl.lastIndexOf('/');
        return slash < 0 ? typeUrl : typeUrl.substring(slash + 1);
    }

    public static String typeUrlFor(Class<?> type) {
        ProtobufMessage pm = type.getAnnotation(ProtobufMessage.class);
        if (pm == null) {
            throw new IllegalArgumentException(type + " is missing @ProtobufMessage");
        }
        String full = pm.value().isEmpty() ? type.getSimpleName() : pm.value();
        return DEFAULT_BASE + "/" + full;
    }
}
