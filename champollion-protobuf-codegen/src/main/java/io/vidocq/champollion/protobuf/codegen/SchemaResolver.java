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
package io.vidocq.champollion.protobuf.codegen;

import io.vidocq.champollion.protobuf.Descriptors;
import io.vidocq.champollion.protobuf.FieldType;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.EnumDecl;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.EnumValueDecl;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.Edition;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.FieldDecl;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.FieldKind;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.MessageDecl;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.MethodDecl;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.NamedType;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.Proto2;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.Proto3;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.ProtoFile;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.Scalar;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.ScalarType;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.ServiceDecl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Semantic resolver: converts a {@link ProtoFile} (syntax AST produced by
 * {@link io.vidocq.champollion.protobuf.codegen.internal.ProtoParser})
 * into a {@link Descriptors.FileDescriptor} (stable reflective model).
 *
 * <p>Spec: <a href="https://protobuf.dev/reference/protobuf/proto3-spec/#identifiers">Proto3 §Type Lookup</a>.</p>
 *
 * <p>Applied proto3 / Editions lookup rules:</p>
 * <ul>
 *   <li>name starting with {@code .} → fully qualified from the root
 *       (e.g. {@code .google.protobuf.Timestamp})</li>
 *   <li>unqualified name → searched from the local scope (current message,
 *       then enclosing messages, then file, then imports)</li>
 *   <li>conflict between {@link MessageDecl} and {@link EnumDecl} is forbidden</li>
 * </ul>
 *
 * <p>Imports are not followed in M2.2: only the types defined in the current
 * {@link ProtoFile} are resolved. The multi-file resolver will come with
 * M2.3 ({@link SchemaResolver#resolve(List)}).</p>
 */
public final class SchemaResolver {

    private SchemaResolver() {}

    /** Resolves a single file (no cross-file imports). */
    public static Descriptors.FileDescriptor resolve(ProtoFile file) {
        Map<String, Symbol> table = new LinkedHashMap<>();
        indexFile(file, table);
        return resolveOne(file, table);
    }

    /**
     * Multi-file resolution. The symbol table is built by walking all files
     * (each message/enum is indexed by its fullName, with the proto package as
     * the prefix). Cross-file references in {@link NamedType} are resolved
     * against this shared table.
     *
     * <p>Note M2.5: imports are not required in order to reference a cross-file
     * type — resolution is global. The strict check
     * "every referenced type must come from a declared import" will be added
     * in M2.6 when we handle strict namespace separation.</p>
     *
     * @return map {@code fileName → FileDescriptor} preserving source insertion order.
     */
    public static Map<String, Descriptors.FileDescriptor> resolveAll(Collection<ProtoFile> files) {
        Objects.requireNonNull(files, "files");
        Map<String, Symbol> table = new LinkedHashMap<>();
        for (ProtoFile f : files) indexFile(f, table);
        Map<String, Descriptors.FileDescriptor> out = new LinkedHashMap<>();
        for (ProtoFile f : files) out.put(f.fileName(), resolveOne(f, table));
        return out;
    }

    private static void indexFile(ProtoFile file, Map<String, Symbol> table) {
        String pkg = file.packageName();
        for (MessageDecl m : file.messages()) indexMessage(m, pkg, table);
        for (EnumDecl e : file.enums()) indexEnum(e, pkg, table);
    }

    private static Descriptors.FileDescriptor resolveOne(ProtoFile file, Map<String, Symbol> table) {
        String pkg = file.packageName();
        Descriptors.Syntax syntax = toSyntax(file.syntax());
        Descriptors.Features fileFeatures = featuresFromOptions(
                file.options(), defaultFeaturesFor(syntax));
        List<Descriptors.Descriptor> outMsgs = new ArrayList<>();
        for (MessageDecl m : file.messages()) outMsgs.add(resolveMessage(m, pkg, table, fileFeatures));
        List<Descriptors.EnumDescriptor> outEnums = new ArrayList<>();
        for (EnumDecl e : file.enums()) outEnums.add(toEnumDescriptor(e, pkg));
        List<Descriptors.ServiceDescriptor> outServices = new ArrayList<>();
        for (ServiceDecl s : file.services()) outServices.add(resolveService(s, pkg, table));
        return new Descriptors.FileDescriptor(
                file.fileName(),
                pkg,
                syntax,
                outMsgs,
                outEnums,
                outServices);
    }

    private static Descriptors.Features defaultFeaturesFor(Descriptors.Syntax syntax) {
        return switch (syntax) {
            case PROTO2 -> Descriptors.Features.PROTO2_DEFAULTS;
            case PROTO3 -> Descriptors.Features.PROTO3_DEFAULTS;
            case EDITION_2023 -> Descriptors.Features.EDITION_2023_DEFAULTS;
        };
    }

    /**
     * Convertit une liste d'options (format {@code features.<name> = <VALUE>})
     * en {@link Descriptors.Features}, en partant des defaults fournis et en
     * appliquant chaque override.
     */
    private static Descriptors.Features featuresFromOptions(List<ProtoAst.OptionEntry> options,
                                                            Descriptors.Features base) {
        Descriptors.FieldPresence fp = base.fieldPresence();
        Descriptors.EnumType et = base.enumType();
        Descriptors.RepeatedFieldEncoding rfe = base.repeatedFieldEncoding();
        Descriptors.Utf8Validation uv = base.utf8Validation();
        Descriptors.MessageEncoding me = base.messageEncoding();
        Descriptors.JsonFormat jf = base.jsonFormat();
        for (ProtoAst.OptionEntry o : options) {
            if (!o.name().startsWith("features.")) continue;
            String key = o.name().substring("features.".length());
            String v = o.value();
            switch (key) {
                case "field_presence" -> fp = Descriptors.FieldPresence.valueOf(v);
                case "enum_type" -> et = Descriptors.EnumType.valueOf(v);
                case "repeated_field_encoding" -> rfe = Descriptors.RepeatedFieldEncoding.valueOf(v);
                case "utf8_validation" -> uv = Descriptors.Utf8Validation.valueOf(v);
                case "message_encoding" -> me = Descriptors.MessageEncoding.valueOf(v);
                case "json_format" -> jf = Descriptors.JsonFormat.valueOf(v);
                default -> { /* unknown feature — silently ignored */ }
            }
        }
        return new Descriptors.Features(fp, et, rfe, uv, me, jf);
    }

    private static Descriptors.ServiceDescriptor resolveService(ServiceDecl s, String pkg, Map<String, Symbol> table) {
        String full = join(pkg, s.name());
        List<Descriptors.MethodDescriptor> outMethods = new ArrayList<>();
        for (MethodDecl m : s.methods()) {
            String input = resolveMessageRef(m.inputType(), pkg, table, full, m.name(), "input");
            String output = resolveMessageRef(m.outputType(), pkg, table, full, m.name(), "output");
            outMethods.add(new Descriptors.MethodDescriptor(
                    m.name(), input, output, m.clientStreaming(), m.serverStreaming()));
        }
        return new Descriptors.ServiceDescriptor(s.name(), full, outMethods);
    }

    private static String resolveMessageRef(String ref, String pkg, Map<String, Symbol> table,
                                            String serviceFull, String methodName, String role) {
        Symbol sym = lookup(ref, pkg, table);
        if (sym == null) {
            throw new SchemaResolutionException(
                    "Unresolved " + role + " type '" + ref + "' in rpc "
                            + serviceFull + "." + methodName);
        }
        if (!(sym instanceof MessageSymbol ms)) {
            throw new SchemaResolutionException(
                    "RPC " + role + " must be a message, got " + ref + " in " + serviceFull + "." + methodName);
        }
        // Re-find canonical
        if (ref.startsWith(".")) return ref.substring(1);
        String s = pkg;
        while (true) {
            String candidate = join(s, ref);
            if (table.containsKey(candidate)) return candidate;
            if (s.isEmpty()) break;
            int dot = s.lastIndexOf('.');
            s = (dot < 0) ? "" : s.substring(0, dot);
        }
        return ms.decl().name();
    }

    // ============================================================ Symbol table

    private sealed interface Symbol permits MessageSymbol, EnumSymbol {}
    private record MessageSymbol(MessageDecl decl) implements Symbol {}
    private record EnumSymbol(EnumDecl decl) implements Symbol {}

    private static void indexMessage(MessageDecl m, String scope, Map<String, Symbol> table) {
        String full = join(scope, m.name());
        if (table.containsKey(full)) {
            throw new SchemaResolutionException("Duplicate symbol: " + full);
        }
        table.put(full, new MessageSymbol(m));
        for (MessageDecl n : m.nestedMessages()) indexMessage(n, full, table);
        for (EnumDecl e : m.nestedEnums()) indexEnum(e, full, table);
    }

    private static void indexEnum(EnumDecl e, String scope, Map<String, Symbol> table) {
        String full = join(scope, e.name());
        if (table.containsKey(full)) {
            throw new SchemaResolutionException("Duplicate symbol: " + full);
        }
        table.put(full, new EnumSymbol(e));
    }

    private static Symbol lookup(String reference, String currentScope, Map<String, Symbol> table) {
        if (reference.startsWith(".")) {
            return table.get(reference.substring(1));
        }
        // Local search → then walk up parent scopes → root
        String scope = currentScope;
        while (true) {
            String candidate = join(scope, reference);
            Symbol s = table.get(candidate);
            if (s != null) return s;
            if (scope.isEmpty()) break;
            int dot = scope.lastIndexOf('.');
            scope = (dot < 0) ? "" : scope.substring(0, dot);
        }
        return null;
    }

    // ============================================================ Resolution

    private static Descriptors.Descriptor resolveMessage(MessageDecl m, String scope,
                                                         Map<String, Symbol> table,
                                                         Descriptors.Features inherited) {
        String full = join(scope, m.name());
        Descriptors.Features msgFeatures = featuresFromOptions(m.options(), inherited);
        List<Descriptors.FieldDescriptor> fields = new ArrayList<>();
        for (FieldDecl f : m.fields()) fields.add(resolveField(f, full, table, msgFeatures));
        List<Descriptors.Descriptor> nested = new ArrayList<>();
        for (MessageDecl n : m.nestedMessages()) nested.add(resolveMessage(n, full, table, msgFeatures));
        List<Descriptors.EnumDescriptor> nestedE = new ArrayList<>();
        for (EnumDecl e : m.nestedEnums()) nestedE.add(toEnumDescriptor(e, full));
        return new Descriptors.Descriptor(m.name(), full, fields, nested, nestedE);
    }

    private static Descriptors.FieldDescriptor resolveField(FieldDecl f, String scope,
                                                            Map<String, Symbol> table,
                                                            Descriptors.Features inherited) {
        Descriptors.Cardinality card = switch (f.kind()) {
            case REPEATED -> Descriptors.Cardinality.REPEATED;
            case OPTIONAL -> Descriptors.Cardinality.EXPLICIT;
            case SINGULAR -> Descriptors.Cardinality.IMPLICIT;
        };

        FieldType type;
        String messageTypeName = null;
        String enumTypeName = null;

        switch (f.type()) {
            case ScalarType s -> type = scalarToFieldType(s.scalar());
            case NamedType n -> {
                Symbol sym = lookup(n.fullName(), scope, table);
                if (sym == null) {
                    throw new SchemaResolutionException(
                            "Unresolved type '" + n.fullName() + "' in field "
                                    + scope + "." + f.name());
                }
                switch (sym) {
                    case MessageSymbol ms -> {
                        type = FieldType.MESSAGE;
                        messageTypeName = canonicalFullName(scope, ms.decl().name(), n.fullName(), table);
                    }
                    case EnumSymbol es -> {
                        type = FieldType.ENUM;
                        enumTypeName = canonicalFullName(scope, es.decl().name(), n.fullName(), table);
                    }
                }
            }
        }

        // packed now depends on the repeated_field_encoding feature
        boolean packed = card == Descriptors.Cardinality.REPEATED
                && type.packable()
                && inherited.repeatedFieldEncoding() == Descriptors.RepeatedFieldEncoding.PACKED;
        return new Descriptors.FieldDescriptor(
                f.name(),
                Descriptors.toJsonName(f.name()),
                f.number(),
                type,
                card,
                packed,
                messageTypeName,
                enumTypeName,
                inherited);
    }

    private static String canonicalFullName(String scope, String simpleName,
                                            String referenceUsed, Map<String, Symbol> table) {
        // Runs the lookup again to recover the exact full name in the table.
        if (referenceUsed.startsWith(".")) return referenceUsed.substring(1);
        String s = scope;
        while (true) {
            String candidate = join(s, referenceUsed);
            if (table.containsKey(candidate)) return candidate;
            if (s.isEmpty()) break;
            int dot = s.lastIndexOf('.');
            s = (dot < 0) ? "" : s.substring(0, dot);
        }
        // Fallback: simple name
        return simpleName;
    }

    private static Descriptors.EnumDescriptor toEnumDescriptor(EnumDecl e, String scope) {
        String full = join(scope, e.name());
        List<Descriptors.EnumValueDescriptor> values = new ArrayList<>();
        for (EnumValueDecl v : e.values()) {
            values.add(new Descriptors.EnumValueDescriptor(v.name(), v.number()));
        }
        return new Descriptors.EnumDescriptor(e.name(), full, values);
    }

    private static FieldType scalarToFieldType(Scalar scalar) {
        return switch (scalar) {
            case DOUBLE -> FieldType.DOUBLE;
            case FLOAT -> FieldType.FLOAT;
            case INT32 -> FieldType.INT32;
            case INT64 -> FieldType.INT64;
            case UINT32 -> FieldType.UINT32;
            case UINT64 -> FieldType.UINT64;
            case SINT32 -> FieldType.SINT32;
            case SINT64 -> FieldType.SINT64;
            case FIXED32 -> FieldType.FIXED32;
            case FIXED64 -> FieldType.FIXED64;
            case SFIXED32 -> FieldType.SFIXED32;
            case SFIXED64 -> FieldType.SFIXED64;
            case BOOL -> FieldType.BOOL;
            case STRING -> FieldType.STRING;
            case BYTES -> FieldType.BYTES;
        };
    }

    private static Descriptors.Syntax toSyntax(ProtoAst.Syntax syntax) {
        return switch (syntax) {
            case Proto2 ignored -> Descriptors.Syntax.PROTO2;
            case Proto3 ignored -> Descriptors.Syntax.PROTO3;
            case Edition e -> "2023".equals(e.name())
                    ? Descriptors.Syntax.EDITION_2023
                    : Descriptors.Syntax.EDITION_2023; // all future editions mapped here
        };
    }

    private static String join(String scope, String simple) {
        Objects.requireNonNull(simple, "simple");
        return scope == null || scope.isEmpty() ? simple : scope + "." + simple;
    }

    public static final class SchemaResolutionException extends RuntimeException {
        public SchemaResolutionException(String message) {
            super(message);
        }
    }
}
