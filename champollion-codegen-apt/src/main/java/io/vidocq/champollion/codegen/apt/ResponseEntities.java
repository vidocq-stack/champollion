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
package io.vidocq.champollion.codegen.apt;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The static types of the entities the code of a compilation hands a JAX-RS {@code Response}: the argument of
 * {@code Response.ok(x)}, {@code Response.ok(x, type)}, {@code Response.accepted(x)} and {@code ResponseBuilder.entity(x)}
 * in any chain, and the {@code T} of a {@code new GenericEntity<T>(…)}, anonymous subclass or not. JAX-RS hands them to
 * JSON-B at run time, by the entity's class: no method signature shows them, so the method bodies are read, with the
 * compiler's tree API ({@code jdk.compiler}).
 *
 * <p>Loaded only when that API is there: {@link #find} reports an empty result when the compiler does not expose its
 * trees (another compiler, or a wrapped processing environment).</p>
 */
final class ResponseEntities {

    static final String RESPONSE = "jakarta.ws.rs.core.Response";
    static final String RESPONSE_BUILDER = "jakarta.ws.rs.core.Response.ResponseBuilder";
    static final String GENERIC_ENTITY = "jakarta.ws.rs.core.GenericEntity";
    private static final Set<String> FACTORIES = Set.of("ok", "accepted");

    private ResponseEntities() {
    }

    /** The entity types the bodies of {@code types} give a {@code Response}; empty when the trees are unavailable. */
    static Optional<List<TypeMirror>> find(ProcessingEnvironment env, Collection<TypeElement> types) {
        Trees trees;
        try {
            trees = Trees.instance(env);
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
        List<TypeMirror> found = new ArrayList<>();
        for (TypeElement type : types) {
            if (type.getEnclosingElement().getKind() != ElementKind.PACKAGE) continue;   // scanned with its top level
            TreePath path = trees.getPath(type);
            if (path != null) new Scanner(trees, env, found).scan(path, null);
        }
        return Optional.of(found);
    }

    /** Collects the entity of each {@code Response} call and each {@code GenericEntity} creation. */
    private static final class Scanner extends TreePathScanner<Void, Void> {

        private final Trees trees;
        private final ProcessingEnvironment env;
        private final List<TypeMirror> found;

        Scanner(Trees trees, ProcessingEnvironment env, List<TypeMirror> found) {
            this.trees = trees;
            this.env = env;
            this.found = found;
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
            Element method = trees.getElement(getCurrentPath());
            if (method instanceof ExecutableElement m && !call.getArguments().isEmpty() && takesEntity(m)) {
                ExpressionTree argument = call.getArguments().getFirst();
                add(trees.getTypeMirror(new TreePath(getCurrentPath(), argument)));
            }
            return super.visitMethodInvocation(call, unused);
        }

        @Override
        public Void visitNewClass(NewClassTree creation, Void unused) {
            TypeMirror created = trees.getTypeMirror(getCurrentPath());
            genericEntityArgument(created).ifPresent(found::add);
            return super.visitNewClass(creation, unused);
        }

        /** {@code Response.ok/accepted(entity…)} or {@code ResponseBuilder.entity(entity…)}. */
        private static boolean takesEntity(ExecutableElement m) {
            String owner = ((TypeElement) m.getEnclosingElement()).getQualifiedName().toString();
            String name = m.getSimpleName().toString();
            boolean firstIsObject = !m.getParameters().isEmpty()
                    && "java.lang.Object".equals(m.getParameters().getFirst().asType().toString());
            return firstIsObject && (RESPONSE.equals(owner) && FACTORIES.contains(name)
                    || RESPONSE_BUILDER.equals(owner) && "entity".equals(name));
        }

        /** The entity type of an argument: the {@code T} of a {@code GenericEntity<T>}, else its own static type. */
        private void add(TypeMirror type) {
            if (type == null || type.getKind() != TypeKind.DECLARED && type.getKind() != TypeKind.ARRAY) return;
            Optional<TypeMirror> generic = genericEntityArgument(type);
            if (generic.isPresent()) {
                if (!found.contains(generic.get())) found.add(generic.get());
            } else if (!found.contains(type)) {
                found.add(type);
            }
        }

        /** The {@code T} of a {@code GenericEntity<T>} or of an (anonymous) subclass of it. */
        private Optional<TypeMirror> genericEntityArgument(TypeMirror type) {
            for (TypeMirror t = type; t != null && t.getKind() == TypeKind.DECLARED; t = superclass(t)) {
                DeclaredType d = (DeclaredType) t;
                if (((TypeElement) d.asElement()).getQualifiedName().contentEquals(GENERIC_ENTITY)) {
                    return d.getTypeArguments().size() == 1 ? Optional.of(d.getTypeArguments().getFirst())
                            : Optional.empty();
                }
            }
            return Optional.empty();
        }

        /** The superclass of {@code t} with the type arguments {@code t} gives it, or {@code null}. */
        private TypeMirror superclass(TypeMirror t) {
            return env.getTypeUtils().directSupertypes(t).stream()
                    .filter(s -> s.getKind() == TypeKind.DECLARED
                            && ((DeclaredType) s).asElement().getKind() == ElementKind.CLASS)
                    .findFirst().orElse(null);
        }
    }
}
