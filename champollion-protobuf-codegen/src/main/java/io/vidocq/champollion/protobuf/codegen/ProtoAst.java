package io.vidocq.champollion.protobuf.codegen;

import java.util.List;
import java.util.Objects;

/**
 * AST minimal d'un fichier {@code .proto} (proto3 / Editions 2023).
 *
 * <p>Spec : <a href="https://protobuf.dev/reference/protobuf/proto3-spec/">Proto3 Language Spec</a>
 * et <a href="https://protobuf.dev/editions/spec/">Editions Spec</a>.</p>
 *
 * <p>M2.1 — couvre {@code syntax} / {@code edition}, {@code package},
 * {@code import}, {@code message}, scalaires, {@code repeated}, nested message
 * et {@code enum}. {@code service}, {@code oneof}, {@code map}, options
 * complexes, et extensions reportés à M2.2/M2.3.</p>
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

    /** Path d'un import. {@link #publicImport()} = {@code import public "x.proto";}. */
    public record ImportDecl(String path, boolean publicImport, boolean weakImport) {}

    /** Une option simple key = value (string ou identifier ou number). */
    public record OptionEntry(String name, String value) {}

    /** Définition d'un champ dans un message. */
    public record FieldDecl(
            String name,
            int number,
            FieldKind kind,
            FieldTypeRef type) {}

    public enum FieldKind { SINGULAR, OPTIONAL, REPEATED }

    /**
     * Référence à un type. Soit un scalaire ({@code int32}, {@code string}, etc.),
     * soit un identifiant (référence à un autre message/enum dans le même fichier
     * ou un fichier importé). Le résolveur sémantique (M2.2) liera ces refs
     * vers des {@link MessageDecl} / {@link EnumDecl}.
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
            List<EnumDecl> nestedEnums) {

        public MessageDecl {
            fields = List.copyOf(fields);
            nestedMessages = List.copyOf(nestedMessages);
            nestedEnums = List.copyOf(nestedEnums);
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
