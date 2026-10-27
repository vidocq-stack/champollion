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
package io.vidocq.champollion.protobuf.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A binding plan is identified by the record type it was built for, whatever arrays it holds. */
class BindingPlanIdentityTest {

    record Sample(int a) {}

    record Other(int a) {}

    @Test
    void wire_plans_of_the_same_type_are_equal() {
        var a = new RuntimeBinding.BindingPlan(Sample.class, List.of(), Map.of(), null, new Class<?>[] {int.class});
        var b = new RuntimeBinding.BindingPlan(Sample.class, List.of(), Map.of(), null, new Class<?>[] {int.class});

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, new RuntimeBinding.BindingPlan(Other.class, List.of(), Map.of(), null, new Class<?>[0]));
        assertNotEquals(a, (Object) "BindingPlan");
        assertEquals("BindingPlan[" + Sample.class.getName() + "]", a.toString());
    }

    @Test
    void json_plans_of_the_same_type_are_equal() {
        var a = jsonPlan(Sample.class);
        var b = jsonPlan(Sample.class);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, jsonPlan(Other.class));
        assertNotEquals(a, (Object) "JsonBindingPlan");
        assertEquals("JsonBindingPlan[" + Sample.class.getName() + "]", a.toString());
    }

    private static ProtobufJsonRuntime.JsonBindingPlan jsonPlan(Class<?> type) {
        return new ProtobufJsonRuntime.JsonBindingPlan(type, List.of(), Map.of(), Map.of(),
                new ProtobufJsonRuntime.FieldBinding[0], null, 0, -1, null);
    }
}
