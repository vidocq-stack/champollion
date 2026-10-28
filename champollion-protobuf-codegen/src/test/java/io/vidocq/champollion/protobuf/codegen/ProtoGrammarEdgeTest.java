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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.champollion.protobuf.codegen.ProtoAst.EnumDecl;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.FieldDecl;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.FieldKind;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.MessageDecl;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.MethodDecl;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.NamedType;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.ProtoFile;
import io.vidocq.champollion.protobuf.codegen.internal.ProtoLexer;
import io.vidocq.champollion.protobuf.codegen.internal.ProtoLexer.Token;
import io.vidocq.champollion.protobuf.codegen.internal.ProtoLexer.TokenKind;
import io.vidocq.champollion.protobuf.codegen.internal.ProtoParser;
import io.vidocq.champollion.protobuf.codegen.internal.ProtoSyntaxException;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The lexical and syntactic corners of the {@code .proto} grammar the code generator reads. */
class ProtoGrammarEdgeTest {

    private static final String FILE = "edge.proto";

    private static List<Token> lex(String src) {
        return new ProtoLexer(src).tokenize();
    }

    @Test
    void every_punctuation_and_literal_kind_is_recognised() {
        List<TokenKind> kinds = lex("a = 0x1F, -2 . ; { } [ ] ( ) \"s\" 'q'").stream().map(Token::kind).toList();
        assertEquals(List.of(TokenKind.IDENT, TokenKind.EQ, TokenKind.INT_LITERAL, TokenKind.COMMA, TokenKind.MINUS,
                TokenKind.INT_LITERAL, TokenKind.DOT, TokenKind.SEMI, TokenKind.LBRACE, TokenKind.RBRACE,
                TokenKind.LBRACK, TokenKind.RBRACK, TokenKind.LPAREN, TokenKind.RPAREN, TokenKind.STRING_LITERAL,
                TokenKind.STRING_LITERAL, TokenKind.EOF), kinds);
        assertEquals("0x1F", lex("0x1F").get(0).text());
    }

    @Test
    void string_escapes_are_decoded() {
        Token t = lex("\"a\\nb\\tc\\rd\\\\e\\\"f\\'g\\zh\"").get(0);
        assertEquals("a\nb\tc\rd\\e\"f'g\\zh", t.text());
    }

    @Test
    void a_string_may_span_lines_and_end_without_its_quote() {
        List<Token> tokens = lex("'one\ntwo");
        assertEquals("one\ntwo", tokens.get(0).text());
        assertEquals(2, tokens.get(1).line());
    }

    @Test
    void comments_and_whitespace_are_skipped_and_lines_counted() {
        List<Token> tokens = lex("// line\n\t/* block\n over lines */ x\r\n");
        assertEquals("x", tokens.get(0).text());
        assertEquals(3, tokens.get(0).line());
        assertEquals(TokenKind.EOF, tokens.get(1).kind());
        assertEquals("IDENT(x)@3:16", tokens.get(0).toString());
    }

    @Test
    void an_unexpected_character_is_reported_with_its_position() {
        ProtoSyntaxException e = assertThrows(ProtoSyntaxException.class, () -> lex("a\n  #"));
        assertTrue(e.getMessage().contains("'#' at 2:3"), e.getMessage());
    }

    @Test
    void services_accept_streams_options_bodies_and_qualified_types() {
        ProtoFile f = ProtoParser.parse(FILE, """
                syntax = "proto3";
                package demo;
                service Chat {
                  option deprecated = true;
                  ;
                  rpc Talk(stream .demo.Line) returns (stream demo.Line) { option idempotency_level = NO_SIDE_EFFECTS; }
                  rpc Ping(Line) returns (Line);
                }
                message Line { string text = 1; }
                """);
        List<MethodDecl> methods = f.services().get(0).methods();
        assertEquals(".demo.Line", methods.get(0).inputType());
        assertEquals("demo.Line", methods.get(0).outputType());
        assertTrue(methods.get(0).clientStreaming());
        assertTrue(methods.get(0).serverStreaming());
        assertFalse(methods.get(1).clientStreaming());
    }

    @Test
    void imports_options_and_editions_are_read() {
        ProtoFile f = ProtoParser.parse(FILE, """
                edition = "2023";
                import public "a.proto";
                import weak "b.proto";
                import "c.proto";
                option (my.ext) = 42;
                option java_package = "x.y";
                ;
                """);
        assertInstanceOf(ProtoAst.Edition.class, f.syntax());
        assertTrue(f.imports().get(0).publicImport());
        assertTrue(f.imports().get(1).weakImport());
        assertFalse(f.imports().get(2).publicImport());
        assertEquals("(my.ext)", f.options().get(0).name());
        assertEquals("42", f.options().get(0).value());
    }

    @Test
    void a_file_without_syntax_is_proto2() {
        assertInstanceOf(ProtoAst.Proto2.class, ProtoParser.parse(FILE, "message M {}").syntax());
    }

    @Test
    void message_bodies_skip_what_the_generator_does_not_map() {
        ProtoFile f = ProtoParser.parse(FILE, """
                syntax = "proto2";
                message M {
                  reserved 2, 3;
                  extensions 100 to 200;
                  option message_set_wire_format = false;
                  oneof choice { string a = 4; int32 b = 5; }
                  ;
                  required int32 id = 1 [default = 7];
                  optional .other.Type ref = 7;
                  repeated other.Type refs = 8;
                }
                enum E {
                  option allow_alias = true;
                  reserved 9;
                  ;
                  ZERO = 0;
                  NEG = -1 [deprecated = true];
                  HEX = 0x10;
                }
                """);
        MessageDecl m = f.messages().get(0);
        List<String> names = m.fields().stream().map(FieldDecl::name).toList();
        assertEquals(List.of("id", "ref", "refs"), names);
        assertEquals(FieldKind.SINGULAR, m.fields().get(0).kind());
        assertEquals(new NamedType(".other.Type"), m.fields().get(1).type());
        assertEquals(FieldKind.REPEATED, m.fields().get(2).kind());
        EnumDecl e = f.enums().get(0);
        assertEquals(List.of(0, -1, 16), e.values().stream().map(ProtoAst.EnumValueDecl::number).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto4\";",
            "frobnicate x;",
            "= 1;",
            "service S { rpc A(B) gives (C); }",
            "service S { 1 }",
            "service S { stream x; }",
            "message M { = 1; }",
            "enum E { 1 = 2; }",
            "option x = { };",
            "message M { int32 a 1; }"
    })
    void malformed_declarations_are_rejected(String src) {
        assertThrows(ProtoSyntaxException.class, () -> ProtoParser.parse(FILE, src));
    }
}
