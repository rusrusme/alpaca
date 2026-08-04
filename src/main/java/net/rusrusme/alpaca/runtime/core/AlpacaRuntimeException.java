package net.rusrusme.alpaca.runtime.core;

public class AlpacaRuntimeException extends RuntimeException {
    public AlpacaRuntimeException(String message) {
        super(message);
    }

    public AlpacaRuntimeException(String message, int line, int column) {
        super(message + " at line " + line + ":" + column);
    }
}
