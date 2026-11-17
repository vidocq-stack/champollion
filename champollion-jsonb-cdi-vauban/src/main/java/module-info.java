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
 * The Vauban glue of {@code io.vidocq.champollion.jsonb.cdi}: the components that let Vauban create the JSON-B
 * registry, call its observers and proxy it, without reflection and without an {@code opens}. The Vidocq runtime
 * extension that brings the registry adds this module; another CDI container needs nothing of it.
 */
module io.vidocq.champollion.jsonb.cdi.vauban {
    requires io.vidocq.champollion.jsonb;
    requires io.vidocq.champollion.jsonb.cdi;
    requires io.vidocq.vauban.api;
    requires jakarta.cdi;

    // Exports nothing: Vauban loads the components as a service.
    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.champollion.jsonb.cdi.vauban.JsonbCdiVaubanComponents;
}
