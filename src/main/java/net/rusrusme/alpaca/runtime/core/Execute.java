package net.rusrusme.alpaca.runtime.core;

import net.rusrusme.alpacac.*;
import net.rusrusme.alpacac.SyntaxTree.Declaration;
import net.rusrusme.alpacac.SyntaxTree.Program;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class Execute {
    private Execute() {}

    static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("alpaca: I/O error: no source file specified");
            System.exit(0);
        }
        // args[0] is the entry point file; its transitive imports are lexed and
        // parsed concurrently by ModuleLoader.
        try {
            run(Path.of(args[0]));
        }
        catch (AlpacaCompilerError e) {
            System.err.println("alpaca: compiler error: " + e.getMessage());
            System.exit(0);
        }
        catch (AlpacaRuntimeException e) {
            System.err.println("alpaca: runtime exception: " + e.getMessage());
            System.exit(0);
        }
        catch (IOException e) {
            System.err.println("alpaca: I/O error: " + e.getMessage());
            System.exit(0);
        }
        catch (InterruptedException e) {
            System.err.println("alpaca: interpreter error " + e.getMessage());
            System.exit(0);
        }
    }

    /**
     * Dev-time search roots for the bundled standard library. The .alpaca sources
     * live under src/main/java/net/rusrusme/alpaca/runtime/lib and are resolved
     * relative to the working directory; they are not packaged into the jar yet.
     */
    private static List<Path> stdlibSearchRoots() {
        Path stdlib = Path.of("src", "main", "java", "net", "rusrusme", "alpaca", "runtime", "lib");
        return Files.isDirectory(stdlib) ? List.of(stdlib) : List.of();
    }

    /**
     * Runs the full pipeline for the given entry-point file: lex, parse, import
     * merge, M1, M2, monomorphize, re-analyze the concrete program, and execute
     * it. Failures propagate to the caller — only {@link #main(String[])} turns
     * them into exit codes, so library callers (e.g. tests) never kill the JVM.
     */
    public static void run(Path filePath) throws IOException, InterruptedException {
        Path absolutePath = filePath.toAbsolutePath().normalize();
        String sourceCode = Files.readString(absolutePath);

        // Lex and Parse the main file
        AlpacaLexer lexer = new AlpacaLexer();
        List<Token> tokens = lexer.tokenize(sourceCode);

        AlpacaParser parser = new AlpacaParser(absolutePath.getFileName().toString(), tokens);
        Program mainProgram = parser.construct();

        // Resolve imported dependencies (lexed and parsed concurrently)
        List<Declaration> mergedDeclarations = new ArrayList<>();
        if (!mainProgram.imports().isEmpty()) {
            ModuleLoader loader = new ModuleLoader(stdlibSearchRoots());
            List<Declaration> dependencies = loader.resolveDependencies(
                    mainProgram.imports(),
                    absolutePath.getParent()
            );
            mergedDeclarations.addAll(dependencies);
        }

        // Combine dependencies with main program declarations
        mergedDeclarations.addAll(mainProgram.declarations());

        // Final unified program ready for semantic analysis
        Program programWithDeps = new Program(
                mainProgram.name(),
                List.of(),
                mergedDeclarations
        );

        SemanticAnalyzer analyzer = new SemanticAnalyzer();
        analyzer.analyze(programWithDeps);

        TypeChecker typeChecker = new TypeChecker(analyzer.symbolTable(), analyzer.resolvedSymbols());
        typeChecker.check(programWithDeps);

        // Monomorphize (milestone 3): generic functions and objects become
        // concrete clones with mangled names; the output is generic-free.
        Monomorphizer monomorphizer = new Monomorphizer(
                analyzer.symbolTable(), analyzer.resolvedSymbols(), typeChecker.expressionTypes());
        Program concreteProgram = monomorphizer.process(programWithDeps);

        // Re-analyze the concrete program: with no type parameters left, the
        // lax type rules never fire, so this is a strict verification of the
        // monomorphizer's output. The C emitter (milestone 4) consumes the
        // concrete program and the fresh resolution maps.
        SemanticAnalyzer concreteAnalyzer = new SemanticAnalyzer();
        concreteAnalyzer.analyze(concreteProgram);
        new TypeChecker(concreteAnalyzer.symbolTable(), concreteAnalyzer.resolvedSymbols())
                .check(concreteProgram);

        System.out.println(concreteProgram);
        // Run the program
        AlpacaInterpreter interpreter = new AlpacaInterpreter(concreteProgram);
//        interpreter.execute();
    }
}
