package net.rusrusme.alpacac;

public record Token(TokenType type, String lexeme, Object literal, int line, int column) {
    @Override
    public String toString() {
        return String.format("%s '%s'", type, lexeme);
    }
}