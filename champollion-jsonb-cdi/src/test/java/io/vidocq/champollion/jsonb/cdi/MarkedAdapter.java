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
import jakarta.inject.Inject;
import jakarta.json.bind.adapter.JsonbAdapter;

/** An adapter that is a bean with a qualifier of its own, so without {@code @Default}; it injects a bean. */
@Marked
@Dependent
public class MarkedAdapter implements JsonbAdapter<String, String> {

    @Inject
    Formatter formatter;

    /** Decorated when the container made this instance, unchanged when its constructor alone did. */
    @Override
    public String adaptToJson(String value) {
        return formatter == null ? value : formatter.decorate(value);
    }

    @Override
    public String adaptFromJson(String json) {
        return formatter == null ? json : formatter.strip(json);
    }
}
