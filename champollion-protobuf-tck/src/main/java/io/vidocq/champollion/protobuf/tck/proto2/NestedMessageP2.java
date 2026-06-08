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
package io.vidocq.champollion.protobuf.tck.proto2;

import io.vidocq.champollion.protobuf.FieldType;
import io.vidocq.champollion.protobuf.Message;
import io.vidocq.champollion.protobuf.ProtobufField;
import io.vidocq.champollion.protobuf.ProtobufMessage;

/**
 * {@code TestAllTypesProto2.NestedMessage} — recursive sub-message (proto2).
 *
 * <p>{@code optional int32 a = 1; optional TestAllTypesProto2 corecursive = 2;}.</p>
 */
@ProtobufMessage("protobuf_test_messages.proto2.TestAllTypesProto2.NestedMessage")
public record NestedMessageP2(
        @ProtobufField(number = 1, type = FieldType.INT32, explicitPresence = true) Integer a,
        @ProtobufField(number = 2, type = FieldType.MESSAGE, explicitPresence = true) TestAllTypesProto2 corecursive
) implements Message {}
