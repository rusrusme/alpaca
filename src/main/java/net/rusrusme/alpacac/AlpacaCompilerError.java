package net.rusrusme.alpacac;

public class AlpacaCompilerError extends RuntimeException {
    public AlpacaCompilerError(String message) {
        super(message);
    }

    public AlpacaCompilerError(String message, int line, int column) {
        super(message + " at line " + line + ":" + column);
    }
}
