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
 * The CDI side of Champollion's JSON-B: a build compatible extension that validates, at build, the adapters,
 * serializers and deserializers JSON-B annotations name, and a registry that hands their CDI instances to the
 * generated bindings and to the runtime, per container. The core, {@code io.vidocq.champollion.jsonb}, stays free of
 * CDI.
 */
module io.vidocq.champollion.jsonb.cdi {
    requires io.vidocq.champollion.jsonb;
    requires jakarta.cdi;
    requires jakarta.cdi.lang.model;
    requires jakarta.json.bind;

    exports io.vidocq.champollion.jsonb.cdi;

    // No `opens`: Vauban creates the registry and calls its observers through the _VaubanComponents the Vauban
    // processor generated in this module. Other CDI containers run it on a class path, where this is inert.
    requires io.vidocq.vauban.api;

    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.champollion.jsonb.cdi._VaubanComponents;

    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.champollion.jsonb.cdi.JsonbCdiExtension;
}
