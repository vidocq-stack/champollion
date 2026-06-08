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
package io.vidocq.champollion.protobuf;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Tells the static codegen ({@code champollion-protobuf-codegen} APT) that a
 * dedicated {@code Parser<T>} without reflection must be generated for this type.
 *
 * <p>The record must also carry {@link ProtobufMessage} and its fields
 * {@link ProtobufField}. The generated parser is registered via
 * {@link java.util.ServiceLoader} as a {@link ParserProvider} — at runtime
 * {@code Protobuf.parser(Class)} will prefer this parser over the reflective runtime.</p>
 *
 * <p>AOT advantage (GraalVM, Leyden CDS): no {@code MethodHandles},
 * no introspection — all resolution is done at compile time.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ProtobufStatic {}
