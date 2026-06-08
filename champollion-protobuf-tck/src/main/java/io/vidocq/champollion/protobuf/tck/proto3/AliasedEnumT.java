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
package io.vidocq.champollion.protobuf.tck.proto3;

import io.vidocq.champollion.protobuf.ProtoEnumValue;

/**
 * {@code TestAllTypesProto3.AliasedEnum} — enum avec {@code option allow_alias = true}.
 *
 * <p>Multiple Java constants point to the same proto value via
 * {@link ProtoEnumValue} :</p>
 *
 * <pre>
 * ALIAS_FOO = 0
 * ALIAS_BAR = 1
 * ALIAS_BAZ = 2
 * MOO      = 2   (alias of ALIAS_BAZ)
 * moo      = 2   (alias different-case)
 * bAz      = 2   (alias different-case bis)
 * </pre>
 *
 * <p>During deserialization, the first constant declared with
 * {@code value=2} (i.e. {@code ALIAS_BAZ}) is used — declaration order
 * wins in case of aliasing.</p>
 */
public enum AliasedEnumT {
    ALIAS_FOO,                       // 0
    ALIAS_BAR,                       // 1
    ALIAS_BAZ,                       // 2
    @ProtoEnumValue(2) MOO,          // alias 2
    @ProtoEnumValue(2) moo,          // alias 2
    @ProtoEnumValue(2) bAz           // alias 2
}
