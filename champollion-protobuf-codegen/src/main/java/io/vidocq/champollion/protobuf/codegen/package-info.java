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
/**
 * Protobuf codegen — M1 skeleton, with the real implementation deferred to M2/M3.
 *
 * <p>M2: {@code .proto → .java} compiler (lexer + parser + Class-File API emitter)
 * invoked by {@code champollion-protobuf-maven-plugin}.</p>
 *
 * <p>M3: {@code @ProtobufStatic} APT on Java records to produce a compiled
 * {@code <FQN>$$Binding} without reflection, ServiceLoader-discoverable,
 * AOT-compatible (GraalVM, Leyden CDS).</p>
 */
package io.vidocq.champollion.protobuf.codegen;
