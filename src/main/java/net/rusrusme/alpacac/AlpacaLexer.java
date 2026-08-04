package net.rusrusme.alpacac;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static net.rusrusme.alpacac.TokenType.*;

/**
 * The `AlpacaLexer` class is responsible for tokenizing the source code of the alpaca programming language.
 * It reads the input source code character by character and produces a list of tokens that represent the
 * lexical elements of the language, such as keywords, identifiers, literals, operators, and punctuation.
 */
public final class AlpacaLexer {
    private static final Map<String, TokenType> KEYWORDS = new HashMap<>();

    static {
        KEYWORDS.put("if", IF);
        KEYWORDS.put("else", ELSE);
        KEYWORDS.put("for", FOR);
        KEYWORDS.put("while", WHILE);
        KEYWORDS.put("do", DO);
        KEYWORDS.put("return", RETURN);
        KEYWORDS.put("break", BREAK);
        KEYWORDS.put("continue", CONTINUE);
        KEYWORDS.put("unsafe", UNSAFE);
        KEYWORDS.put("is", IS);
        KEYWORDS.put("object", OBJECT);
        KEYWORDS.put("public", PUBLIC);
        KEYWORDS.put("private", PRIVATE);
        KEYWORDS.put("switch", SWITCH);
        KEYWORDS.put("case", CASE);
        KEYWORDS.put("default", DEFAULT);
        KEYWORDS.put("import", IMPORT);

        // Primitive types
        KEYWORDS.put("void", VOID);
        KEYWORDS.put("int", INT);
        KEYWORDS.put("unsigned", UNSIGNED);  // part of "unsigned int" / "unsigned long"
        KEYWORDS.put("long", LONG);
        KEYWORDS.put("float", FLOAT);
        KEYWORDS.put("double", DOUBLE);
        KEYWORDS.put("string", STRING);
        KEYWORDS.put("char", CHAR);
        KEYWORDS.put("bool", BOOL);

        KEYWORDS.put("true", BOOL_LITERAL);
        KEYWORDS.put("false", BOOL_LITERAL);
    }

    private String sourceCode;
    private List<Token> tokens;
    private int start;
    private int current;
    private int line;
    private int column;

    public AlpacaLexer() {
    }

    /**
     * Tokenizes the given source code into a list of tokens.
     *
     * @param sourceCode The source code to tokenize.
     * @return A list of tokens representing the lexical elements of the source code.
     */
    public List<Token> tokenize(String sourceCode) {
        this.sourceCode = sourceCode == null ? "" : sourceCode;
        this.tokens = new ArrayList<>();
        this.start = 0;
        this.current = 0;
        this.line = 1;
        this.column = 0;

        while (!isAtEnd()) {
            start = current;
            scanToken();
        }
        tokens.add(new Token(EOF, "", null, line, column));
        return tokens;
    }

    /**
     * Scans the next token from the source code and adds it to the list of tokens.
     */
    private void scanToken() {
        char c = advance();
        switch (c) {
            // -----------------------
            case '(' -> addToken(LPAR);
            case ')' -> addToken(RPAR);
            case '{' -> addToken(LBRACE);
            case '}' -> addToken(RBRACE);
            case '[' -> addToken(LBRACKET);
            case ']' -> addToken(RBRACKET);
            // -----------------------
            case ',' -> addToken(COMMA);
            case ':' -> addToken(COLON);
            case ';' -> addToken(SEMICOLON);
            case '.' -> addToken(DOT);
            // -----------------------
            case '+' -> addToken(match('+') ? PLUS_PLUS : PLUS);
            case '-' -> {
                if (match('-')) {
                    addToken(MINUS_MINUS);
                }
                else if (match('>')) {
                    addToken(ARROW);
                }
                else {
                    addToken(MINUS);
                }
            }
            case '*' -> addToken(STAR);
            case '/' -> addToken(SLASH);
            // -----------------------
            case '&' -> {
                if (match('&')) {
                    addToken(AND_AND);
                }
                else {
                    throw new AlpacaCompilerError("syntax error: unexpected character '&'", line, column);
                }
            }
            case '|' -> {
                if (match('|')) {
                    addToken(OR_OR);
                }
                else {
                    throw new AlpacaCompilerError("syntax error: unexpected character '|'", line, column);
                }
            }
            // -----------------------
            case '@' -> addToken(AT);
            // -----------------------
            case '#' -> {
                while (peek() != '\n' && !isAtEnd()) advance();
            }
            //  -----------------------
            case '=' -> addToken(match('=') ? EQUALS_EQUALS : EQUALS);
            case '!' -> addToken(match('=') ? BANG_EQUALS : BANG);
            case '<' -> addToken(match('=') ? LESS_EQUALS : LESS);
            case '>' -> addToken(match('=') ? GREATER_EQUALS : GREATER);
            // -----------------------
            case '\n' -> {
                line++;
                column = 0;
            }
            case ' ', '\r', '\t' -> { /* Ignore whitespace */ }
            // -----------------------
            case '\'' -> character();
            case '"' -> string();
            default -> {
                if (isDigit(c)) {
                    number();
                }
                else if (isAlphabet(c)) {
                    identifier();
                }
                else {
                    throw new AlpacaCompilerError("syntax error: unexpected character '" + c, line, column);
                }
            }
        }
    }

    /**
     * Scans an identifier or keyword from the source code and adds it as a token.
     * It checks if the scanned text matches any reserved keywords and assigns the appropriate token type.
     */
    private void identifier() {
        while (isAlphabetNumeric(peek())) advance();

        String text = sourceCode.substring(start, current);
        TokenType type = KEYWORDS.getOrDefault(text, IDENTIFIER);

        if (type == BOOL_LITERAL) {
            addToken(type, Boolean.parseBoolean(text));
        }
        else {
            addToken(type);
        }
    }

    /**
     * Scans a number literal from the source code and adds it as a token.
     * It handles both integer and floating-point literals.
     */
    private void number() {
        while (isDigit(peek())) advance();

        boolean isFloat = false;

        if (peek() == '.' && isDigit(peekNext())) {
            isFloat = true;

            do advance();
            while (isDigit(peek()));
        }

        String text = sourceCode.substring(start, current);
        try {
            if (isFloat) {
                addToken(FLOAT_LITERAL, Float.parseFloat(text));
            }
            else {
                addToken(INT_LITERAL, Integer.parseInt(text));
            }
        }
        catch (NumberFormatException _) {
            String typeName = isFloat ? "float" : "integer";
            throw new AlpacaCompilerError("syntax error: " + typeName + " literal out of range '" + text, line, column);
        }
    }

    /**
     * Scans a string literal from the source code and adds it as a token.
     */
    private void string() {
        StringBuilder builder = new StringBuilder();

        while (peek() != '"' && !isAtEnd()) {
            char c = advance();
            if (c == '\n') {
                line++;
                column = 0;
                builder.append(c);
            }
            else if (c == '\\') {
                if (isAtEnd()) break;
                char escaped = advance();
                switch (escaped) {
                    case 'n' -> builder.append('\n');
                    case 't' -> builder.append('\t');
                    case 'r' -> builder.append('\r');
                    case '"' -> builder.append('"');
                    case '\\' -> builder.append('\\');
                    default ->
                            throw new AlpacaCompilerError("syntax error: invalid escape sequence '\\" + escaped, line, column);
                }
            }
            else {
                builder.append(c);
            }
        }
        if (isAtEnd()) {
            throw new AlpacaCompilerError("syntax error: unterminated string", line, column);
        }
        advance(); // Closing quote '"'
        addToken(STRING_LITERAL, builder.toString());
    }

    /**
     * Scans a character literal from the source code and adds it as a token.
     */
    private void character() {
        if (isAtEnd()) {
            throw new AlpacaCompilerError("syntax error: unterminated character literal", line, column);
        }

        char value;
        char c = advance();

        if (c == '\'') {
            throw new AlpacaCompilerError("syntax error: empty character literal", line, column);
        }

        if (c == '\\') {
            if (isAtEnd()) {
                throw new AlpacaCompilerError("syntax error: unterminated character literal", line, column);
            }
            char escaped = advance();
            value = switch (escaped) {
                case 'n' -> '\n';
                case 't' -> '\t';
                case 'r' -> '\r';
                case '\'' -> '\'';
                case '\\' -> '\\';
                default ->
                        throw new AlpacaCompilerError("syntax error: invalid escape sequence '\\" + escaped + "'", line, column);
            };
        }
        else if (c == '\n') {
            line++;
            column = 0;
            value = c;
        }
        else {
            value = c;
        }

        // Ensure the character literal is immediately followed by the closing quote '\''
        if (peek() != '\'') {
            throw new AlpacaCompilerError("syntax error: unterminated character literal", line, column);
        }

        advance(); // Closing quote '\''
        addToken(CHAR_LITERAL, value);
    }

    /**
     * Checks if the current character matches the expected character.
     *
     * @param expected The character to match against the current character.
     * @return True if the current character matches the expected character, false otherwise.
     */
    private boolean match(char expected) {
        if (isAtEnd() || sourceCode.charAt(current) != expected) return false;
        current++;
        column++;
        return true;
    }

    /**
     * Returns the current character without advancing the lexer.
     *
     * @return The current character, or '\0' if the lexer is at the end of the source code.
     */
    private char peek() {
        if (isAtEnd()) return '\0';
        return sourceCode.charAt(current);
    }

    /**
     * Returns the next character without advancing the lexer.
     *
     * @return The next character, or '\0' if the lexer is at the end of the source code.
     */
    private char peekNext() {
        if (current + 1 >= sourceCode.length()) return '\0';
        return sourceCode.charAt(current + 1);
    }

    /**
     * Advances the lexer to the next character and returns the current character.
     *
     * @return The current character before advancing, or '\0' if the lexer is at the end of the source code.
     */
    private char advance() {
        column++;
        return sourceCode.charAt(current++);
    }

    /**
     * Checks if the lexer has reached the end of the source code.
     *
     * @return True if the lexer is at the end of the source code, false otherwise.
     */
    private boolean isAtEnd() {
        return current >= sourceCode.length();
    }

    /**
     * Checks if the given character is a digit (0-9).
     *
     * @param c The character to check.
     * @return True if the character is a digit, false otherwise.
     */
    private boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /**
     * Checks if the given character is an alphabetic character (a-z, A-Z) or an underscore (_).
     *
     * @param c The character to check.
     * @return True if the character is alphabetic or an underscore, false otherwise.
     */
    private boolean isAlphabet(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    /**
     * Checks if the given character is alphanumeric (a-z, A-Z, 0-9) or an underscore (_).
     *
     * @param c The character to check.
     * @return True if the character is alphanumeric or an underscore, false otherwise.
     */
    private boolean isAlphabetNumeric(char c) {
        return isAlphabet(c) || isDigit(c);
    }

    /**
     * Adds a token of the specified type to the list of tokens.
     *
     * @param type The type of the token to add.
     */
    private void addToken(TokenType type) {
        addToken(type, null);
    }

    /**
     * Adds a token of the specified type and literal value to the list of tokens.
     *
     * @param type    The type of the token to add.
     * @param literal The literal value associated with the token, or null if there is no literal value.
     */
    private void addToken(TokenType type, Object literal) {
        String text = sourceCode.substring(start, current);
        int startColumn = column - text.length() + 1;
        tokens.add(new Token(type, text, literal, line, startColumn));
    }
}