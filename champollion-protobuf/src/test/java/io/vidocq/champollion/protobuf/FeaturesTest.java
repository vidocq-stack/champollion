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
package io.vidocq.champollion.protobuf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.vidocq.champollion.protobuf.Descriptors.EnumType;
import io.vidocq.champollion.protobuf.Descriptors.Features;
import io.vidocq.champollion.protobuf.Descriptors.FieldPresence;
import io.vidocq.champollion.protobuf.Descriptors.JsonFormat;
import io.vidocq.champollion.protobuf.Descriptors.MessageEncoding;
import io.vidocq.champollion.protobuf.Descriptors.RepeatedFieldEncoding;
import io.vidocq.champollion.protobuf.Descriptors.Utf8Validation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests M4.1 — Modèle Edition 2023 {@code google.protobuf.FeatureSet}.
 *
 * <p>Spec : <a href="https://protobuf.dev/editions/features/">Edition Features</a>.</p>
 */
class FeaturesTest {

    @Nested
    @DisplayName("Defaults proto3/proto2/Editions 2023")
    class Defaults {

        @Test
        void proto3_defaults() {
            Features f = Features.PROTO3_DEFAULTS;
            assertEquals(FieldPresence.IMPLICIT, f.fieldPresence());
            assertEquals(EnumType.OPEN, f.enumType());
            assertEquals(RepeatedFieldEncoding.PACKED, f.repeatedFieldEncoding());
            assertEquals(Utf8Validation.VERIFY, f.utf8Validation());
            assertEquals(MessageEncoding.LENGTH_PREFIXED, f.messageEncoding());
            assertEquals(JsonFormat.ALLOW, f.jsonFormat());
        }

        @Test
        void proto2_defaults() {
            Features f = Features.PROTO2_DEFAULTS;
            assertEquals(FieldPresence.EXPLICIT, f.fieldPresence());
            assertEquals(EnumType.CLOSED, f.enumType());
            assertEquals(RepeatedFieldEncoding.EXPANDED, f.repeatedFieldEncoding());
            assertEquals(Utf8Validation.NONE, f.utf8Validation());
            assertEquals(MessageEncoding.LENGTH_PREFIXED, f.messageEncoding());
            assertEquals(JsonFormat.LEGACY_BEST_EFFORT, f.jsonFormat());
        }

        @Test
        void edition_2023_defaults_align_with_proto3() {
            assertSame(Features.PROTO3_DEFAULTS, Features.EDITION_2023_DEFAULTS);
        }

        @Test
        void proto2_and_proto3_defaults_differ() {
            assertNotEquals(Features.PROTO2_DEFAULTS, Features.PROTO3_DEFAULTS);
        }
    }

    @Nested
    @DisplayName("FieldDescriptor — features par champ")
    class FieldDescriptorFeatures {

        @Test
        void default_constructor_uses_proto3_features() {
            Descriptors.FieldDescriptor fd = new Descriptors.FieldDescriptor(
                    "name", "name", 1, FieldType.STRING,
                    Descriptors.Cardinality.IMPLICIT, false, null, null);
            assertSame(Features.PROTO3_DEFAULTS, fd.features());
        }

        @Test
        void explicit_constructor_with_features() {
            Features explicit = new Features(
                    FieldPresence.EXPLICIT, EnumType.OPEN,
                    RepeatedFieldEncoding.PACKED, Utf8Validation.VERIFY,
                    MessageEncoding.LENGTH_PREFIXED, JsonFormat.ALLOW);
            Descriptors.FieldDescriptor fd = new Descriptors.FieldDescriptor(
                    "name", "name", 1, FieldType.STRING,
                    Descriptors.Cardinality.EXPLICIT, false, null, null, explicit);
            assertEquals(FieldPresence.EXPLICIT, fd.features().fieldPresence());
        }

        @Test
        void null_features_falls_back_to_proto3() {
            Descriptors.FieldDescriptor fd = new Descriptors.FieldDescriptor(
                    "x", null, 1, FieldType.INT32,
                    Descriptors.Cardinality.IMPLICIT, false, null, null, null);
            assertSame(Features.PROTO3_DEFAULTS, fd.features());
        }
    }
}
