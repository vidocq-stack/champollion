package io.vidocq.champollion.protobuf.codegen;

import io.vidocq.champollion.protobuf.Descriptors;
import io.vidocq.champollion.protobuf.FieldType;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.EnumDecl;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.EnumValueDecl;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.Edition;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.FieldDecl;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.FieldKind;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.MessageDecl;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.NamedType;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.Proto2;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.Proto3;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.ProtoFile;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.Scalar;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.ScalarType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Résolveur sémantique : convertit un {@link ProtoFile} (AST syntaxique
 * produit par {@link io.vidocq.champollion.protobuf.codegen.internal.ProtoParser})
 * en {@link Descriptors.FileDescriptor} (modèle réflexif stable).
 *
 * <p>Spec : <a href="https://protobuf.dev/reference/protobuf/proto3-spec/#identifiers">Proto3 §Type Lookup</a>.</p>
 *
 * <p>Règles de lookup proto3 / Editions appliquées :</p>
 * <ul>
 *   <li>nom commençant par {@code .} → fully-qualified depuis la racine
 *       (ex. {@code .google.protobuf.Timestamp})</li>
 *   <li>nom non-qualifié → cherché de la portée locale (message courant,
 *       puis enclosing messages, puis fichier, puis imports)</li>
 *   <li>conflit entre {@link MessageDecl} et {@link EnumDecl} interdit</li>
 * </ul>
 *
 * <p>Les imports ne sont pas suivis en M2.2 : seuls les types définis dans
 * le {@link ProtoFile} courant sont résolus. Le multi-file resolver viendra
 * avec M2.3 ({@link SchemaResolver#resolve(List)}).</p>
 */
public final class SchemaResolver {

    private SchemaResolver() {}

    /** Résout un seul fichier (pas de cross-file imports). */
    public static Descriptors.FileDescriptor resolve(ProtoFile file) {
        Map<String, Symbol> table = new LinkedHashMap<>();
        String pkg = file.packageName();
        for (MessageDecl m : file.messages()) indexMessage(m, pkg, table);
        for (EnumDecl e : file.enums()) indexEnum(e, pkg, table);

        List<Descriptors.Descriptor> outMsgs = new ArrayList<>();
        for (MessageDecl m : file.messages()) outMsgs.add(resolveMessage(m, pkg, table));
        List<Descriptors.EnumDescriptor> outEnums = new ArrayList<>();
        for (EnumDecl e : file.enums()) outEnums.add(toEnumDescriptor(e, pkg));

        return new Descriptors.FileDescriptor(
                file.fileName(),
                pkg,
                toSyntax(file.syntax()),
                outMsgs,
                outEnums);
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
        // Recherche locale → puis remontée des scopes parents → racine
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

    private static Descriptors.Descriptor resolveMessage(MessageDecl m, String scope, Map<String, Symbol> table) {
        String full = join(scope, m.name());
        List<Descriptors.FieldDescriptor> fields = new ArrayList<>();
        for (FieldDecl f : m.fields()) fields.add(resolveField(f, full, table));
        List<Descriptors.Descriptor> nested = new ArrayList<>();
        for (MessageDecl n : m.nestedMessages()) nested.add(resolveMessage(n, full, table));
        List<Descriptors.EnumDescriptor> nestedE = new ArrayList<>();
        for (EnumDecl e : m.nestedEnums()) nestedE.add(toEnumDescriptor(e, full));
        return new Descriptors.Descriptor(m.name(), full, fields, nested, nestedE);
    }

    private static Descriptors.FieldDescriptor resolveField(FieldDecl f, String scope, Map<String, Symbol> table) {
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

        boolean packed = card == Descriptors.Cardinality.REPEATED && type.packable();
        return new Descriptors.FieldDescriptor(
                f.name(),
                Descriptors.toJsonName(f.name()),
                f.number(),
                type,
                card,
                packed,
                messageTypeName,
                enumTypeName);
    }

    private static String canonicalFullName(String scope, String simpleName,
                                            String referenceUsed, Map<String, Symbol> table) {
        // Re-fait le lookup pour retrouver le full name exact dans la table.
        if (referenceUsed.startsWith(".")) return referenceUsed.substring(1);
        String s = scope;
        while (true) {
            String candidate = join(s, referenceUsed);
            if (table.containsKey(candidate)) return candidate;
            if (s.isEmpty()) break;
            int dot = s.lastIndexOf('.');
            s = (dot < 0) ? "" : s.substring(0, dot);
        }
        // Fallback : nom simple
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
                    : Descriptors.Syntax.EDITION_2023; // toutes les éditions à venir mappées ici
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
