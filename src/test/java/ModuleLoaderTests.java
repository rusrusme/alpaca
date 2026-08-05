import net.rusrusme.alpacac.AlpacaCompilerError;
import net.rusrusme.alpacac.ModuleLoader;
import net.rusrusme.alpacac.SyntaxTree.Declaration;
import net.rusrusme.alpacac.SyntaxTree.FunctionDeclaration;
import net.rusrusme.alpacac.SyntaxTree.GlobalVariable;
import net.rusrusme.alpacac.SyntaxTree.Import;
import net.rusrusme.alpacac.SyntaxTree.ObjectDeclaration;
import net.rusrusme.alpacac.SyntaxTree.StatementDeclaration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModuleLoaderTests {

    @TempDir
    Path dir;

    private void write(String name, String content) throws IOException {
        Path path = dir.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private List<String> declarationNames(List<Declaration> declarations) {
        return declarations.stream()
                .map(decl -> switch (decl) {
                    case ObjectDeclaration object -> object.name();
                    case FunctionDeclaration function -> function.name();
                    case GlobalVariable global -> global.name();
                    case StatementDeclaration statement -> "<statement>";
                })
                .toList();
    }

    private List<Declaration> resolve(String importPath) throws IOException {
        return new ModuleLoader().resolveDependencies(List.of(new Import(importPath)), dir);
    }

    // ============================================================
    //  Loading
    // ============================================================

    @Test
    @DisplayName("Loads a directly imported file's declarations")
    void testLoadsDirectImport() throws IOException {
        write("b.alpaca", "int fromB = 1;");
        List<String> names = declarationNames(resolve("b"));
        assertEquals(List.of("fromB"), names);
    }

    @Test
    @DisplayName("Loads transitive imports")
    void testLoadsTransitiveImports() throws IOException {
        write("c.alpaca", "int fromC = 3;");
        write("b.alpaca", "import c; int fromB = 1;");
        List<String> names = declarationNames(resolve("b"));
        assertTrue(names.containsAll(List.of("fromB", "fromC")));
    }

    @Test
    @DisplayName("Loads a file referenced by two sibling imports exactly once")
    void testSharedDependencyLoadedOnce() throws IOException {
        write("d.alpaca", "int fromD = 4;");
        write("b.alpaca", "import d; int fromB = 1;");
        write("c.alpaca", "import d; int fromC = 3;");
        write("a.alpaca", "import b; import c; int fromA = 2;");
        List<Declaration> declarations = resolve("a");
        List<String> names = declarationNames(declarations);
        assertEquals(1, names.stream().filter("fromD"::equals).count(), "shared dependency loaded more than once");
        assertTrue(names.containsAll(List.of("fromA", "fromB", "fromC")));
    }

    @Test
    @DisplayName("Circular imports terminate and load each file once")
    void testCircularImportsTerminate() throws IOException {
        write("a.alpaca", "import b; int fromA = 2;");
        write("b.alpaca", "import a; int fromB = 1;");
        List<Declaration> declarations = resolve("b");
        List<String> names = declarationNames(declarations);
        assertEquals(1, names.stream().filter("fromA"::equals).count());
        assertEquals(1, names.stream().filter("fromB"::equals).count());
    }

    @Test
    @DisplayName("Subdirectory imports fall back to the project root")
    void testSubdirectoryImportResolvesAgainstRoot() throws IOException {
        write("lib/vector.alpaca", "int fromVector = 1;");
        write("lib/math.alpaca", "import lib.vector; int fromMath = 1;");
        List<String> names = declarationNames(resolve("lib.math"));
        assertTrue(names.containsAll(List.of("fromVector", "fromMath")));
    }

    @Test
    @DisplayName("Missing imports are reported as compiler errors")
    void testMissingImport() throws IOException {
        AlpacaCompilerError e = assertThrows(AlpacaCompilerError.class, () -> resolve("nope"));
        assertTrue(e.getMessage().contains("could not resolve import: 'nope'"));
    }

    @Test
    @DisplayName("Resolves imports against configured search roots")
    void testSearchRootFallback() throws IOException {
        Path root = dir.resolve("roots");
        Files.createDirectories(root);
        Files.writeString(root.resolve("extra.alpaca"), "int fromExtra = 1;");
        write("a.alpaca", "import extra; int fromA = 2;");
        List<Declaration> declarations =
                new ModuleLoader(List.of(root)).resolveDependencies(List.of(new Import("extra")), dir);
        List<String> names = declarationNames(declarations);
        assertTrue(names.contains("fromExtra"));
    }

    @Test
    @DisplayName("Loads a deep import chain")
    void testDeepImportChain() throws IOException {
        write("d.alpaca", "int fromD = 4;");
        write("c.alpaca", "import d; int fromC = 3;");
        write("b.alpaca", "import c; int fromB = 1;");
        List<String> names = declarationNames(resolve("b"));
        assertTrue(names.containsAll(List.of("fromB", "fromC", "fromD")));
    }

    @Test
    @DisplayName("Loads a file imported twice by the same module once")
    void testDuplicateImportInSameFile() throws IOException {
        write("d.alpaca", "int fromD = 4;");
        write("a.alpaca", "import d; import d; int fromA = 2;");
        List<Declaration> declarations = resolve("a");
        List<String> names = declarationNames(declarations);
        assertEquals(1, names.stream().filter("fromD"::equals).count());
    }

    @Test
    @DisplayName("Reports missing subdirectory imports")
    void testMissingSubdirImport() throws IOException {
        write("lib/a.alpaca", "import lib.nope; int fromA = 1;");
        AlpacaCompilerError e = assertThrows(AlpacaCompilerError.class, () -> resolve("lib.a"));
        assertTrue(e.getMessage().contains("could not resolve import"));
    }

    @Test
    @DisplayName("Propagates syntax errors from imported files")
    void testSyntaxErrorInImportPropagates() throws IOException {
        write("bad.alpaca", "int = ;");
        AlpacaCompilerError e = assertThrows(AlpacaCompilerError.class, () -> resolve("bad"));
        assertTrue(e.getMessage().contains("syntax error"));
    }
}
