package io.vidocq.champollion.protobuf.codegen.internal;

import io.vidocq.champollion.protobuf.codegen.ProtoAst;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.EnumDecl;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.EnumValueDecl;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.FieldDecl;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.FieldKind;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.FieldTypeRef;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.ImportDecl;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.MessageDecl;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.NamedType;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.OptionEntry;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.ProtoFile;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.Scalar;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.ScalarType;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.Syntax;
import io.vidocq.champollion.protobuf.codegen.internal.ProtoLexer.Token;
import io.vidocq.champollion.protobuf.codegen.internal.ProtoLexer.TokenKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parser .proto (proto3 / Editions 2023) — récursif descendant.
 *
 * <p>Spec : <a href="https://protobuf.dev/reference/protobuf/proto3-spec/">Proto3 Language Spec</a>.</p>
 */
public final class ProtoParser {

    private static final Map<String, Scalar> SCALARS = Map.ofEntries(
            Map.entry("double", Scalar.DOUBLE), Map.entry("float", Scalar.FLOAT),
            Map.entry("int32", Scalar.INT32), Map.entry("int64", Scalar.INT64),
            Map.entry("uint32", Scalar.UINT32), Map.entry("uint64", Scalar.UINT64),
            Map.entry("sint32", Scalar.SINT32), Map.entry("sint64", Scalar.SINT64),
            Map.entry("fixed32", Scalar.FIXED32), Map.entry("fixed64", Scalar.FIXED64),
            Map.entry("sfixed32", Scalar.SFIXED32), Map.entry("sfixed64", Scalar.SFIXED64),
            Map.entry("bool", Scalar.BOOL), Map.entry("string", Scalar.STRING),
            Map.entry("bytes", Scalar.BYTES));

    private final String fileName;
    private final List<Token> tokens;
    private int idx = 0;

    public ProtoParser(String fileName, String source) {
        this.fileName = fileName;
        this.tokens = new ProtoLexer(source).tokenize();
    }

    public static ProtoFile parse(String fileName, String source) {
        return new ProtoParser(fileName, source).parseFile();
    }

    public ProtoFile parseFile() {
        Syntax syntax = parseSyntaxOrEdition();
        String packageName = "";
        List<ImportDecl> imports = new ArrayList<>();
        List<MessageDecl> messages = new ArrayList<>();
        List<EnumDecl> enums = new ArrayList<>();
        List<OptionEntry> options = new ArrayList<>();

        while (peek().kind() != TokenKind.EOF) {
            Token t = peek();
            if (t.kind() == TokenKind.IDENT) {
                switch (t.text()) {
                    case "package" -> packageName = parsePackage();
                    case "import" -> imports.add(parseImport());
                    case "option" -> options.add(parseOption());
                    case "message" -> messages.add(parseMessage());
                    case "enum" -> enums.add(parseEnum());
                    case "service" -> skipBlock("service");
                    default -> throw err("Unexpected top-level identifier '" + t.text() + "'", t);
                }
            } else if (t.kind() == TokenKind.SEMI) {
                consume();
            } else {
                throw err("Unexpected token at top-level", t);
            }
        }
        return new ProtoFile(fileName, syntax, packageName, imports, messages, enums, options);
    }

    private Syntax parseSyntaxOrEdition() {
        Token first = peek();
        if (first.kind() == TokenKind.IDENT && first.text().equals("syntax")) {
            consume();
            expect(TokenKind.EQ);
            Token val = expect(TokenKind.STRING_LITERAL);
            expect(TokenKind.SEMI);
            return switch (val.text()) {
                case "proto2" -> new ProtoAst.Proto2();
                case "proto3" -> new ProtoAst.Proto3();
                default -> throw err("Unknown syntax '" + val.text() + "'", val);
            };
        }
        if (first.kind() == TokenKind.IDENT && first.text().equals("edition")) {
            consume();
            expect(TokenKind.EQ);
            Token val = expect(TokenKind.STRING_LITERAL);
            expect(TokenKind.SEMI);
            return new ProtoAst.Edition(val.text());
        }
        // Spec : proto2 par défaut si aucun syntax explicite.
        return new ProtoAst.Proto2();
    }

    private String parsePackage() {
        consume(); // 'package'
        String fullName = parseFullIdent();
        expect(TokenKind.SEMI);
        return fullName;
    }

    private ImportDecl parseImport() {
        consume(); // 'import'
        boolean pub = false, weak = false;
        if (peek().kind() == TokenKind.IDENT && peek().text().equals("public")) {
            consume(); pub = true;
        } else if (peek().kind() == TokenKind.IDENT && peek().text().equals("weak")) {
            consume(); weak = true;
        }
        Token path = expect(TokenKind.STRING_LITERAL);
        expect(TokenKind.SEMI);
        return new ImportDecl(path.text(), pub, weak);
    }

    private OptionEntry parseOption() {
        consume(); // 'option'
        // Skip eventual paren around option name : option (foo.bar) = baz;
        StringBuilder name = new StringBuilder();
        if (peek().kind() == TokenKind.LPAREN) {
            consume();
            name.append('(').append(parseFullIdent()).append(')');
            expect(TokenKind.RPAREN);
        } else {
            name.append(parseFullIdent());
        }
        expect(TokenKind.EQ);
        String value;
        Token v = consume();
        if (v.kind() == TokenKind.STRING_LITERAL || v.kind() == TokenKind.IDENT || v.kind() == TokenKind.INT_LITERAL) {
            value = v.text();
        } else {
            throw err("Unsupported option value type", v);
        }
        expect(TokenKind.SEMI);
        return new OptionEntry(name.toString(), value);
    }

    private MessageDecl parseMessage() {
        consume(); // 'message'
        Token name = expect(TokenKind.IDENT);
        expect(TokenKind.LBRACE);
        List<FieldDecl> fields = new ArrayList<>();
        List<MessageDecl> nested = new ArrayList<>();
        List<EnumDecl> nestedEnums = new ArrayList<>();
        while (peek().kind() != TokenKind.RBRACE && peek().kind() != TokenKind.EOF) {
            Token t = peek();
            if (t.kind() == TokenKind.SEMI) { consume(); continue; }
            if (t.kind() == TokenKind.DOT) {
                // Champ dont le type est fully-qualified (.pkg.Type).
                fields.add(parseField());
                continue;
            }
            if (t.kind() == TokenKind.IDENT) {
                switch (t.text()) {
                    case "message" -> nested.add(parseMessage());
                    case "enum" -> nestedEnums.add(parseEnum());
                    case "reserved" -> skipUntilSemi();
                    case "extensions" -> skipUntilSemi();
                    case "option" -> parseOption();
                    case "oneof" -> skipBlock("oneof"); // M2.1 : ignore oneof
                    case "map" -> skipUntilSemi();      // M2.1 : ignore map
                    default -> fields.add(parseField());
                }
            } else {
                throw err("Unexpected token inside message", t);
            }
        }
        expect(TokenKind.RBRACE);
        return new MessageDecl(name.text(), fields, nested, nestedEnums);
    }

    private FieldDecl parseField() {
        FieldKind kind = FieldKind.SINGULAR;
        Token first = peek();
        if (first.kind() == TokenKind.IDENT) {
            if (first.text().equals("repeated")) { kind = FieldKind.REPEATED; consume(); }
            else if (first.text().equals("optional")) { kind = FieldKind.OPTIONAL; consume(); }
            else if (first.text().equals("required")) { /* proto2 legacy */ kind = FieldKind.SINGULAR; consume(); }
        }
        FieldTypeRef type = parseType();
        Token name = expect(TokenKind.IDENT);
        expect(TokenKind.EQ);
        int number = parseInt();
        // Skip eventuelles options inline [packed=true, default=...]
        if (peek().kind() == TokenKind.LBRACK) {
            skipBalanced(TokenKind.LBRACK, TokenKind.RBRACK);
        }
        expect(TokenKind.SEMI);
        return new FieldDecl(name.text(), number, kind, type);
    }

    private FieldTypeRef parseType() {
        // Leading dot pour fully-qualified : .google.protobuf.Timestamp
        boolean leading = peek().kind() == TokenKind.DOT;
        if (leading) consume();
        Token t = expect(TokenKind.IDENT);
        // Un type scalaire ne peut pas avoir de leading dot.
        if (!leading) {
            Scalar scalar = SCALARS.get(t.text());
            if (scalar != null) return new ScalarType(scalar);
        }
        StringBuilder sb = new StringBuilder();
        if (leading) sb.append('.');
        sb.append(t.text());
        while (peek().kind() == TokenKind.DOT) {
            consume();
            Token next = expect(TokenKind.IDENT);
            sb.append('.').append(next.text());
        }
        return new NamedType(sb.toString());
    }

    private EnumDecl parseEnum() {
        consume(); // 'enum'
        Token name = expect(TokenKind.IDENT);
        expect(TokenKind.LBRACE);
        List<EnumValueDecl> values = new ArrayList<>();
        while (peek().kind() != TokenKind.RBRACE && peek().kind() != TokenKind.EOF) {
            Token t = peek();
            if (t.kind() == TokenKind.SEMI) { consume(); continue; }
            if (t.kind() == TokenKind.IDENT) {
                if (t.text().equals("option")) { parseOption(); continue; }
                if (t.text().equals("reserved")) { skipUntilSemi(); continue; }
                Token vname = consume();
                expect(TokenKind.EQ);
                int num = parseInt();
                if (peek().kind() == TokenKind.LBRACK) skipBalanced(TokenKind.LBRACK, TokenKind.RBRACK);
                expect(TokenKind.SEMI);
                values.add(new EnumValueDecl(vname.text(), num));
            } else {
                throw err("Unexpected token inside enum", t);
            }
        }
        expect(TokenKind.RBRACE);
        return new EnumDecl(name.text(), values);
    }

    private int parseInt() {
        boolean negative = false;
        if (peek().kind() == TokenKind.MINUS) { consume(); negative = true; }
        Token t = expect(TokenKind.INT_LITERAL);
        int value;
        String text = t.text();
        if (text.startsWith("0x") || text.startsWith("0X")) {
            value = Integer.parseInt(text.substring(2), 16);
        } else {
            value = Integer.parseInt(text);
        }
        return negative ? -value : value;
    }

    private String parseFullIdent() {
        StringBuilder sb = new StringBuilder();
        sb.append(expect(TokenKind.IDENT).text());
        while (peek().kind() == TokenKind.DOT) {
            consume();
            sb.append('.').append(expect(TokenKind.IDENT).text());
        }
        return sb.toString();
    }

    private void skipUntilSemi() {
        while (peek().kind() != TokenKind.SEMI && peek().kind() != TokenKind.EOF) consume();
        if (peek().kind() == TokenKind.SEMI) consume();
    }

    private void skipBlock(String label) {
        consume(); // mot-clé
        // Optionally consume name
        if (peek().kind() == TokenKind.IDENT) consume();
        skipBalanced(TokenKind.LBRACE, TokenKind.RBRACE);
    }

    private void skipBalanced(TokenKind open, TokenKind close) {
        expect(open);
        int depth = 1;
        while (depth > 0 && peek().kind() != TokenKind.EOF) {
            TokenKind k = consume().kind();
            if (k == open) depth++;
            else if (k == close) depth--;
        }
    }

    private Token peek() { return tokens.get(idx); }

    private Token consume() { return tokens.get(idx++); }

    private Token expect(TokenKind kind) {
        Token t = peek();
        if (t.kind() != kind) {
            throw err("Expected " + kind + " but got " + t.kind() + " ('" + t.text() + "')", t);
        }
        return consume();
    }

    private ProtoSyntaxException err(String msg, Token t) {
        return new ProtoSyntaxException(fileName + ":" + t.line() + ":" + t.column() + " — " + msg);
    }
}
