package net.rusrusme.alpacac;

public enum TokenType {
    // Keywords
    OBJECT,
    IF, ELSE,
    SWITCH, CASE, DEFAULT,
    FOR,
    DO, WHILE,
    UNSAFE,
    UNSAFE_CODE, // raw C body of an unsafe block, read verbatim by the lexer
    AT, // `@`
    IS,
    RETURN,
    BREAK, CONTINUE,
    PUBLIC, PRIVATE,
    IMPORT,
    THIS, // Misc

    // Primitive Types
    VOID, INT, UNSIGNED, LONG,
    FLOAT, DOUBLE,
    STRING, CHAR, BOOL,

    // Literals & Identifiers
    IDENTIFIER,
    LITERAL,
    BOOL_LITERAL,
    INT_LITERAL,
    FLOAT_LITERAL,
    STRING_LITERAL,
    CHAR_LITERAL,

    // Arithmetic
    PLUS, MINUS, STAR, SLASH, PERCENT,
    PLUS_PLUS, MINUS_MINUS,

    // Logic
    AND_AND, OR_OR,

    // Comparisons
    EQUALS, EQUALS_EQUALS, BANG, BANG_EQUALS,
    LESS, LESS_EQUALS, GREATER, GREATER_EQUALS,

    // Blocks
    LPAR, RPAR,             // ()
    LBRACE, RBRACE,         // {}
    LBRACKET, RBRACKET,     // []

    // Separators & Control Flow
    COMMA, COLON, SEMICOLON,
    ARROW, // ->

    // Member Access
    DOT,

    // Comments
    HASH, // `#`

    EOF
}
