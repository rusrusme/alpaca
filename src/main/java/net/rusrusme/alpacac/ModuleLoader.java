package net.rusrusme.alpacac;

import net.rusrusme.alpacac.SyntaxTree.Declaration;
import net.rusrusme.alpacac.SyntaxTree.Import;
import net.rusrusme.alpacac.SyntaxTree.Program;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@link ModuleLoader} class is responsible for resolving and loading all import
 * dependencies for a given list of imports using a combination of local relative lookup
 * and global search roots.
 * <p>
 * Dependencies are lexed and parsed <b>concurrently</b> (parallel streams). The shared
 * loaded-files set uses atomic {@code add}, so exactly one task parses each file and
 * circular imports terminate. The result list contains every transitively imported
 * declaration exactly once; the order is not deterministic (the semantic analyzer
 * registers all names in a first pass and is order-independent).
 */
public final class ModuleLoader {
    private final List<Path> searchRoots;
    private final Set<Path> loadedFiles = ConcurrentHashMap.newKeySet();

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
     * Resolves all import dependencies for a given list of imports relative to a working
     * directory (the project root). The imports are loaded concurrently, each pulling in
     * its own transitive dependencies.
     *
     * @param imports          The imports declared in the root file.
     * @param workingDirectory The directory containing the root file.
     * @return A list of all transitively imported Declarations, each file exactly once.
     */
    public List<Declaration> resolveDependencies(List<Import> imports, Path workingDirectory) throws IOException {
        Path projectRoot = workingDirectory.toAbsolutePath().normalize();
        try {
            return imports.parallelStream()
                    .map(imp -> findImportFile(imp.path(), projectRoot, projectRoot))
                    .flatMap(path -> loadRecursive(path, projectRoot).stream())
                    .toList();
        }
        catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /**
     * Reads, lexes and parses one dependency file and recursively all of its imports.
     *
     * @return The file's own declarations, after all of its transitive dependencies.
     */
    private List<Declaration> loadRecursive(Path filePath, Path projectRoot) {
        // Atomic check-and-add: exactly one task parses a file, all others skip it.
        if (!loadedFiles.add(filePath)) {
            return List.of();
        }

        String sourceCode;
        try {
            sourceCode = Files.readString(filePath);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        AlpacaLexer lexer = new AlpacaLexer();
        List<Token> tokens = lexer.tokenize(sourceCode);

        AlpacaParser parser = new AlpacaParser(filePath.getFileName().toString(), tokens);
        Program subProgram = parser.construct();

        // Resolve sub-dependencies first, concurrently
        List<Declaration> result = new ArrayList<>();
        if (!subProgram.imports().isEmpty()) {
            List<Declaration> subDependencies = subProgram.imports().parallelStream()
                    .map(subImport -> findImportFile(subImport.path(), filePath.getParent(), projectRoot))
                    .flatMap(subPath -> loadRecursive(subPath, projectRoot).stream())
                    .toList();
            result.addAll(subDependencies);
        }

        // Accumulate this file's own declarations
        result.addAll(subProgram.declarations());
        return result;
    }

    private Path findImportFile(String importPath, Path currentDir, Path projectRoot) {
        String relativePath = importPath.replace('.', '/') + ".alpaca";

        // Try resolving relative to the importing file's directory
        Path localPath = currentDir.resolve(relativePath).normalize();
        if (Files.exists(localPath)) {
            return localPath;
        }

        // Try resolving relative to the project root (entry file's directory)
        Path rootPath = projectRoot.resolve(relativePath).normalize();
        if (Files.exists(rootPath)) {
            return rootPath;
        }

        // Try resolving against global search roots (Source Root, Stdlib)
        for (Path root : searchRoots) {
            Path searchPath = root.resolve(relativePath).normalize();
            if (Files.exists(searchPath)) {
                return searchPath;
            }
        }
        throw new AlpacaCompilerError("could not resolve import: '" + importPath + "'");
    }
}
