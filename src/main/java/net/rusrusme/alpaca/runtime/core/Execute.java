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
        run(Path.of(args[0]));
    }

    // Concurrency
    // List<Path> sourceFiles = Files.walk(Path.of("src"))
    //        .filter(path -> path.toString().endsWith(".alpaca"))
    //        .toList();
    //
    // Parses all source files concurrently across available CPU threads
    // sourceFiles.parallelStream().forEach(path -> {
    //    try {
    //        String code = Files.readString(path);
    //        Execute.run(path.getFileName().toString(), code);
    //    } catch (IOException e) {
    //        System.err.println("Failed to read " + path);
    //    }
    //});

    public static void run(Path filePath) {
        try {
            Path absolutePath = filePath.toAbsolutePath().normalize();
            String sourceCode = Files.readString(absolutePath);

            // Lex and Parse the main file
            AlpacaLexer lexer = new AlpacaLexer();
            List<Token> tokens = lexer.tokenize(sourceCode);

            AlpacaParser parser = new AlpacaParser(absolutePath.getFileName().toString(), tokens);
            Program mainProgram = parser.construct();

            // Resolve imported dependencies if imports exist
            List<Declaration> mergedDeclarations = new ArrayList<>();
            if (!mainProgram.imports().isEmpty()) {
                ModuleLoader loader = new ModuleLoader();
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

//            monomorphizer = new LamaMonomorphizer();
//            concreteDeclarations = monomorphizer.process(declarations);

//            typeChecker.check(concreteDeclarations);

//            interpreter.execute(concreteDeclarations);
        }
        catch (AlpacaCompilerError error) {
            System.err.println("alpaca: compiler error: " + error.getMessage());
            System.exit(0);
        }
        catch (AlpacaRuntimeException exception) {
            System.err.println("alpaca: runtime exception: " + exception.getMessage());
            System.exit(0);
        }
        catch (IOException e) {
            System.err.println("alpaca: I/O error: " + e.getMessage());
            System.exit(0);
        }
    }
}
