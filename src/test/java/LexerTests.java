import net.rusrusme.alpacac.AlpacaLexer;
import net.rusrusme.alpacac.AlpacaCompilerError;
import net.rusrusme.alpacac.Token;
import net.rusrusme.alpacac.TokenType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

import static net.rusrusme.alpacac.TokenType.*;
import static org.junit.jupiter.api.Assertions.*;

class LexerTests {

    private AlpacaLexer lexer;

    @BeforeEach
    void setup() {
        lexer = new AlpacaLexer();
    }

    // ============================================================
    //  Single-character tokens
    // ============================================================

    @Test
    @DisplayName("Tokenizes single-character punctuation")
    void testSingleCharTokens() {
        assertTokens("(){}[]", List.of(
                token(LPAR, "("),
                token(RPAR, ")"),
                token(LBRACE, "{"),
                token(RBRACE, "}"),
                token(LBRACKET, "["),
                token(RBRACKET, "]"),
                eof()
        ));
    }

    @Test
    @DisplayName("Tokenizes single-character operators and delimiters")
    void testSingleCharOperators() {
        assertTokens(",.:;*/-+@", List.of(
                token(COMMA, ","),
                token(DOT, "."),
                token(COLON, ":"),
                token(SEMICOLON, ";"),
                token(STAR, "*"),
                token(SLASH, "/"),
                token(MINUS, "-"),
                token(PLUS, "+"),
                token(AT, "@"),
                eof()
        ));
    }

    // ============================================================
    //  Multi-character / compound operators
    // ============================================================

    @Test
    @DisplayName("Tokenizes ==, !=, <=, >=")
    void testEqualityAndComparison() {
        assertTokens("== != <= >=", List.of(
                token(EQUALS_EQUALS, "=="),
                token(BANG_EQUALS, "!="),
                token(LESS_EQUALS, "<="),
                token(GREATER_EQUALS, ">="),
                eof()
        ));
    }

    @Test
    @DisplayName("Tokenizes single =, !, <, >")
    void testSingleEqualityAndComparison() {
        assertTokens("= ! < >", List.of(
                token(EQUALS, "="),
                token(BANG, "!"),
                token(LESS, "<"),
                token(GREATER, ">"),
                eof()
        ));
    }

    @Test
    @DisplayName("Tokenizes ++, --, &&, ||")
    void testCompoundOperators() {
        assertTokens("++ -- && ||", List.of(
                token(PLUS_PLUS, "++"),
                token(MINUS_MINUS, "--"),
                token(AND_AND, "&&"),
                token(OR_OR, "||"),
                eof()
        ));
    }

    @Test
    @DisplayName("Tokenizes arrow ->")
    void testArrow() {
        assertTokens("->", List.of(
                token(ARROW, "->"),
                eof()
        ));
    }

    // ============================================================
    //  Error cases for solo & / |
    // ============================================================

    @Test
    @DisplayName("Throws error on lone &")
    void testLoneAmpersand() {
        assertThrows(AlpacaCompilerError.class, () -> lexer.tokenize("&"));
    }

    @Test
    @DisplayName("Throws error on lone |")
    void testLonePipe() {
        assertThrows(AlpacaCompilerError.class, () -> lexer.tokenize("|"));
    }

    // ============================================================
    //  Comments
    // ============================================================

    @Test
    @DisplayName("Ignores # comments until end of line")
    void testComment() {
        assertTokens("# this is a comment\n123", List.of(
                token(INT_LITERAL, "123", 123),
                eof()
        ));
    }

    @Test
    @DisplayName("Ignores # comment at end of file without newline")
    void testCommentEof() {
        assertTokens("42 # answer", List.of(
                token(INT_LITERAL, "42", 42),
                eof()
        ));
    }

    // ============================================================
    //  Whitespace and newlines
    // ============================================================

    @Test
    @DisplayName("Handles empty source")
    void testEmptySource() {
        assertTokens("", List.of(eof()));
    }

    @Test
    @DisplayName("Handles null source as empty")
    void testNullSource() {
        assertTokens(null, List.of(eof()));
    }

    @Test
    @DisplayName("Ignores spaces, tabs, carriage returns")
    void testWhitespace() {
        assertTokens("  \t\r  ", List.of(eof()));
    }

    @Test
    @DisplayName("Tracks line numbers across newlines")
    void testLineTracking() {
        List<Token> tokens = lexer.tokenize("1\n2\n3");
        assertEquals(1, tokens.get(0).line());
        assertEquals(2, tokens.get(1).line());
        assertEquals(3, tokens.get(2).line());
        assertEquals(3, tokens.get(3).line()); // EOF
    }

    // ============================================================
    //  Identifiers & keywords
    // ============================================================

    @Test
    @DisplayName("Tokenizes identifiers")
    void testIdentifiers() {
        assertTokens("foo bar _tmp var42", List.of(
                token(IDENTIFIER, "foo"),
                token(IDENTIFIER, "bar"),
                token(IDENTIFIER, "_tmp"),
                token(IDENTIFIER, "var42"),
                eof()
        ));
    }

    @Test
    @DisplayName("Tokenizes all keywords")
    void testKeywords() {
        assertTokens("if else for while do return unsafe is object public private switch case default this", List.of(
                token(IF, "if"),
                token(ELSE, "else"),
                token(FOR, "for"),
                token(WHILE, "while"),
                token(DO, "do"),
                token(RETURN, "return"),
                token(UNSAFE, "unsafe"),
                token(IS, "is"),
                token(OBJECT, "object"),
                token(PUBLIC, "public"),
                token(PRIVATE, "private"),
                token(SWITCH, "switch"),
                token(CASE, "case"),
                token(DEFAULT, "default"),
                token(THIS, "this"),
                eof()
        ));
    }

    @Test
    @DisplayName("Tokenizes boolean literals with literal value")
    void testBoolLiterals() {
        List<Token> tokens = lexer.tokenize("true false");
        assertEquals(3, tokens.size());
        assertEquals(BOOL_LITERAL, tokens.get(0).type());
        assertEquals(Boolean.TRUE, tokens.get(0).literal());
        assertEquals(BOOL_LITERAL, tokens.get(1).type());
        assertEquals(Boolean.FALSE, tokens.get(1).literal());
        assertEquals(EOF, tokens.get(2).type());
    }

    // ============================================================
    //  Integer literals
    // ============================================================

    @Test
    @DisplayName("Tokenizes integer literals")
    void testIntegers() {
        assertTokens("0 42 007", List.of(
                token(INT_LITERAL, "0", 0),
                token(INT_LITERAL, "42", 42),
                token(INT_LITERAL, "007", 7),
                eof()
        ));
    }

    @Test
    @DisplayName("Throws error on integer literal out of range")
    void testIntegerOverflow() {
        assertThrows(AlpacaCompilerError.class,
                () -> lexer.tokenize("99999999999999999999999999999999999999999999999999999999999999999999999999999999999999999"));
    }

    // ============================================================
    //  Float literals
    // ============================================================

    @Test
    @DisplayName("Tokenizes float literals")
    void testFloats() {
        assertTokens("3.14 0.5 42.0", List.of(
                token(FLOAT_LITERAL, "3.14", 3.14f),
                token(FLOAT_LITERAL, "0.5", 0.5f),
                token(FLOAT_LITERAL, "42.0", 42.0f),
                eof()
        ));
    }

    @Test
    @DisplayName("Tokenizes integer followed by dot (member access), not float")
    void testIntegerDotNotFloat() {
        // 2.abc — dot not followed by digit, so it's integer then dot then identifier
        assertTokens("2.abc", List.of(
                token(INT_LITERAL, "2", 2),
                token(DOT, "."),
                token(IDENTIFIER, "abc"),
                eof()
        ));
    }

    @Test
    @DisplayName("Tokenizes float with trailing dot (e.g. 2.0)")
    void testFloatTrailingDot() {
        assertTokens("2.0", List.of(
                token(FLOAT_LITERAL, "2.0", 2.0f),
                eof()
        ));
    }

    @Test
        @DisplayName("Tokenizes out-of-range float as Infinity")
        void testFloatInfinity() {
            // Float.parseFloat returns Infinity for extremely large numbers, not an exception
            List<Token> tokens = lexer.tokenize("999999999999999999999999999999999999999999999999999.0");
            assertEquals(FLOAT_LITERAL, tokens.getFirst().type());
            assertEquals(Float.POSITIVE_INFINITY, tokens.getFirst().literal());
        }

    // ============================================================
    //  String literals
    // ============================================================

    @Test
    @DisplayName("Tokenizes simple string literal")
    void testSimpleString() {
        assertTokens("\"hello\"", List.of(
                token(STRING_LITERAL, "\"hello\"", "hello"),
                eof()
        ));
    }

    @Test
    @DisplayName("Tokenizes empty string")
    void testEmptyString() {
        assertTokens("\"\"", List.of(
                token(STRING_LITERAL, "\"\"", ""),
                eof()
        ));
    }

    @Test
    @DisplayName("Tokenizes string with escape sequences")
    void testStringEscapes() {
        assertTokens("\"a\\nb\\tc\\r\\\\d\\\"e\"", List.of(
                token(STRING_LITERAL, "\"a\\nb\\tc\\r\\\\d\\\"e\"", "a\nb\tc\r\\d\"e"),
                eof()
        ));
    }

    @Test
    @DisplayName("Tokenizes string with newlines")
    void testStringNewline() {
        assertTokens("\"hello\nworld\"", List.of(
                token(STRING_LITERAL, "\"hello\nworld\"", "hello\nworld"),
                eof()
        ));
    }

    @Test
    @DisplayName("Throws error on unterminated string")
    void testUnterminatedString() {
        assertThrows(AlpacaCompilerError.class, () -> lexer.tokenize("\"hello"));
    }

    @Test
    @DisplayName("Throws error on invalid escape in string")
    void testInvalidStringEscape() {
        assertThrows(AlpacaCompilerError.class, () -> lexer.tokenize("\"\\x\""));
    }

    // ============================================================
    //  Character literals
    // ============================================================

    @Test
    @DisplayName("Tokenizes character literal")
    void testSimpleChar() {
        assertTokens("'x'", List.of(
                token(CHAR_LITERAL, "'x'", 'x'),
                eof()
        ));
    }

    @Test
    @DisplayName("Tokenizes char escape sequences")
    void testCharEscapes() {
        assertTokens("'\\n' '\\t' '\\r' '\\\\' '\\''", List.of(
                token(CHAR_LITERAL, "'\\n'", '\n'),
                token(CHAR_LITERAL, "'\\t'", '\t'),
                token(CHAR_LITERAL, "'\\r'", '\r'),
                token(CHAR_LITERAL, "'\\\\'", '\\'),
                token(CHAR_LITERAL, "'\\''", '\''),
                eof()
        ));
    }

    @Test
    @DisplayName("Throws error on empty char literal ''")
    void testEmptyChar() {
        assertThrows(AlpacaCompilerError.class, () -> lexer.tokenize("''"));
    }

    @Test
    @DisplayName("Throws error on unterminated char literal")
    void testUnterminatedChar() {
        assertThrows(AlpacaCompilerError.class, () -> lexer.tokenize("'a"));
    }

    @Test
    @DisplayName("Throws error on char literal with more than one char")
    void testTooLongChar() {
        // 'ab' — 'a' is consumed, peek is 'b' not '\'' -> unterminated
        assertThrows(AlpacaCompilerError.class, () -> lexer.tokenize("'ab'"));
    }

    @Test
    @DisplayName("Throws error on invalid escape in char")
    void testInvalidCharEscape() {
        assertThrows(AlpacaCompilerError.class, () -> lexer.tokenize("'\\z'"));
    }

    // ============================================================
    //  Mixed / integration
    // ============================================================

    @Test
    @DisplayName("Tokenizes a small program fragment")
    void testSmallProgram() {
        String src = """
                if (x == 42) {
                    return true;
                }
                """;
        assertTokens(src, List.of(
                token(IF, "if"),
                token(LPAR, "("),
                token(IDENTIFIER, "x"),
                token(EQUALS_EQUALS, "=="),
                token(INT_LITERAL, "42", 42),
                token(RPAR, ")"),
                token(LBRACE, "{"),
                token(RETURN, "return"),
                token(BOOL_LITERAL, "true", true),
                token(SEMICOLON, ";"),
                token(RBRACE, "}"),
                eof()
        ));
    }

    // ============================================================
    //  Error handling
    // ============================================================

    @Test
    @DisplayName("Throws error on unexpected character")
    void testUnexpectedChar() {
        assertThrows(AlpacaCompilerError.class, () -> lexer.tokenize("~"));
    }

    @Test
    @DisplayName("Throws error on lone & in expression")
    void testLoneAmpersandInExpression() {
        assertThrows(AlpacaCompilerError.class, () -> lexer.tokenize("if &"));
    }

    @Test
    @DisplayName("Throws error on lone | in expression")
    void testLonePipeInExpression() {
        assertThrows(AlpacaCompilerError.class, () -> lexer.tokenize("x | y"));
    }

    // ============================================================
    //  Helpers
    // ============================================================

    private static Token token(TokenType type, String lexeme) {
        return new Token(type, lexeme, null, 0, 0);
    }

    private static Token token(TokenType type, String lexeme, Object literal) {
        return new Token(type, lexeme, literal, 0, 0);
    }

    private static Token eof() {
        return new Token(EOF, "", null, 0, 0);
    }

    private void assertTokens(String source, List<Token> expected) {
        List<Token> actual = lexer.tokenize(source);
        assertEquals(expected.size(), actual.size(), "Token count mismatch for source: " + source);
        for (int i = 0; i < expected.size(); i++) {
            Token exp = expected.get(i);
            Token act = actual.get(i);
            assertEquals(exp.type(), act.type(), "Token type mismatch at index " + i);
            assertEquals(exp.lexeme(), act.lexeme(), "Lexeme mismatch at index " + i);
            assertEquals(exp.literal(), act.literal(), "Literal mismatch at index " + i);
        }
    }

    @Test
    @DisplayName("Tokenizes primitive type keywords")
    void testPrimitiveTypes() {
        assertTokens("void int unsigned long float double string char bool", List.of(
        token(VOID, "void"),
        token(INT, "int"),
        token(UNSIGNED, "unsigned"),
        token(LONG, "long"),
        token(FLOAT, "float"),
        token(DOUBLE, "double"),
        token(STRING, "string"),
        token(CHAR, "char"),
        token(BOOL, "bool"),
        eof()
        ));
    }

    @Test
    @DisplayName("Lexes int in declaration as INT keyword, not IDENTIFIER")
    void testIntInDeclaration() {
        // Previously 'int' was lexed as IDENTIFIER — now it should be INT
        assertTokens("public int eat()", List.of(
        token(PUBLIC, "public"),
        token(INT, "int"),
        token(IDENTIFIER, "eat"),
        token(LPAR, "("),
        token(RPAR, ")"),
        eof()
        ));
    }

    @Test
    @DisplayName("Tokenizes the modulo operator")
    void testPercentOperator() {
        assertTokens("5 % 2", List.of(
                token(INT_LITERAL, "5", 5),
                token(PERCENT, "%"),
                token(INT_LITERAL, "2", 2),
                eof()
        ));
    }

    @Test
    @DisplayName("Tokenizes += as two tokens (no compound assignment operator)")
    void testPlusEqualsIsTwoTokens() {
        assertTokens("x += 1", List.of(
                token(IDENTIFIER, "x"),
                token(PLUS, "+"),
                token(EQUALS, "="),
                token(INT_LITERAL, "1", 1),
                eof()
        ));
    }

    @Test
    @DisplayName("Tokenizes an integer directly followed by an identifier as two tokens")
    void testIntegerFollowedByIdentifier() {
        assertTokens("12abc", List.of(
                token(INT_LITERAL, "12", 12),
                token(IDENTIFIER, "abc"),
                eof()
        ));
    }

    @Test
    @DisplayName("Tokenizes a float followed by a dot as float then dot then integer")
    void testFloatThenDot() {
        assertTokens("1.2.3", List.of(
                token(FLOAT_LITERAL, "1.2", 1.2f),
                token(DOT, "."),
                token(INT_LITERAL, "3", 3),
                eof()
        ));
    }

    @Test
    @DisplayName("Tracks columns across tokens and newlines")
    void testColumnTracking() {
        List<Token> tokens = lexer.tokenize("a  b\n  c");
        assertEquals(1, tokens.get(0).column());
        assertEquals(4, tokens.get(1).column());
        assertEquals(3, tokens.get(2).column());
        assertEquals(3, tokens.get(3).column()); // EOF
    }

    @Test
    @DisplayName("Throws error on various unexpected characters")
    void testMoreUnexpectedChars() {
        for (String ch : new String[]{"$", "^", "?", "`"}) {
            assertThrows(AlpacaCompilerError.class, () -> lexer.tokenize(ch),
                    "expected lexer error for '" + ch + "'");
        }
    }
}
