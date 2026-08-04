package net.rusrusme.alpacac;

import net.rusrusme.alpacac.SyntaxTree.Declaration;
import net.rusrusme.alpacac.SyntaxTree.Import;
import net.rusrusme.alpacac.SyntaxTree.Program;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The {@link ModuleLoader} class is responsible for resolving and loading all import dependencies for a
 * given list of imports using a combination of local relative lookup and global search roots.
 * It recursively processes each import, ensuring that each file is loaded only once to avoid circular dependencies.
 */
public final class ModuleLoader {
    private final List<Path> searchRoots;
    private final Set<Path> loadedFiles = new HashSet<>();
    private final List<Declaration> dependencies = new ArrayList<>();

    public ModuleLoader(List<Path> searchRoots) {
        this.searchRoots = searchRoots.stream()
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .toList();
    }

    public ModuleLoader() {
        this(List.of());
    }

    /**
     * Resolves all import dependencies for a given list of imports relative to a working directory.
     *
     * @param imports          The imports declared in the root file.
     * @param workingDirectory The directory containing the root file.
     * @return A list of all transitively imported Declarations in dependency order.
     */
    public List<Declaration> resolveDependencies(List<Import> imports, Path workingDirectory) throws IOException {
        for (Import imp : imports) {
            Path targetPath = findImportFile(imp.path(), workingDirectory);
            loadRecursive(targetPath);
        }
        return dependencies;
    }

    private void loadRecursive(Path filePath) throws IOException {
        if (loadedFiles.contains(filePath)) {
            return; // Avoid circular loops and duplicate loads
        }
        loadedFiles.add(filePath);

        // Read and parse dependency file
        String sourceCode = Files.readString(filePath);
        AlpacaLexer lexer = new AlpacaLexer();
        List<Token> tokens = lexer.tokenize(sourceCode);

        AlpacaParser parser = new AlpacaParser(filePath.getFileName().toString(), tokens);
        Program subProgram = parser.construct();

        // Resolve sub-dependencies first (DFS)
        Path parentDir = filePath.getParent();
        for (Import subImport : subProgram.imports()) {
            Path subPath = findImportFile(subImport.path(), parentDir);
            loadRecursive(subPath);
        }

        // Accumulate sub-program declarations
        dependencies.addAll(subProgram.declarations());
    }

    private Path findImportFile(String importPath, Path currentDir) {
        String relativePath = importPath.replace('.', '/') + ".alpaca";

        // Try resolving relative to current file's directory
        Path localPath = currentDir.resolve(relativePath).normalize();
        if (Files.exists(localPath)) {
            return localPath;
        }

        // Try resolving against global search roots (Source Root, Stdlib)
        for (Path root : searchRoots) {
            Path rootPath = root.resolve(relativePath).normalize();
            if (Files.exists(rootPath)) {
                return rootPath;
            }
        }
        System.out.println(relativePath);
        throw new AlpacaCompilerError("alpaca: module loader error: could not resolve import: '" + importPath + "'");
    }
}