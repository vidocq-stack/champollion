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
package io.vidocq.champollion.jsonb.spi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type for generation of a {@link JsonbBinding} at compile time by
 * {@code champollion-codegen-apt}.
 *
 * <p>The generated binding will be registered as a {@link JsonbBinding} service
 * via a {@code META-INF/services/io.vidocq.champollion.jsonb.spi.JsonbBinding}
 * file and therefore used lookup-first by {@code ChampollionJsonb}, with no
 * reflection at runtime.</p>
 *
 * <p>{@link RetentionPolicy#CLASS} retention: preserved in bytecode to allow a
 * possible post-compile scan by {@code champollion-codegen-maven-plugin} on
 * transitively annotated types, without requiring source code access.</p>
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface JsonbStatic {
}
