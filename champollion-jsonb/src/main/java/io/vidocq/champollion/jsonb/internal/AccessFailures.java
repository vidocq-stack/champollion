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
package io.vidocq.champollion.jsonb.internal;

import jakarta.json.bind.JsonbException;

import java.lang.reflect.InaccessibleObjectException;

/**
 * The failures of a reflective binding whose cause is Java module access (BUG-20261031-01): the message names
 * the module, the package and the {@code opens} line to add, instead of a bare {@code Failed to read property} or
 * {@code ctor failed}.
 */
final class AccessFailures {

    /** This module's name, also when the tests run it from the class path. */
    private static final String MODULE = "io.vidocq.champollion.jsonb";

    private AccessFailures() {}

    /** A {@link JsonbException} with {@code message}, completed with the access cause when there is one. */
    static JsonbException wrap(String message, Class<?> type, Throwable cause) {
        return new JsonbException(message + hint(type, cause), cause);
    }

    /**
     * {@code ": module M does not open package P to <this module> — add `opens P;` to its module-info.java"} when
     * {@code cause} is an access failure and {@code type}'s module does not open its package to Champollion;
     * otherwise empty.
     */
    static String hint(Class<?> type, Throwable cause) {
        if (type == null || !isAccessFailure(cause)) {
            return "";
        }
        Module module = type.getModule();
        String pkg = type.getPackageName();
        if (!module.isNamed() || module.isOpen(pkg, AccessFailures.class.getModule())) {
            return "";
        }
        return ": module " + module.getName() + " does not open package " + pkg + " to " + MODULE
                + " — add `opens " + pkg + ";` to its module-info.java";
    }

    private static boolean isAccessFailure(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof IllegalAccessException || c instanceof InaccessibleObjectException) {
                return true;
            }
        }
        return false;
    }
}
