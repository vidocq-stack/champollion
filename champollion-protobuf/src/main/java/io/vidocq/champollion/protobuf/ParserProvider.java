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

/**
 * SPI {@link java.util.ServiceLoader} to provide precompiled {@link Parser}
 * instances (APT static mode).
 *
 * <p>Each module that adds classes annotated {@link ProtobufStatic}
 * contributes an implementation of {@code ParserProvider} via the service file
 * {@code META-INF/services/io.vidocq.champollion.protobuf.ParserProvider}.</p>
 *
 * <p>{@link Protobuf#parser(Class)} consults providers in {@link ServiceLoader}
 * order. The first one that returns a non-null parser is used.
 * If none match, the M1.3 reflective runtime takes over.</p>
 */
public interface ParserProvider {

    /**
     * @return a {@link Parser} for {@code type}, or {@code null} if this
     * provider cannot handle it.
     */
    <T> Parser<T> parserFor(Class<T> type);
}
