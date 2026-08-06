package net.rusrusme.alpacac;

import net.rusrusme.alpacac.SyntaxTree.Program;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Wrapper class for executing the transpiled C code
 */
public final class AlpacaInterpreter {
    public static final String OUT_PATH = "src/main/java/net/rusrusme/alpaca/runtime/out";

    private final Program program;

    public AlpacaInterpreter(Program program) {
        this.program = program;
    }

    public void execute() throws IOException, InterruptedException {
        AlpacaGenerator generator = new AlpacaGenerator();
        String cCode = generator.generate(program);

        Path out = Path.of(OUT_PATH);
        Files.createDirectories(out);
        Path cFile = out.resolve("transpiled.c");
        Files.writeString(cFile, cCode);

        ProcessBuilder compileBuilder = new ProcessBuilder("gcc", cFile.toString(), "-o", out.resolve("alpaca").toString());
        compileBuilder.inheritIO();
        Process compileProcess = compileBuilder.start();
        int compileExitCode = compileProcess.waitFor();

        if (compileExitCode != 0) {
            System.err.println("compilation failed");
            return;
        }

        ProcessBuilder runBuilder = new ProcessBuilder("./" + OUT_PATH + "/alpaca");
        runBuilder.inheritIO();
        Process runProcess = runBuilder.start();
        int runExitCode = runProcess.waitFor();

        System.out.println("process finished with exit code " + runExitCode);
    }
}
