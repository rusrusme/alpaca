package net.rusrusme.alpacac;

import net.rusrusme.alpacac.SymbolTable.Kind;
import net.rusrusme.alpacac.SymbolTable.Symbol;
import net.rusrusme.alpacac.SyntaxTree.*;

import java.util.*;

/**
 * Semantic analyzer for Alpaca (milestone 1: scope resolution).
 * <p>
 * Runs two passes over the merged program:
 * <ul>
 *   <li><b>Pass 1 (collect):</b> registers all top-level names (functions, objects,
 *       global variables) and all object members, and rejects duplicate declarations,
 *       duplicate parameters and duplicate type parameters.</li>
 *   <li><b>Pass 2 (resolve):</b> walks every statement and expression, resolves
 *       identifiers against the scope chain, validates object member access
 *       (existence + visibility) and rejects writes to {@code @const} names.</li>
 * </ul>
 * <p>
 * Type checking is intentionally out of scope for this milestone; expressions whose
 * receiver type cannot be resolved statically (e.g. the result of a call) are left
 * unchecked and will be handled by the type checker.
 */
public final class SemanticAnalyzer {
    private final SymbolTable symbols = new SymbolTable();

    /**
     * The object whose body is currently being resolved; {@code null} at top level.
     */
    private String currentObject;

    /**
     * Expressions to the symbols they resolved to, populated during pass 2.
     * Purely additive side info for the type checker (milestone 2); the
     * resolution behaviour itself is unchanged.
     */
    private final Map<Expression, Symbol> resolved = new IdentityHashMap<>();

    public SymbolTable symbolTable() {
        return symbols;
    }

    public Map<Expression, Symbol> resolvedSymbols() {
        return resolved;
    }

    public void analyze(Program program) {
        collect(program.declarations());
        resolve(program.declarations());
    }

    // --------------------------------------------------------------------------
    // Pass 1: collect declarations
    // --------------------------------------------------------------------------

    private void collect(List<Declaration> declarations) {
        for (Declaration declaration : declarations) {
            switch (declaration) {
                case ObjectDeclaration object -> collectObject(object);
                case FunctionDeclaration function -> collectFunction(function, null);
                case GlobalVariable global -> collectGlobal(global);
                case StatementDeclaration _ ->
                        throw new AlpacaCompilerError("top-level statements are not allowed");
            }
        }
    }

    private void collectObject(ObjectDeclaration object) {
        defineTopLevel(object.name(), new Symbol(object.name(), Kind.OBJECT, null, object.visibility(), false, null),
                "declaration");
        checkTypeParameters(object.decorators());

        Map<String, Symbol> members = new HashMap<>();
        for (Field field : object.fields()) {
            Symbol symbol = new Symbol(field.name(), Kind.FIELD, field.type(), field.visibility(),
                    hasDecorator(field.decorators(), ConstDecorator.class), object.name());
            defineMember(members, symbol, object.name());
        }
        for (FunctionDeclaration method : object.methods()) {
            Symbol symbol = new Symbol(method.name(), Kind.METHOD, method.returnType(), method.visibility(),
                    hasDecorator(method.decorators(), ConstDecorator.class), object.name());
            defineMember(members, symbol, object.name());
            checkParameters(method, object.name());
        }
        object.constructor().ifPresent(constructor -> {
            // Without an explicit modifier the constructor inherits the object's visibility.
            Visibility visibility = constructor.visibility().orElse(object.visibility());
            Symbol symbol = new Symbol("construct", Kind.CONSTRUCTOR, null, visibility, false, object.name());
            defineMember(members, symbol, object.name());
            checkParameters("construct", constructor.parameters(), constructor.decorators(), object.name());
        });
        symbols.defineObject(object.name(), members);
    }

    private void collectFunction(FunctionDeclaration function, String objectName) {
        defineTopLevel(function.name(),
                new Symbol(function.name(), Kind.FUNCTION, function.returnType(), function.visibility(), false, null),
                "declaration");
        checkParameters(function, objectName);
    }

    private void collectGlobal(GlobalVariable global) {
        defineTopLevel(global.name(),
                new Symbol(global.name(), Kind.VARIABLE, global.type(), global.visibility(),
                        hasDecorator(global.decorators(), ConstDecorator.class), null),
                "declaration");
    }

    private void defineTopLevel(String name, Symbol symbol, String what) {
        if (!symbols.defineGlobal(symbol)) {
            throw new AlpacaCompilerError("duplicate " + what + " of '" + name + "'");
        }
    }

    private void defineMember(Map<String, Symbol> members, Symbol symbol, String objectName) {
        if (members.putIfAbsent(symbol.name(), symbol) != null) {
            throw new AlpacaCompilerError(
                    "duplicate member '" + symbol.name() + "' in object '" + objectName + "'");
        }
    }

    private void checkParameters(FunctionDeclaration function, String objectName) {
        checkParameters(function.name(), function.parameters(), function.decorators(), objectName);
    }

    private void checkParameters(String name, List<Parameter> parameters, List<Decorator> decorators, String objectName) {
        Set<String> names = new HashSet<>();
        for (Parameter parameter : parameters) {
            if (!names.add(parameter.name())) {
                String context = objectName != null ? " of object '" + objectName + "'" : "";
                throw new AlpacaCompilerError(
                        "duplicate parameter '" + parameter.name() + "' in function '" + name + "'" + context);
            }
        }
        checkTypeParameters(decorators);
    }

    private void checkTypeParameters(List<Decorator> decorators) {
        for (Decorator decorator : decorators) {
            if (decorator instanceof GenericDecorator(List<Type> typeParams)) {
                Set<String> names = new HashSet<>();
                for (Type typeParam : typeParams) {
                    if (typeParam instanceof NamedType(String name)) {
                        if (!names.add(name)) {
                            throw new AlpacaCompilerError("duplicate type parameter '" + name + "'");
                        }
                    }
                }
            }
        }
    }

    private static boolean hasDecorator(List<Decorator> decorators, Class<? extends Decorator> type) {
        return decorators.stream().anyMatch(type::isInstance);
    }

    // --------------------------------------------------------------------------
    // Pass 2: resolve statements and expressions
    // --------------------------------------------------------------------------

    private void resolve(List<Declaration> declarations) {
        for (Declaration declaration : declarations) {
            switch (declaration) {
                case ObjectDeclaration object -> resolveObject(object);
                case FunctionDeclaration function -> resolveFunction(function);
                case GlobalVariable global -> global.initializer().ifPresent(this::resolveExpression);
                case StatementDeclaration _ -> { /* rejected in pass 1 */ }
            }
        }
    }

    private void resolveObject(ObjectDeclaration object) {
        String outer = currentObject;
        currentObject = object.name();
        for (Field field : object.fields()) {
            field.initializer().ifPresent(this::resolveExpression);
        }
        for (FunctionDeclaration method : object.methods()) {
            resolveFunction(method);
        }
        object.constructor().ifPresent(this::resolveConstructor);
        currentObject = outer;
    }

    private void resolveFunction(FunctionDeclaration function) {
        if (function.body().isEmpty()) {
            return;
        }
        symbols.pushScope();
        for (Parameter parameter : function.parameters()) {
            symbols.defineLocal(new Symbol(parameter.name(), Kind.VARIABLE, parameter.type(),
                    Visibility.PUBLIC, false, null));
        }
        resolveStatement(function.body().get());
        symbols.popScope();
    }

    private void resolveConstructor(ConstructDecl constructor) {
        symbols.pushScope();
        for (Parameter parameter : constructor.parameters()) {
            symbols.defineLocal(new Symbol(parameter.name(), Kind.VARIABLE, parameter.type(),
                    Visibility.PUBLIC, false, null));
        }
        resolveStatement(constructor.body());
        symbols.popScope();
    }

    private void resolveStatement(Statement statement) {
        switch (statement) {
            case Block block -> {
                symbols.pushScope();
                for (Statement inner : block.statements()) {
                    resolveStatement(inner);
                }
                symbols.popScope();
            }
            case IfStatement ifStatement -> {
                resolveExpression(ifStatement.condition());
                resolveStatement(ifStatement.thenBranch());
                ifStatement.elseBranch().ifPresent(this::resolveStatement);
            }
            case WhileStatement whileStatement -> {
                resolveExpression(whileStatement.condition());
                resolveStatement(whileStatement.body());
            }
            case DoWhileStatement doWhileStatement -> {
                resolveStatement(doWhileStatement.body());
                resolveExpression(doWhileStatement.condition());
            }
            case ForStatement forStatement -> {
                symbols.pushScope();
                forStatement.init().ifPresent(this::resolveStatement);
                forStatement.condition().ifPresent(this::resolveExpression);
                forStatement.update().ifPresent(this::resolveStatement);
                resolveStatement(forStatement.body());
                symbols.popScope();
            }
            case SwitchStatement switchStatement -> {
                resolveExpression(switchStatement.selector());
                for (SwitchCase switchCase : switchStatement.cases()) {
                    if (switchCase.label() != null) {
                        resolveExpression(switchCase.label());
                    }
                    resolveStatement(switchCase.body());
                }
            }
            case SimpleStatement simple -> resolveSimpleStatement(simple);
        }
    }

    private void resolveSimpleStatement(SimpleStatement statement) {
        switch (statement) {
            case LocalVariable local -> {
                if (!symbols.defineLocal(new Symbol(local.name(), Kind.VARIABLE, local.type(),
                        Visibility.PUBLIC, false, null))) {
                    throw new AlpacaCompilerError("duplicate local variable '" + local.name() + "'");
                }
                local.initializer().ifPresent(this::resolveExpression);
            }
            case Assignment assignment -> resolveAssignment(assignment);
            case ExpressionStatement expressionStatement -> resolveExpression(expressionStatement.expression());
            case ReturnStatement returnStatement -> returnStatement.value().ifPresent(this::resolveExpression);
            case UnsafeStatement _ -> { /* raw C code: not analyzed */ }
            case BreakStatement _ -> { /* loop-context check is a later milestone */ }
            case ContinueStatement _ -> { /* loop-context check is a later milestone */ }
        }
    }

    private void resolveAssignment(Assignment assignment) {
        resolveExpression(assignment.value());
        switch (assignment.target()) {
            case Identifier identifier -> {
                Symbol symbol = lookupVariable(identifier.name());
                resolved.put(identifier, symbol);
                checkFieldConflict(identifier, symbol);
                if (symbol.constant()) {
                    throw new AlpacaCompilerError(
                            "cannot assign to constant " + constantTargetName(assignment.target(), symbol));
                }
            }
            case MemberAccess memberAccess -> {
                Symbol symbol = resolveExpression(memberAccess);
                if (symbol != null && symbol.constant()) {
                    throw new AlpacaCompilerError(
                            "cannot assign to constant " + constantTargetName(assignment.target(), symbol));
                }
            }
            case IndexAccess indexAccess -> resolveExpression(indexAccess);
            default -> throw new AlpacaCompilerError("invalid assignment target");
        }
    }

    private Symbol resolveExpression(Expression expression) {
        Symbol symbol = resolveExpressionInner(expression);
        if (symbol != null) {
            resolved.put(expression, symbol);
        }
        return symbol;
    }

    /**
     * Resolves an expression and returns the symbol it refers to, or {@code null}
     * when the expression is not statically resolvable to a single declaration
     * (literals, call results, computed values). Undefined names throw.
     */
    private Symbol resolveExpressionInner(Expression expression) {
        switch (expression) {
            case Literal _ -> {
                return null;
            }
            case Identifier identifier -> {
                return lookupVariable(identifier.name());
            }
            case ThisExpression _ -> throw new AlpacaCompilerError("'this' can only be used with member access (this.field)");
            case FunctionCall call -> {
                resolveCall(call);
                return null;
            }
            case UnaryOp unary -> {
                resolveExpression(unary.operand());
                return null;
            }
            case BinaryOp binary -> {
                resolveExpression(binary.left());
                resolveExpression(binary.right());
                return null;
            }
            case IsExpression isExpression -> {
                resolveExpression(isExpression.expression());
                return null; // target type resolution is part of the type checker
            }
            case TupleExpression tuple -> {
                for (Expression element : tuple.elements()) {
                    resolveExpression(element);
                }
                return null;
            }
            case ArrayLiteral array -> {
                for (Expression element : array.elements()) {
                    resolveExpression(element);
                }
                return null;
            }
            case MemberAccess memberAccess -> {
                return resolveMemberAccess(memberAccess);
            }
            case IndexAccess indexAccess -> {
                resolveExpression(indexAccess.array());
                resolveExpression(indexAccess.index());
                return null;
            }
            case PostfixOp postfix -> {
                Symbol symbol = resolveExpression(postfix.operand());
                checkFieldConflict(postfix.operand(), symbol);
                if (symbol != null && symbol.constant()) {
                    throw new AlpacaCompilerError(
                            "cannot assign to constant " + constantTargetName(postfix.operand(), symbol));
                }
                return null;
            }
        }
    }

    private void resolveCall(FunctionCall call) {
        switch (call.callee()) {
            case Identifier identifier -> {
                Symbol symbol = symbols.lookup(identifier.name());
                if (symbol == null) {
                    throw new AlpacaCompilerError("undefined function '" + identifier.name() + "'");
                }
                if (symbol.kind() == Kind.OBJECT) {
                    // Construction call: Point(...) - validated here, typed by the type checker.
                    checkConstructorCall(identifier, symbol);
                    resolved.put(identifier, symbol);
                }
                else if (symbol.kind() != Kind.FUNCTION) {
                    throw new AlpacaCompilerError("cannot call '" + identifier.name() + "': not a function");
                }
            }
            case MemberAccess memberAccess -> {
                Symbol symbol = resolveMemberAccess(memberAccess);
                if (symbol != null) {
                    resolved.put(memberAccess, symbol);
                }
                if (symbol != null && symbol.kind() != Kind.METHOD) {
                    throw new AlpacaCompilerError(
                            "cannot call '" + memberAccess.member() + "': not a method");
                }
            }
            default -> resolveExpression(call.callee());
        }
        for (Expression argument : call.arguments()) {
            resolveExpression(argument);
        }
    }

    /**
     * Validates a construction call {@code Point(...)}: the object must declare
     * a constructor, and a private constructor is only accessible from inside
     * the declaring object. Arity and argument types are checked by the type
     * checker (milestone 2).
     */
    private void checkConstructorCall(Identifier callee, Symbol objectSymbol) {
        Map<String, Symbol> members = symbols.lookupObject(objectSymbol.name());
        Symbol constructor = members != null ? members.get("construct") : null;
        if (constructor == null || constructor.kind() != Kind.CONSTRUCTOR) {
            throw new AlpacaCompilerError(
                    "object '" + objectSymbol.name() + "' has no constructor and cannot be constructed");
        }
        if (constructor.visibility() == Visibility.PRIVATE && !objectSymbol.name().equals(currentObject)) {
            throw new AlpacaCompilerError(
                    "cannot access private constructor of object '" + objectSymbol.name() + "'");
        }
    }

    private Symbol resolveMemberAccess(MemberAccess memberAccess) {
        if (memberAccess.object() instanceof ThisExpression) {
            return resolveThisMember(memberAccess);
        }
        Symbol receiver = resolveExpression(memberAccess.object());
        if (receiver == null || !(receiver.type() instanceof NamedType(String name))) {
            return null; // receiver type not statically known: checked by the type checker
        }
        Map<String, Symbol> members = symbols.lookupObject(name);
        if (members == null) {
            return null; // primitive or unknown type: checked by the type checker
        }
        Symbol member = members.get(memberAccess.member());
        if (member == null) {
            throw new AlpacaCompilerError(
                    "unknown member '" + memberAccess.member() + "' on object '" + name + "'");
        }
        if (member.visibility() == Visibility.PRIVATE && !name.equals(currentObject)) {
            throw new AlpacaCompilerError(
                    "cannot access private member '" + memberAccess.member() + "' of object '" + name + "'");
        }
        return member;
    }

    /**
     * Resolves {@code this.member}: the member is always looked up on the current
     * object, so private members are accessible and no visibility check applies.
     */
    private Symbol resolveThisMember(MemberAccess memberAccess) {
        if (currentObject == null) {
            throw new AlpacaCompilerError("'this' can only be used inside an object");
        }
        Map<String, Symbol> members = symbols.lookupObject(currentObject);
        Symbol member = members != null ? members.get(memberAccess.member()) : null;
        if (member == null) {
            throw new AlpacaCompilerError(
                    "unknown member '" + memberAccess.member() + "' on object '" + currentObject + "'");
        }
        return member;
    }

    private Symbol lookupVariable(String name) {
        Symbol symbol = symbols.lookup(name);
        if (symbol != null) {
            return switch (symbol.kind()) {
                case VARIABLE -> symbol;
                case FUNCTION -> throw new AlpacaCompilerError("cannot use function '" + name + "' as a value");
                case OBJECT -> throw new AlpacaCompilerError("cannot use object '" + name + "' as a value");
                case TYPE_PARAMETER ->
                        throw new AlpacaCompilerError("cannot use type parameter '" + name + "' as a value");
                case FIELD, METHOD, CONSTRUCTOR -> throw new AlpacaCompilerError("undefined variable '" + name + "'");
            };
        }
        // Implicit field access: inside an object (field initializers and method
        // bodies) an unqualified name that is not a variable falls back to a
        // field of that object.
        Symbol field = lookupOwnField(name);
        if (field != null) {
            return field;
        }
        throw new AlpacaCompilerError("undefined variable '" + name + "'");
    }

    /**
     * A write ({@code name = ...}, {@code name++}) to a bare name that resolves to
     * a variable while a field of the same name exists is ambiguous: the writer
     * must disambiguate explicitly with {@code this.name}. Reads are unaffected;
     * they prefer the variable.
     */
    private void checkFieldConflict(Expression target, Symbol symbol) {
        if (target instanceof Identifier(String name)
                && symbol != null && symbol.kind() == Kind.VARIABLE
                && lookupOwnField(name) != null) {
            throw new AlpacaCompilerError("ambiguous reference '" + name
                    + "': variable and field of object '" + currentObject
                    + "'; use 'this." + name + "' for the field");
        }
    }

    /**
     * Returns the field of the current object with the given name, or {@code null}.
     */
    private Symbol lookupOwnField(String name) {
        if (currentObject == null) {
            return null;
        }
        Map<String, Symbol> members = symbols.lookupObject(currentObject);
        if (members == null) {
            return null;
        }
        Symbol field = members.get(name);
        return field != null && field.kind() == Kind.FIELD ? field : null;
    }

    private static String constantTargetName(Expression target, Symbol symbol) {
        String name = switch (target) {
            case Identifier identifier -> identifier.name();
            case MemberAccess memberAccess -> memberAccess.member();
            default -> "value";
        };
        if (symbol != null && symbol.kind() == Kind.FIELD) {
            return "field '" + name + "'";
        }
        return "'" + name + "'";
    }
}
