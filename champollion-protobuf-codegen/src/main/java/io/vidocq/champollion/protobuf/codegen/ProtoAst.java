package io.vidocq.champollion.protobuf.codegen;

import java.util.List;
import java.util.Objects;

/**
 * Minimal AST of a {@code .proto} file (proto3 / Editions 2023).
 *
 * <p>Spec: <a href="https://protobuf.dev/reference/protobuf/proto3-spec/">Proto3 Language Spec</a>
 * and <a href="https://protobuf.dev/editions/spec/">Editions Spec</a>.</p>
 *
 * <p>M2.1 — covers {@code syntax} / {@code edition}, {@code package},
 * {@code import}, {@code message}, scalars, {@code repeated}, nested message
 * and {@code enum}. {@code service}, {@code oneof}, {@code map}, complex
 * options, and extensions deferred to M2.2/M2.3.</p>
 */
public final class ProtoAst {

    private ProtoAst() {}

    public sealed interface Syntax permits Proto2, Proto3, Edition {}
    public record Proto2() implements Syntax {}
    public record Proto3() implements Syntax {}
    public record Edition(String name) implements Syntax {
        public Edition {
            Objects.requireNonNull(name, "name");
        }
    }

    /** An import path. {@link #publicImport()} = {@code import public "x.proto";}. */
    public record ImportDecl(String path, boolean publicImport, boolean weakImport) {}

    /** A simple key = value option (string, identifier, or number). */
    public record OptionEntry(String name, String value) {}

    /** Field definition in a message. */
    public record FieldDecl(
            String name,
            int number,
            FieldKind kind,
            FieldTypeRef type) {}

    public enum FieldKind { SINGULAR, OPTIONAL, REPEATED }

    /**
     * Type reference. Either a scalar ({@code int32}, {@code string}, etc.),
     * or an identifier (reference to another message/enum in the same file or
     * an imported file). The semantic resolver (M2.2) will bind these refs to
     * {@link MessageDecl} / {@link EnumDecl}.
     */
    public sealed interface FieldTypeRef permits ScalarType, NamedType {}

    public record ScalarType(Scalar scalar) implements FieldTypeRef {}

    public record NamedType(String fullName) implements FieldTypeRef {}

    public enum Scalar {
        DOUBLE, FLOAT,
        INT32, INT64, UINT32, UINT64, SINT32, SINT64,
        FIXED32, FIXED64, SFIXED32, SFIXED64,
        BOOL, STRING, BYTES
    }

    public record EnumValueDecl(String name, int number) {}

    public record EnumDecl(String name, List<EnumValueDecl> values) {
        public EnumDecl {
            values = List.copyOf(values);
        }
    }

    public record MessageDecl(
            String name,
            List<FieldDecl> fields,
            List<MessageDecl> nestedMessages,
            List<EnumDecl> nestedEnums,
            List<OptionEntry> options) {

        public MessageDecl {
            fields = List.copyOf(fields);
            nestedMessages = List.copyOf(nestedMessages);
            nestedEnums = List.copyOf(nestedEnums);
            options = List.copyOf(options);
        }

        /** Compatibility constructor (without options) — pre-M4.2 call sites. */
        public MessageDecl(String name, List<FieldDecl> fields,
                           List<MessageDecl> nestedMessages, List<EnumDecl> nestedEnums) {
            this(name, fields, nestedMessages, nestedEnums, List.of());
        }
    }

    public record ProtoFile(
            String fileName,
            Syntax syntax,
            String packageName,
            List<ImportDecl> imports,
            List<MessageDecl> messages,
            List<EnumDecl> enums,
            List<ServiceDecl> services,
            List<OptionEntry> options) {

        public ProtoFile {
            Objects.requireNonNull(syntax, "syntax");
            packageName = packageName == null ? "" : packageName;
            imports = List.copyOf(imports);
            messages = List.copyOf(messages);
            enums = List.copyOf(enums);
            services = List.copyOf(services);
            options = List.copyOf(options);
        }
    }

    /** {@code service Foo { rpc Bar(Req) returns (Resp); }}. */
    public record ServiceDecl(String name, List<MethodDecl> methods) {
        public ServiceDecl {
            methods = List.copyOf(methods);
        }
    }

    public record MethodDecl(
            String name,
            String inputType,
            String outputType,
            boolean clientStreaming,
            boolean serverStreaming) {}
}
