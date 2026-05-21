package io.vidocq.champollion.protobuf.codegen;

import io.vidocq.champollion.protobuf.Descriptors;
import io.vidocq.champollion.protobuf.FieldType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Émetteur de code Java source à partir d'un {@link Descriptors.FileDescriptor}.
 *
 * <p>Un fichier {@code .java} est émis pour chaque {@link Descriptors.Descriptor}
 * top-level du file. Les messages et enums imbriqués sont rendus comme types
 * imbriqués Java (records statiques implicites). Chaque record implémente
 * {@code io.vidocq.champollion.protobuf.Message} et porte les annotations
 * {@code @ProtobufMessage(fullName)} et {@code @ProtobufField(number, type)}.</p>
 *
 * <p>Spec : <a href="https://protobuf.dev/reference/protobuf/proto3-spec/">Proto3 Language Spec</a>
 * et le contrat du runtime M1.3 ({@code Protobuf.parser(Class)}).</p>
 *
 * <p>Le code produit est volontairement compilable par {@code javac} standard
 * (pas de feature preview, pas d'extension propriétaire) et n'introduit aucune
 * dépendance externe au runtime Champollion.</p>
 */
public final class JavaEmitter {

    private final String javaPackage;

    /**
     * @param javaPackage Package Java dans lequel placer les sources émises.
     *                    Distinct du {@code package} proto (qui sert au fullName).
     */
    public JavaEmitter(String javaPackage) {
        this.javaPackage = Objects.requireNonNull(javaPackage, "javaPackage");
    }

    /**
     * Émet un {@code Map} {@code className → source} pour chaque type top-level
     * du file. La clé est le {@link Class#getName() FQN Java} ; la valeur est le
     * source complet (avec {@code package} et imports).
     */
    public Map<String, String> emit(Descriptors.FileDescriptor file) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Descriptors.Descriptor d : file.messageTypes()) {
            out.put(javaPackage + "." + d.name(), emitMessageFile(d));
        }
        for (Descriptors.EnumDescriptor e : file.enumTypes()) {
            out.put(javaPackage + "." + e.name(), emitEnumFile(e));
        }
        for (Descriptors.ServiceDescriptor s : file.services()) {
            out.put(javaPackage + "." + s.name(), emitServiceFile(s));
        }
        return out;
    }

    // ============================================================ Service

    private String emitServiceFile(Descriptors.ServiceDescriptor s) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("package ").append(javaPackage).append(";\n\n");
        sb.append("import io.vidocq.champollion.protobuf.ProtobufRpc;\n");
        sb.append("import io.vidocq.champollion.protobuf.ProtobufService;\n\n");
        sb.append("@ProtobufService(\"").append(s.fullName()).append("\")\n");
        sb.append("public interface ").append(s.name()).append(" {\n");
        for (Descriptors.MethodDescriptor m : s.methods()) {
            sb.append('\n');
            sb.append("    @ProtobufRpc(value = \"").append(m.name()).append('"');
            if (m.clientStreaming()) sb.append(", clientStreaming = true");
            if (m.serverStreaming()) sb.append(", serverStreaming = true");
            sb.append(")\n");
            String input = simpleNameForReference(m.inputType());
            String output = simpleNameForReference(m.outputType());
            // Unary uniquement pour M2.6. Le streaming est exposé via la
            // signature avec types byte[] (canal opaque) en attendant un
            // type d'abstraction stable (Flow.Publisher en M2.7).
            if (m.isUnary()) {
                sb.append("    ").append(output).append(' ')
                        .append(decapitalize(m.name())).append("(")
                        .append(input).append(" request);\n");
            } else {
                sb.append("    // TODO M2.7 — streaming (clientStreaming=")
                        .append(m.clientStreaming()).append(", serverStreaming=")
                        .append(m.serverStreaming()).append(")\n");
                sb.append("    java.util.concurrent.Flow.Publisher<byte[]> ")
                        .append(decapitalize(m.name())).append("(")
                        .append("java.util.concurrent.Flow.Publisher<byte[]> request);\n");
            }
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static String decapitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    // ============================================================ Message

    private String emitMessageFile(Descriptors.Descriptor d) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("package ").append(javaPackage).append(";\n\n");
        sb.append("import io.vidocq.champollion.protobuf.FieldType;\n");
        sb.append("import io.vidocq.champollion.protobuf.Message;\n");
        sb.append("import io.vidocq.champollion.protobuf.ProtobufField;\n");
        sb.append("import io.vidocq.champollion.protobuf.ProtobufMessage;\n");
        sb.append("import java.util.List;\n\n");
        emitMessageType(sb, d, "");
        return sb.toString();
    }

    private void emitMessageType(StringBuilder sb, Descriptors.Descriptor d, String indent) {
        sb.append(indent).append("@ProtobufMessage(\"").append(d.fullName()).append("\")\n");
        sb.append(indent).append("public record ").append(d.name()).append("(\n");
        for (int i = 0; i < d.fields().size(); i++) {
            Descriptors.FieldDescriptor f = d.fields().get(i);
            sb.append(indent).append("        @ProtobufField(number = ").append(f.number())
                    .append(", type = FieldType.").append(f.type().name());
            if (f.isRepeated() && f.type().packable() && !f.packed()) {
                sb.append(", packed = false");
            }
            sb.append(") ");
            sb.append(javaTypeOf(f)).append(' ').append(safeIdent(f.name()));
            if (i < d.fields().size() - 1) sb.append(',');
            sb.append('\n');
        }
        sb.append(indent).append(") implements Message {");
        if (d.nestedMessageTypes().isEmpty() && d.nestedEnumTypes().isEmpty()) {
            sb.append("}\n");
            return;
        }
        sb.append("\n\n");
        String childIndent = indent + "    ";
        for (Descriptors.Descriptor n : d.nestedMessageTypes()) {
            emitMessageType(sb, n, childIndent);
            sb.append('\n');
        }
        for (Descriptors.EnumDescriptor e : d.nestedEnumTypes()) {
            emitEnumType(sb, e, childIndent);
            sb.append('\n');
        }
        sb.append(indent).append("}\n");
    }

    // ============================================================ Enum

    private String emitEnumFile(Descriptors.EnumDescriptor e) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("package ").append(javaPackage).append(";\n\n");
        emitEnumType(sb, e, "");
        return sb.toString();
    }

    private void emitEnumType(StringBuilder sb, Descriptors.EnumDescriptor e, String indent) {
        sb.append(indent).append("public enum ").append(e.name()).append(" {\n");
        List<Descriptors.EnumValueDescriptor> values = e.values();
        for (int i = 0; i < values.size(); i++) {
            sb.append(indent).append("    ").append(values.get(i).name());
            if (i < values.size() - 1) sb.append(',');
            else sb.append(';');
            sb.append('\n');
        }
        sb.append(indent).append("}\n");
    }

    // ============================================================ Type mapping

    private String javaTypeOf(Descriptors.FieldDescriptor f) {
        String base = baseJavaType(f);
        return f.isRepeated() ? "List<" + boxIfPrimitive(base) + ">" : base;
    }

    private String baseJavaType(Descriptors.FieldDescriptor f) {
        return switch (f.type()) {
            case INT32, UINT32, SINT32, FIXED32, SFIXED32 -> "int";
            case INT64, UINT64, SINT64, FIXED64, SFIXED64 -> "long";
            case FLOAT -> "float";
            case DOUBLE -> "double";
            case BOOL -> "boolean";
            case STRING -> "String";
            case BYTES -> "byte[]";
            case ENUM -> f.enumTypeName() == null ? "Enum<?>" : simpleNameForReference(f.enumTypeName());
            case MESSAGE -> f.messageTypeName() == null ? "Object" : simpleNameForReference(f.messageTypeName());
        };
    }

    private static String boxIfPrimitive(String t) {
        return switch (t) {
            case "int" -> "Integer";
            case "long" -> "Long";
            case "float" -> "Float";
            case "double" -> "Double";
            case "boolean" -> "Boolean";
            default -> t;
        };
    }

    /**
     * Traduit un {@code fullName} de Descriptor en référence Java utilisable
     * dans le file courant : pour un type nested, l'utilisateur écrit
     * {@code Outer.Inner} ; pour un sibling top-level, juste {@code Sibling}.
     */
    private String simpleNameForReference(String fullName) {
        // Strip eventual package prefix (avant le premier majuscule).
        // La règle Proto3 : les fullNames sont {package}.{Type}.{Nested} ;
        // on garde la dernière séquence de noms commençant par majuscule.
        String[] segs = fullName.split("\\.");
        int start = 0;
        for (int i = 0; i < segs.length; i++) {
            if (!segs[i].isEmpty() && Character.isUpperCase(segs[i].charAt(0))) {
                start = i;
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < segs.length; i++) {
            if (sb.length() > 0) sb.append('.');
            sb.append(segs[i]);
        }
        return sb.toString();
    }

    /** Mots-clés Java réservés : on suffixe d'un underscore. */
    private static String safeIdent(String name) {
        return switch (name) {
            case "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
                 "class", "const", "continue", "default", "do", "double", "else", "enum",
                 "extends", "final", "finally", "float", "for", "goto", "if", "implements",
                 "import", "instanceof", "int", "interface", "long", "native", "new", "package",
                 "private", "protected", "public", "return", "short", "static", "strictfp",
                 "super", "switch", "synchronized", "this", "throw", "throws", "transient",
                 "try", "void", "volatile", "while", "true", "false", "null", "record",
                 "sealed", "permits", "non-sealed", "yield"
                    -> name + "_";
            default -> name;
        };
    }
}
