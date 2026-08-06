package net.rusrusme.alpacac;

import net.rusrusme.alpacac.SyntaxTree.*;

public final class AlpacaGenerator {
    private final StringBuilder builder = new StringBuilder();

    private int indentLevel = 0;

    public String generate(Program program) {

        builder.append("#include <stdio.h>\n");
        builder.append("#include <stdbool.h>\n");
        builder.append("#include <stdlib.h>\n");
        builder.append("#include <string.h>\n");

        for (Declaration declaration : program.declarations()) {

        }

        return builder.toString();
    }
}
