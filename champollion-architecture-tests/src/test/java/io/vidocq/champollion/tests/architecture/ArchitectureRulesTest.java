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
package io.vidocq.champollion.tests.architecture;

import static io.vidocq.champollion.tests.architecture.ChampollionClasses.SHIPPED;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Named.named;

import java.util.stream.Stream;

import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The architecture rules on the classes Champollion ships: champollion-api, -jsonp, -jsonb, -protobuf,
 * -protobuf-codegen and -codegen-apt, test classes left out (Vidocq/vidocq#289). A failure names each class and the
 * dependency or call that breaks the rule.
 */
class ArchitectureRulesTest {

    @Test
    void theShippedClassesAreFound() {
        // Guards the other tests: a class path change that imports nothing would make every rule pass.
        assertTrue(SHIPPED.contain("io.vidocq.champollion.spi.RawJsonKeyWriter"), "champollion-api imported");
        assertTrue(
                SHIPPED.contain("io.vidocq.champollion.jsonp.internal.ChampollionJsonProvider"),
                "champollion-jsonp imported");
        assertTrue(
                SHIPPED.contain("io.vidocq.champollion.jsonb.internal.ChampollionJsonbProvider"),
                "champollion-jsonb imported");
        assertTrue(SHIPPED.contain("io.vidocq.champollion.protobuf.ParserProvider"), "champollion-protobuf imported");
        assertTrue(
                SHIPPED.contain("io.vidocq.champollion.protobuf.codegen.ProtobufStaticProcessor"),
                "champollion-protobuf-codegen imported");
        assertTrue(
                SHIPPED.contain("io.vidocq.champollion.codegen.apt.JsonbStaticProcessor"),
                "champollion-codegen-apt imported");
    }

    static Stream<Named<ArchRule>> rules() {
        return Stream.of(
                named("API_DEPENDENCIES", ArchitectureRules.API_DEPENDENCIES),
                named("JSONP_DEPENDENCIES", ArchitectureRules.JSONP_DEPENDENCIES),
                named("JSONB_DEPENDENCIES", ArchitectureRules.JSONB_DEPENDENCIES),
                named("PROTOBUF_DEPENDENCIES", ArchitectureRules.PROTOBUF_DEPENDENCIES),
                named("PROTOBUF_CODEGEN_DEPENDENCIES", ArchitectureRules.PROTOBUF_CODEGEN_DEPENDENCIES),
                named("APT_DEPENDENCIES", ArchitectureRules.APT_DEPENDENCIES),
                named("LAYERS", ArchitectureRules.LAYERS),
                named("FOUNDATIONAL_BRICKS", ArchitectureRules.FOUNDATIONAL_BRICKS),
                named("NO_REFLECTION", ArchitectureRules.NO_REFLECTION),
                named("NO_PLATFORM_THREADS", ArchitectureRules.NO_PLATFORM_THREADS),
                named("NO_THREAD_LOCAL", ArchitectureRules.NO_THREAD_LOCAL));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rules")
    void theShippedClassesKeepTheRule(ArchRule rule) {
        rule.check(SHIPPED);
    }
}
