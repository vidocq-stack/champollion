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
package io.vidocq.champollion.bench;

import io.vidocq.champollion.protobuf.FieldType;
import io.vidocq.champollion.protobuf.Message;
import io.vidocq.champollion.protobuf.ProtobufField;
import io.vidocq.champollion.protobuf.ProtobufMessage;
import io.vidocq.champollion.protobuf.ProtobufStatic;

import java.util.List;

/**
 * Record de bench pour {@link ProtobufBenchmark} — top-level pour que l'APT
 * {@code ProtobufStaticProcessor} génère ses sources dans
 * {@code io.vidocq.champollion.bench} sans collision de package
 * (un type nested produirait un sous-package {@code .ProtobufBenchmark} qui
 * clashe avec la classe parente).
 */
@ProtobufStatic
@ProtobufMessage("bench.BenchPerson")
public record BenchPerson(
        @ProtobufField(number = 1, type = FieldType.STRING) String name,
        @ProtobufField(number = 2, type = FieldType.INT32) int age,
        @ProtobufField(number = 3, type = FieldType.STRING) List<String> tags,
        @ProtobufField(number = 4, type = FieldType.BOOL) boolean active,
        @ProtobufField(number = 5, type = FieldType.INT64) long sequence
) implements Message {}
