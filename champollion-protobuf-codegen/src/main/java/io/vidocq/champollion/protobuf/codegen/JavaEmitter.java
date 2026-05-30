package io.vidocq.champollion.protobuf.codegen;

import io.vidocq.champollion.protobuf.Descriptors;
import io.vidocq.champollion.protobuf.FieldType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Java source code emitter from a {@link Descriptors.FileDescriptor}.
 *
 * <p>A {@code .java} file is emitted for each top-level {@link Descriptors.Descriptor}
 * in the file. Nested messages and enums are rendered as nested Java types
 * (implicit static records). Each record implements
 * {@code io.vidocq.champollion.protobuf.Message} and carries the annotations
 * {@code @ProtobufMessage(fullName)} and {@code @ProtobufField(number, type)}.</p>
 *
 * <p>Spec: <a href="https://protobuf.dev/reference/protobuf/proto3-spec/">Proto3 Language Spec</a>
 * and the M1.3 runtime contract ({@code Protobuf.parser(Class)}).</p>
 *
 * <p>The generated code is intentionally compilable by standard {@code javac}
 * (no preview feature, no proprietary extension) and introduces no external
 * dependency on the Champollion runtime.</p>
 */
public final class JavaEmitter {

    private final String javaPackage;
    private final boolean staticParser;

    /**
     * @param javaPackage Java package in which to place the emitted sources.
     *                    Distinct from the proto {@code package} (used for the fullName).
     */
    public JavaEmitter(String javaPackage) {
        this(javaPackage, false);
    }

    /**
     * @param javaPackage  Target Java package for the emitted sources.
     * @param staticParser If {@code true}, each emitted record also carries
     *                     {@code @ProtobufStatic} — the M3.1 APT will then
     *                     produce a zero-reflection, ServiceLoader-discoverable parser.
     */
    public JavaEmitter(String javaPackage, boolean staticParser) {
        this.javaPackage = Objects.requireNonNull(javaPackage, "javaPackage");
        this.staticParser = staticParser;
    }

    /**
     * Emits a {@code Map} {@code className → source} for each top-level type
     * in the file. The key is the {@link Class#getName() Java FQN}; the value is the
     * full source (including {@code package} and imports).
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
            // Unary only for M2.6. Streaming is exposed through the
            // signature with byte[] types (opaque channel) while waiting for a
            // stable abstraction type (Flow.Publisher in M2.7).
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
        if (staticParser) {
            sb.append("import io.vidocq.champollion.protobuf.ProtobufStatic;\n");
        }
        sb.append("import java.util.List;\n\n");
        emitMessageType(sb, d, "");
        return sb.toString();
    }

    private void emitMessageType(StringBuilder sb, Descriptors.Descriptor d, String indent) {
        if (staticParser) {
            sb.append(indent).append("@ProtobufStatic\n");
        }
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
            case MAP -> "java.util.Map<Object,Object>";
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
     * Translates a {@code fullName} descriptor into a Java reference usable
     * in the current file: for a nested type, the user writes
     * {@code Outer.Inner}; for a top-level sibling, just {@code Sibling}.
     */
    private String simpleNameForReference(String fullName) {
        // Strip any package prefix (before the first uppercase letter).
        // Proto3 rule: fullNames are {package}.{Type}.{Nested};
        // we keep the last sequence of names starting with an uppercase letter.
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

    /** Java reserved keywords: we suffix an underscore. */
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
