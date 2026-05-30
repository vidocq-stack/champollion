package io.vidocq.champollion.protobuf.codegen.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * Lexer .proto (proto3 / Editions 2023).
 *
 * <p>Spec : <a href="https://protobuf.dev/reference/protobuf/proto3-spec/#lexical_elements">Proto3 §Lexical Elements</a>.</p>
 *
 * <p>Recognizes:</p>
 * <ul>
 *   <li>identifiers : {@code [A-Za-z_][A-Za-z0-9_]*}</li>
 *   <li>full identifiers : {@code Foo.Bar.Baz}</li>
 *   <li>integer literals: decimal, hex {@code 0x...}, octal {@code 0...}</li>
 *   <li>float literals: optional for M2.1</li>
 *   <li>string literals: {@code "..."} or {@code '...'} with basic escapes</li>
 *   <li>punctuation : {@code = ; { } [ ] ( ) , .}</li>
 *   <li>line comments {@code //...} and block comments {@code /* ... *}{@code /} are skipped silently</li>
 * </ul>
 */
public final class ProtoLexer {

    public enum TokenKind {
        IDENT, INT_LITERAL, STRING_LITERAL,
        EQ, SEMI, LBRACE, RBRACE, LBRACK, RBRACK, LPAREN, RPAREN, COMMA, DOT, MINUS,
        EOF
    }

    public record Token(TokenKind kind, String text, int line, int column) {
        @Override
        public String toString() {
            return kind + "(" + text + ")@" + line + ":" + column;
        }
    }

    private final String src;
    private int pos = 0;
    private int line = 1;
    private int col = 1;

    public ProtoLexer(String source) {
        this.src = source;
    }

    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        while (true) {
            skipWhitespaceAndComments();
            if (pos >= src.length()) {
                tokens.add(new Token(TokenKind.EOF, "", line, col));
                return tokens;
            }
            int startLine = line;
            int startCol = col;
            char c = src.charAt(pos);
            if (isIdentStart(c)) {
                tokens.add(readIdent(startLine, startCol));
            } else if (Character.isDigit(c)) {
                tokens.add(readIntLiteral(startLine, startCol));
            } else if (c == '"' || c == '\'') {
                tokens.add(readStringLiteral(startLine, startCol, c));
            } else {
                tokens.add(readPunct(startLine, startCol));
            }
        }
    }

    private void skipWhitespaceAndComments() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r') {
                advance();
            } else if (c == '\n') {
                pos++;
                line++;
                col = 1;
            } else if (c == '/' && pos + 1 < src.length() && src.charAt(pos + 1) == '/') {
                while (pos < src.length() && src.charAt(pos) != '\n') pos++;
            } else if (c == '/' && pos + 1 < src.length() && src.charAt(pos + 1) == '*') {
                pos += 2;
                col += 2;
                while (pos + 1 < src.length() && !(src.charAt(pos) == '*' && src.charAt(pos + 1) == '/')) {
                    if (src.charAt(pos) == '\n') { line++; col = 1; }
                    else col++;
                    pos++;
                }
                if (pos + 1 < src.length()) { pos += 2; col += 2; }
            } else {
                return;
            }
        }
    }

    private Token readIdent(int startLine, int startCol) {
        int start = pos;
        while (pos < src.length() && isIdentPart(src.charAt(pos))) advance();
        return new Token(TokenKind.IDENT, src.substring(start, pos), startLine, startCol);
    }

    private Token readIntLiteral(int startLine, int startCol) {
        int start = pos;
        // Hex detection 0x...
        if (src.charAt(pos) == '0' && pos + 1 < src.length()
                && (src.charAt(pos + 1) == 'x' || src.charAt(pos + 1) == 'X')) {
            advance(); advance();
            while (pos < src.length() && isHexDigit(src.charAt(pos))) advance();
        } else {
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) advance();
        }
        return new Token(TokenKind.INT_LITERAL, src.substring(start, pos), startLine, startCol);
    }

    private Token readStringLiteral(int startLine, int startCol, char quote) {
        advance(); // skip opening quote
        StringBuilder sb = new StringBuilder();
        while (pos < src.length() && src.charAt(pos) != quote) {
            char c = src.charAt(pos);
            if (c == '\\' && pos + 1 < src.length()) {
                char esc = src.charAt(pos + 1);
                switch (esc) {
                    case 'n' -> { sb.append('\n'); pos += 2; col += 2; }
                    case 't' -> { sb.append('\t'); pos += 2; col += 2; }
                    case 'r' -> { sb.append('\r'); pos += 2; col += 2; }
                    case '\\' -> { sb.append('\\'); pos += 2; col += 2; }
                    case '"' -> { sb.append('"'); pos += 2; col += 2; }
                    case '\'' -> { sb.append('\''); pos += 2; col += 2; }
                    default -> { sb.append(c); advance(); }
                }
            } else {
                sb.append(c);
                if (c == '\n') { pos++; line++; col = 1; }
                else advance();
            }
        }
        if (pos < src.length()) advance(); // closing quote
        return new Token(TokenKind.STRING_LITERAL, sb.toString(), startLine, startCol);
    }

    private Token readPunct(int startLine, int startCol) {
        char c = src.charAt(pos);
        advance();
        TokenKind kind = switch (c) {
            case '=' -> TokenKind.EQ;
            case ';' -> TokenKind.SEMI;
            case '{' -> TokenKind.LBRACE;
            case '}' -> TokenKind.RBRACE;
            case '[' -> TokenKind.LBRACK;
            case ']' -> TokenKind.RBRACK;
            case '(' -> TokenKind.LPAREN;
            case ')' -> TokenKind.RPAREN;
            case ',' -> TokenKind.COMMA;
            case '.' -> TokenKind.DOT;
            case '-' -> TokenKind.MINUS;
            default -> throw new ProtoSyntaxException(
                    "Unexpected character '" + c + "' at " + startLine + ":" + startCol);
        };
        return new Token(kind, String.valueOf(c), startLine, startCol);
    }

    private void advance() {
        pos++;
        col++;
    }

    private static boolean isIdentStart(char c) {
        return c == '_' || Character.isLetter(c);
    }

    private static boolean isIdentPart(char c) {
        return c == '_' || Character.isLetterOrDigit(c);
    }

    private static boolean isHexDigit(char c) {
        return Character.isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
