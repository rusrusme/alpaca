import net.rusrusme.alpacac.AlpacaCompilerError;
import net.rusrusme.alpacac.AlpacaLexer;
import net.rusrusme.alpacac.AlpacaParser;
import net.rusrusme.alpacac.ModuleLoader;
import net.rusrusme.alpacac.SemanticAnalyzer;
import net.rusrusme.alpacac.SyntaxTree.Declaration;
import net.rusrusme.alpacac.SyntaxTree.Import;
import net.rusrusme.alpacac.SyntaxTree.Program;
import net.rusrusme.alpacac.TypeChecker;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests: the full pipeline (lex, parse, resolve imports
 * via the real ModuleLoader against the bundled stdlib directory, semantic
 * analysis, type checking) for programs that actually use the stdlib.
 */
class IntegrationTests {

    private static final Path LIB_DIR = Path.of("src/main/java/net/rusrusme/alpaca/runtime/lib");

    private void analyzeWithStdlib(String source) {
        Assumptions.assumeTrue(Files.isDirectory(LIB_DIR), "bundled stdlib not present; skipping");
        Program main = new AlpacaParser("main.alpaca", new AlpacaLexer().tokenize(source)).construct();
        ModuleLoader loader = new ModuleLoader(List.of(LIB_DIR));
        List<Declaration> merged;
        try {
            merged = new ArrayList<>(loader.resolveDependencies(main.imports(), Path.of(".")));
        }
        catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        merged.addAll(main.declarations());
        Program program = new Program("main", List.of(), merged);
        SemanticAnalyzer analyzer = new SemanticAnalyzer();
        analyzer.analyze(program);
        new TypeChecker(analyzer.symbolTable(), analyzer.resolvedSymbols()).check(program);
    }

    private AlpacaCompilerError analyzeErrorWithStdlib(String source) {
        try {
            analyzeWithStdlib(source);
            fail("expected AlpacaCompilerError");
            return null;
        }
        catch (AlpacaCompilerError e) {
            return e;
        }
    }

    @Test
    @DisplayName("Runs the stdlib demo through the full import pipeline")
    void testStdlibDemoPipeline() {
        analyzeWithStdlib("""
                import data.optional;
                import data.array;

                @main
                void run() {
                    Array<int> arr = Array(10);
                    arr.add(5);
                    Optional<int> o = arr.get(0);
                    if o.is_present() {
                        int v = o.get();
                    }
                }
                """);
    }

    @Test
    @DisplayName("Accepts remove returning Optional and clearing the cell")
    void testRemoveReturnsOptional() {
        analyzeWithStdlib("""
                import data.optional;
                import data.array;

                @main
                void run() {
                    Array<int> arr = Array(10);
                    arr.add(1);
                    arr.add(2);
                    Optional<int> removed = arr.remove(0);
                    Optional<int> again = arr.get(0);
                    bool b = removed.is_present() && !again.is_present();
                }
                """);
    }

    @Test
    @DisplayName("Accepts some and none directly from the stdlib")
    void testSomeAndNone() {
        analyzeWithStdlib("""
                import data.optional;

                @main
                void run() {
                    Optional<int> o = some(5);
                    Optional<int> n = none(0);
                    bool b = o.is_present() && !n.is_present();
                    int v = o.get();
                }
                """);
    }

    @Test
    @DisplayName("Accepts nesting a stdlib Array inside an Optional")
    void testNestedStdlibTypes() {
        analyzeWithStdlib("""
                import data.optional;
                import data.array;

                @main
                void run() {
                    Optional<Array<int>> o = some(Array(10));
                    bool b = o.is_present();
                }
                """);
    }

    @Test
    @DisplayName("Rejects accessing a private stdlib member from outside")
    void testStdlibPrivateMember() {
        AlpacaCompilerError e = analyzeErrorWithStdlib("""
                import data.array;

                @main
                void run() {
                    Array<int> arr = Array(10);
                    int s = arr.size;
                }
                """);
        assertTrue(e.getMessage().contains("cannot access private member 'size'"));
    }

    @Test
    @DisplayName("Rejects an unknown stdlib member")
    void testStdlibUnknownMember() {
        AlpacaCompilerError e = analyzeErrorWithStdlib("""
                import data.array;

                @main
                void run() {
                    Array<int> arr = Array(10);
                    arr.nope = 1;
                }
                """);
        assertTrue(e.getMessage().contains("unknown member 'nope' on object 'Array'"));
    }

    @Test
    @DisplayName("Rejects wrong argument count on a stdlib method")
    void testStdlibMethodArity() {
        AlpacaCompilerError e = analyzeErrorWithStdlib("""
                import data.array;

                @main
                void run() {
                    Array<int> arr = Array(10);
                    arr.add();
                }
                """);
        assertTrue(e.getMessage().contains("expects 1 arguments but got 0"));
    }

    @Test
    @DisplayName("Rejects a missing import from the stdlib search roots")
    void testMissingStdlibImport() {
        AlpacaCompilerError e = analyzeErrorWithStdlib("""
                import data.nope;

                @main
                void run() { }
                """);
        assertTrue(e.getMessage().contains("could not resolve import"));
    }
}
