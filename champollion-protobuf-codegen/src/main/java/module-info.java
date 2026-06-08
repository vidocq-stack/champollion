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
 * Protobuf codegen: {@code @ProtobufStatic} APT + {@code .proto} parser.
 *
 * <p>M1 skeleton; real implementations in M2 ({@code .proto → Java} via
 * Class-File API) and M3 (static mode on annotated records).</p>
 */
module io.vidocq.champollion.protobuf.codegen {
    requires java.compiler;
    requires io.vidocq.champollion.protobuf;

    exports io.vidocq.champollion.protobuf.codegen;
    exports io.vidocq.champollion.protobuf.codegen.cli;

    // @ProtobufStatic APT: activated by javac via ServiceLoader.
    provides javax.annotation.processing.Processor
            with io.vidocq.champollion.protobuf.codegen.ProtobufStaticProcessor;
}
