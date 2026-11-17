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

import jakarta.enterprise.context.Dependent;

/**
 * A class with an unbounded type variable that two beans have the type of: itself and its subclass. Its raw class
 * looks both up (CDI 4.1 section 5.2.4), so the lookup by type is ambiguous.
 *
 * @param <T> unused
 */
@Dependent
public class TwoOfAType<T> {

    /**
     * The second bean of type {@link TwoOfAType}.
     *
     * @param <T> unused
     */
    @Dependent
    public static class Child<T> extends TwoOfAType<T> {
    }
}
