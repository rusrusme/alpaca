package net.rusrusme.alpacac;

import net.rusrusme.alpacac.SyntaxTree.Type;
import net.rusrusme.alpacac.SyntaxTree.Visibility;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * A symbol table with a scope chain for the Alpaca compiler.
 * <p>
 * Top-level names (functions, objects, global variables) share a single global
 * namespace. Object members (fields and methods) live in a per-object member
 * map that is looked up separately. Local scopes (function parameters, blocks,
 * for-loop bodies) form a stack: {@link #lookup(String)} searches the innermost
 * scope first and walks outward to the globals.
 */
public final class SymbolTable {
    /**
     * The kind of a declared name.
     */
    public enum Kind {VARIABLE, FUNCTION, OBJECT, FIELD, METHOD, TYPE_PARAMETER}

    /**
     * A resolved declaration.
     *
     * @param name            The declared name.
     * @param kind            The kind of declaration.
     * @param type            The declared type; {@code null} for {@link Kind#OBJECT}.
     * @param visibility      The visibility modifier.
     * @param constant        Whether the name is marked {@code @const}.
     * @param declaringObject The owning object for {@link Kind#FIELD} and {@link Kind#METHOD},
     *                        otherwise {@code null}.
     */
    public record Symbol(
            String name,
            Kind kind,
            Type type,
            Visibility visibility,
            boolean constant,
            String declaringObject
    ) {}

    private final Map<String, Symbol> globals = new HashMap<>();
    private final Map<String, Map<String, Symbol>> objects = new HashMap<>();
    private final Deque<Map<String, Symbol>> scopes = new ArrayDeque<>();

    /**
     * Registers a top-level name (function, object or global variable).
     *
     * @return {@code false} if the name is already taken.
     */
    public boolean defineGlobal(Symbol symbol) {
        return globals.putIfAbsent(symbol.name(), symbol) == null;
    }

    /**
     * Registers the member map of an object. The object itself must already be
     * registered via {@link #defineGlobal(Symbol)} with {@link Kind#OBJECT}.
     */
    public void defineObject(String name, Map<String, Symbol> members) {
        objects.put(name, members);
    }

    /**
     * Returns the members of the named object, or {@code null} if no such object exists.
     */
    public Map<String, Symbol> lookupObject(String name) {
        return objects.get(name);
    }

    /**
     * Enters a new local scope.
     */
    public void pushScope() {
        scopes.push(new HashMap<>());
    }

    /**
     * Leaves the innermost local scope.
     */
    public void popScope() {
        scopes.pop();
    }

    /**
     * Defines a name in the innermost local scope.
     *
     * @return {@code false} if the name is already defined in that scope.
     */
    public boolean defineLocal(Symbol symbol) {
        return scopes.peek().putIfAbsent(symbol.name(), symbol) == null;
    }

    /**
     * Resolves a name against the scope chain (innermost local scope first,
     * then outward, then the global namespace).
     *
     * @return The resolved symbol, or {@code null} if the name is unknown.
     */
    public Symbol lookup(String name) {
        for (Map<String, Symbol> scope : scopes) {
            Symbol symbol = scope.get(name);
            if (symbol != null) {
                return symbol;
            }
        }
        return globals.get(name);
    }
}
