package net.rusrusme.alpacac;

import net.rusrusme.alpacac.SymbolTable.Kind;
import net.rusrusme.alpacac.SymbolTable.Symbol;
import net.rusrusme.alpacac.SyntaxTree.*;

import java.util.*;

/**
 * Type checker for Alpaca (milestone 2). Runs after the SemanticAnalyzer (M1)
 * and consumes its symbol resolution: expressions that M1 resolved statically
 * (identifiers, member accesses) are typed via {@link SemanticAnalyzer#resolvedSymbols()};
 * expressions M1 deliberately left unchecked (member access on call results,
 * computed values) are typed and validated here.
 * <p>
 * Checks: assignment compatibility, return statements against the declared
 * return type, call arity and argument types, boolean conditions, array index
 * access, member access on statically unknown receivers, operator operand
 * types. `is`-type narrowing in if branches is a later milestone.
 */
public final class TypeChecker {
    private final SymbolTable symbols;
    private final Map<Expression, Symbol> resolved;

    /**
     * Types computed for every checked expression, keyed by node identity.
     * Purely additive side info for the monomorphizer (milestone 3); the
     * checking behaviour itself is unchanged.
     */
    private final Map<Expression, Type> expressionTypes = new IdentityHashMap<>();

    private final Map<String, FunctionDeclaration> functions = new HashMap<>();
    private final Map<String, Map<String, FunctionDeclaration>> methods = new HashMap<>();
    private final Map<String, ConstructDecl> constructors = new HashMap<>();

    /**
     * All type parameter names declared via {@code @generic} across the program.
     * Types named after a type parameter are unconstrained until monomorphization.
     */
    private final Set<String> typeParams = new HashSet<>();

    private String currentObject;
    private Type currentReturnType;

    public TypeChecker(SymbolTable symbols, Map<Expression, Symbol> resolved) {
        this.symbols = symbols;
        this.resolved = resolved;
    }

    /**
     * The type computed for each checked expression (identity-keyed), including
     * expected-type inference on construction calls of generic objects. Consumed
     * by the monomorphizer (milestone 3) to deduce type parameter bindings.
     */
    public Map<Expression, Type> expressionTypes() {
        return expressionTypes;
    }

    public void check(Program program) {
        collect(program.declarations());
        for (Declaration declaration : program.declarations()) {
            switch (declaration) {
                case ObjectDeclaration object -> checkObject(object);
                case FunctionDeclaration function -> checkFunction(function);
                case GlobalVariable global -> global.initializer().ifPresent(init ->
                        requireAssignable(init, global.type(),
                                "initializer of global '" + global.name() + "'"));
                case StatementDeclaration _ -> { /* rejected in M1 */ }
            }
        }
    }

    // --------------------------------------------------------------------------
    // Declaration collection (for arity and parameter types)
    // --------------------------------------------------------------------------

    private void collect(List<Declaration> declarations) {
        for (Declaration declaration : declarations) {
            switch (declaration) {
                case FunctionDeclaration function -> {
                    functions.put(function.name(), function);
                    typeParams.addAll(typeParameterNames(function.decorators()));
                }
                case ObjectDeclaration object -> {
                    Map<String, FunctionDeclaration> objectMethods = new HashMap<>();
                    for (FunctionDeclaration method : object.methods()) {
                        objectMethods.put(method.name(), method);
                    }
                    methods.put(object.name(), objectMethods);
                    object.constructor().ifPresent(constructor -> constructors.put(object.name(), constructor));
                    typeParams.addAll(typeParameterNames(object.decorators()));
                }
                case GlobalVariable _ -> { /* no declaration info needed */ }
                case StatementDeclaration _ -> { /* rejected in M1 */ }
            }
        }
    }

    private static List<String> typeParameterNames(List<Decorator> decorators) {
        for (Decorator decorator : decorators) {
            if (decorator instanceof GenericDecorator(List<Type> typeParams)) {
                return typeParams.stream()
                        .filter(type -> type instanceof NamedType)
                        .map(type -> ((NamedType) type).name())
                        .toList();
            }
        }
        return List.of();
    }

    // --------------------------------------------------------------------------
    // Objects and functions
    // --------------------------------------------------------------------------

    private void checkObject(ObjectDeclaration object) {
        String outer = currentObject;
        currentObject = object.name();
        for (Field field : object.fields()) {
            field.initializer().ifPresent(init ->
                    requireAssignable(init, field.type(),
                            "initializer of field '" + field.name() + "'"));
        }
        for (FunctionDeclaration method : object.methods()) {
            checkFunction(method);
        }
        object.constructor().ifPresent(this::checkConstructor);
        currentObject = outer;
    }

    private void checkFunction(FunctionDeclaration function) {
        if (function.body().isEmpty()) {
            return;
        }
        Type outerReturnType = currentReturnType;
        currentReturnType = function.returnType();
        checkStatement(function.body().get());
        currentReturnType = outerReturnType;
    }

    /**
     * A constructor behaves like a void function: {@code return;} is allowed,
     * returning a value is not.
     */
    private void checkConstructor(ConstructDecl constructor) {
        Type outerReturnType = currentReturnType;
        currentReturnType = new NamedType("void");
        checkStatement(constructor.body());
        currentReturnType = outerReturnType;
    }

    // --------------------------------------------------------------------------
    // Statements
    // --------------------------------------------------------------------------

    private void checkStatement(Statement statement) {
        switch (statement) {
            case Block block -> {
                for (Statement inner : block.statements()) {
                    checkStatement(inner);
                }
            }
            case IfStatement ifStatement -> {
                requireBool(checkExpression(ifStatement.condition()), "if condition");
                checkStatement(ifStatement.thenBranch());
                ifStatement.elseBranch().ifPresent(this::checkStatement);
            }
            case WhileStatement whileStatement -> {
                requireBool(checkExpression(whileStatement.condition()), "while condition");
                checkStatement(whileStatement.body());
            }
            case DoWhileStatement doWhileStatement -> {
                checkStatement(doWhileStatement.body());
                requireBool(checkExpression(doWhileStatement.condition()), "do-while condition");
            }
            case ForStatement forStatement -> {
                forStatement.init().ifPresent(this::checkSimpleStatement);
                forStatement.condition().ifPresent(condition -> requireBool(checkExpression(condition), "for condition"));
                forStatement.update().ifPresent(this::checkSimpleStatement);
                checkStatement(forStatement.body());
            }
            case SwitchStatement switchStatement -> {
                checkExpression(switchStatement.selector());
                for (SwitchCase switchCase : switchStatement.cases()) {
                    if (switchCase.label() != null) {
                        checkExpression(switchCase.label());
                    }
                    checkStatement(switchCase.body());
                }
            }
            case SimpleStatement simple -> checkSimpleStatement(simple);
        }
    }

    private void checkSimpleStatement(SimpleStatement statement) {
        switch (statement) {
            case LocalVariable local -> local.initializer().ifPresent(init ->
                    requireAssignable(init, local.type(),
                            "initializer of variable '" + local.name() + "'"));
            case Assignment assignment -> {
                Type targetType = checkExpression(assignment.target());
                requireAssignable(assignment.value(), targetType,
                        "assignment to '" + targetName(assignment.target()) + "'");
            }
            case ExpressionStatement expressionStatement -> checkExpression(expressionStatement.expression());
            case ReturnStatement returnStatement -> {
                if (returnStatement.value().isEmpty()) {
                    if (!isVoid(currentReturnType)) {
                        throw new AlpacaCompilerError(
                                "missing return value in function returning " + typeName(currentReturnType));
                    }
                }
                else if (isVoid(currentReturnType)) {
                    throw new AlpacaCompilerError("cannot return a value from a void function");
                }
                else {
                    requireAssignable(returnStatement.value().get(), currentReturnType, "return statement");
                }
            }
            case UnsafeStatement _ -> { /* raw C code: not type-checked */ }
            case BreakStatement _ -> { /* loop-context check is a later milestone */ }
            case ContinueStatement _ -> { /* loop-context check is a later milestone */ }
        }
    }

    // --------------------------------------------------------------------------
    // Expressions
    // --------------------------------------------------------------------------

    private Type checkExpression(Expression expression) {
        return checkExpression(expression, null);
    }

    /**
     * Checks an expression and returns its type. {@code expectedType} is the
     * type the surrounding context requires (initializer, assignment target,
     * argument, return value); it is only consumed by construction calls of
     * generic objects (expected-type inference, see {@link #checkCall}).
     * All other expressions ignore it.
     * <p>
     * Records the computed type for every checked expression in
     * {@link #expressionTypes()} (additive side info for the monomorphizer).
     */
    private Type checkExpression(Expression expression, Type expectedType) {
        Type type = checkExpressionInner(expression, expectedType);
        expressionTypes.put(expression, type);
        return type;
    }

    private Type checkExpressionInner(Expression expression, Type expectedType) {
        switch (expression) {
            case IntLiteral _ -> {
                return new NamedType("int");
            }
            case FloatLiteral _ -> {
                return new NamedType("float");
            }
            case BoolLiteral _ -> {
                return new NamedType("bool");
            }
            case CharLiteral _ -> {
                return new NamedType("char");
            }
            case StringLiteral _ -> {
                return new NamedType("string");
            }
            case Identifier identifier -> {
                Symbol symbol = resolved.get(identifier);
                if (symbol == null || symbol.type() == null) {
                    throw new AlpacaCompilerError("cannot determine type of '" + identifier.name() + "'");
                }
                return symbol.type();
            }
            case ThisExpression _ ->
                    throw new AlpacaCompilerError("'this' can only be used with member access (this.field)");
            case UnaryOp unary -> {
                Type operandType = checkExpression(unary.operand());
                switch (unary.operator()) {
                    case "!" -> {
                        requireBool(operandType, "unary '!'");
                        return new NamedType("bool");
                    }
                    case "-" -> {
                        requireNumeric(operandType, "unary '-'");
                        return operandType;
                    }
                    default -> throw new AlpacaCompilerError("unknown unary operator '" + unary.operator() + "'");
                }
            }
            case BinaryOp binary -> {
                Type leftType = checkExpression(binary.left());
                Type rightType = checkExpression(binary.right());
                switch (binary.operator()) {
                    case "&&", "||" -> {
                        requireBool(leftType, "logical '" + binary.operator() + "'");
                        requireBool(rightType, "logical '" + binary.operator() + "'");
                        return new NamedType("bool");
                    }
                    case "==", "!=" -> {
                        if (!sameOrBothNumeric(leftType, rightType)) {
                            throw new AlpacaCompilerError("cannot compare " + typeName(leftType)
                                    + " with " + typeName(rightType) + " using '" + binary.operator() + "'");
                        }
                        return new NamedType("bool");
                    }
                    case "<", ">", "<=", ">=" -> {
                        requireNumeric(leftType, "comparison '" + binary.operator() + "'");
                        requireNumeric(rightType, "comparison '" + binary.operator() + "'");
                        return new NamedType("bool");
                    }
                    case "+" -> {
                        if (isNamed(leftType, "string") && isNamed(rightType, "string")) {
                            return new NamedType("string"); // string concatenation
                        }
                        requireNumeric(leftType, "'+'");
                        requireNumeric(rightType, "'+'");
                        return numericResult(leftType, rightType);
                    }
                    case "-", "*", "/", "%" -> {
                        requireNumeric(leftType, "'" + binary.operator() + "'");
                        requireNumeric(rightType, "'" + binary.operator() + "'");
                        return numericResult(leftType, rightType);
                    }
                    default -> throw new AlpacaCompilerError("unknown binary operator '" + binary.operator() + "'");
                }
            }
            case FunctionCall call -> {
                return checkCall(call, expectedType);
            }
            case TupleExpression tuple -> {
                List<Type> elementTypes = new ArrayList<>();
                for (Expression element : tuple.elements()) {
                    elementTypes.add(checkExpression(element));
                }
                return new TupleType(elementTypes);
            }
            case ArrayLiteral array -> {
                if (array.elements().isEmpty()) {
                    // int[] a = {}; - the expected type supplies the element type.
                    if (expectedType instanceof ArrayType) {
                        return expectedType;
                    }
                    throw new AlpacaCompilerError("cannot infer the element type of an empty array literal");
                }
                Type elementType = checkExpression(array.elements().getFirst());
                for (Expression element : array.elements().subList(1, array.elements().size())) {
                    requireAssignable(element, elementType, "array element");
                }
                return new ArrayType(elementType);
            }
            case MemberAccess memberAccess -> {
                return checkMemberAccess(memberAccess);
            }
            case IsExpression isExpression -> {
                checkExpression(isExpression.expression());
                return new NamedType("bool");
            }
            case IndexAccess indexAccess -> {
                Type arrayType = checkExpression(indexAccess.array());
                if (!(arrayType instanceof ArrayType(Type elementType))) {
                    throw new AlpacaCompilerError("indexing a non-array value of type " + typeName(arrayType));
                }
                Type indexType = checkExpression(indexAccess.index());
                if (!isNamed(indexType, "int")) {
                    throw new AlpacaCompilerError("array index must be int, got " + typeName(indexType));
                }
                return elementType;
            }
            case PostfixOp postfix -> {
                Type operandType = checkExpression(postfix.operand());
                requireNumeric(operandType, "postfix '" + postfix.operator() + "'");
                return operandType;
            }
        }
    }

    private Type checkCall(FunctionCall call, Type expectedType) {
        switch (call.callee()) {
            case Identifier identifier -> {
                Symbol symbol = symbols.lookup(identifier.name());
                if (symbol == null) {
                    throw new AlpacaCompilerError("undefined function '" + identifier.name() + "'");
                }
                if (symbol.kind() == Kind.OBJECT) {
                    // Construction call: Point(...) - M1 already validated the
                    // constructor's existence and visibility; type it here.
                    ConstructDecl constructor = constructors.get(identifier.name());
                    if (constructor != null) {
                        checkArguments(call, constructor.parameters(), identifier.name());
                    }
                    // Expected-type inference for generic objects:
                    // Array<int> arr = Array("Hi"); types the call as Array<int>.
                    // A mismatching base name falls through to the raw object type.
                    if (expectedType instanceof GenericType(
                            String base, List<Type> args
                    ) && base.equals(identifier.name())) {
                        return expectedType;
                    }
                    return new NamedType(identifier.name());
                }
                if (symbol.kind() != Kind.FUNCTION) {
                    throw new AlpacaCompilerError("cannot call '" + identifier.name() + "': not a function");
                }
                FunctionDeclaration function = functions.get(identifier.name());
                if (function == null) {
                    return symbol.type();
                }
                checkArguments(call, function.parameters(), identifier.name());
                return function.returnType();
            }
            case MemberAccess memberAccess -> {
                Symbol symbol = resolved.get(memberAccess);
                if (symbol == null) {
                    // Receiver type not statically known to M1 (e.g. a call result)
                    Type receiverType = checkExpression(memberAccess.object());
                    Symbol resolvedMember = resolveMemberSymbol(memberAccess, receiverType);
                    if (resolvedMember.kind() != Kind.METHOD) {
                        throw new AlpacaCompilerError(
                                "cannot call '" + memberAccess.member() + "': not a method");
                    }
                    return checkMethodCall(call, resolvedMember);
                }
                if (symbol.kind() != Kind.METHOD) {
                    throw new AlpacaCompilerError("cannot call '" + memberAccess.member() + "': not a method");
                }
                return checkMethodCall(call, symbol);
            }
            default -> {
                checkExpression(call.callee());
                throw new AlpacaCompilerError("cannot call a non-function value");
            }
        }
    }

    private Type checkMethodCall(FunctionCall call, Symbol method) {
        String objectName = method.declaringObject();
        Map<String, FunctionDeclaration> objectMethods = methods.get(objectName);
        FunctionDeclaration declaration = objectMethods != null ? objectMethods.get(method.name()) : null;
        if (declaration == null) {
            return method.type();
        }
        checkArguments(call, declaration.parameters(), objectName + "." + method.name());
        return declaration.returnType();
    }

    private void checkArguments(FunctionCall call, List<Parameter> parameters, String calleeName) {
        if (call.arguments().size() != parameters.size()) {
            throw new AlpacaCompilerError("function '" + calleeName + "' expects " + parameters.size()
                    + " arguments but got " + call.arguments().size());
        }
        for (int i = 0; i < parameters.size(); i++) {
            requireAssignable(call.arguments().get(i), parameters.get(i).type(),
                    "argument " + (i + 1) + " of '" + calleeName + "'");
        }
    }

    private Type checkMemberAccess(MemberAccess memberAccess) {
        Symbol member = resolved.get(memberAccess);
        if (member != null) {
            if (member.kind() == Kind.METHOD) {
                throw new AlpacaCompilerError("cannot use method '" + memberAccess.member() + "' as a value");
            }
            return member.type();
        }
        // Receiver type not statically known to M1 (e.g. a call result)
        Type receiverType = checkExpression(memberAccess.object());
        Symbol resolvedMember = resolveMemberSymbol(memberAccess, receiverType);
        if (resolvedMember.kind() == Kind.METHOD) {
            throw new AlpacaCompilerError("cannot use method '" + memberAccess.member() + "' as a value");
        }
        return resolvedMember.type();
    }

    /**
     * Resolves a member on a receiver whose type is only known to the type checker,
     * including the visibility check (mirrors the M1 rules for static receivers).
     * Generic types resolve via their base object ({@code Array<E>} -> {@code Array}).
     */
    private Symbol resolveMemberSymbol(MemberAccess memberAccess, Type receiverType) {
        String objectName = null;
        if (receiverType instanceof NamedType(String name)) {
            objectName = name;
        }
        else if (receiverType instanceof GenericType(String base, List<Type> args)) {
            objectName = base;
        }
        if (objectName != null) {
            Map<String, Symbol> members = symbols.lookupObject(objectName);
            if (members != null) {
                Symbol member = members.get(memberAccess.member());
                if (member == null) {
                    throw new AlpacaCompilerError(
                            "unknown member '" + memberAccess.member() + "' on object '" + objectName + "'");
                }
                if (member.visibility() == Visibility.PRIVATE && !objectName.equals(currentObject)) {
                    throw new AlpacaCompilerError(
                            "cannot access private member '" + memberAccess.member() + "' of object '" + objectName + "'");
                }
                return member;
            }
        }
        throw new AlpacaCompilerError("cannot access member '" + memberAccess.member()
                + "' on a value of type " + typeName(receiverType));
    }

    // --------------------------------------------------------------------------
    // Type compatibility helpers
    // --------------------------------------------------------------------------

    /**
     * All numeric types with their widening rank (assigning rank A to rank E requires A <= E).
     */
    private static final Map<String, Integer> NUMERIC_RANK = Map.of(
            "int", 0,
            "unsigned int", 1,
            "long", 2,
            "unsigned long", 3,
            "float", 4,
            "double", 5);

    private boolean assignable(Type actual, Type expected) {
        if (Objects.equals(actual, expected)) {
            return true;
        }
        // Type parameters are unconstrained until monomorphization: E accepts
        // any value, and a value of type E is assignable to any target type.
        // The monomorphizer performs the concrete checks.
        if (isTypeParameter(actual) || isTypeParameter(expected)) {
            return true;
        }
        // Generic types with the same base are compatible argument-wise; type
        // parameters nested inside (e.g. Optional<E>) are unconstrained the
        // same way. Concrete mismatches (Array<int> vs Array<string>) stay errors.
        if (actual instanceof GenericType(String baseA, List<Type> argsA)
                && expected instanceof GenericType(String baseB, List<Type> argsB)
                && baseA.equals(baseB) && argsA.size() == argsB.size()) {
            for (int i = 0; i < argsA.size(); i++) {
                if (!assignable(argsA.get(i), argsB.get(i))) {
                    return false;
                }
            }
            return true;
        }
        // Numeric widening only (narrowing stays an error)
        int actualRank = numericRank(actual);
        int expectedRank = numericRank(expected);
        return actualRank >= 0 && expectedRank >= 0 && actualRank <= expectedRank;
    }

    private boolean isTypeParameter(Type type) {
        return type instanceof NamedType(String name) && typeParams.contains(name);
    }

    private void requireAssignable(Expression actualExpr, Type expected, String context) {
        Type actual = checkExpression(actualExpr, expected);
        checkUnsignedValue(actualExpr, expected, context);
        if (!assignable(actual, expected)) {
            throw new AlpacaCompilerError("type mismatch: cannot assign " + typeName(actual)
                    + " to " + typeName(expected) + " (" + context + ")");
        }
    }

    /**
     * Rejects statically negative constant values assigned to unsigned types
     * (e.g. {@code unsigned int x = -1;}). Non-constant values are left to
     * runtime semantics, as in C.
     */
    private void checkUnsignedValue(Expression actualExpr, Type expected, String context) {
        if (isUnsigned(expected) && isStaticallyNegative(actualExpr)) {
            throw new AlpacaCompilerError("cannot assign a negative value to "
                    + typeName(expected) + " (" + context + ")");
        }
    }

    private static boolean isStaticallyNegative(Expression expression) {
        return switch (expression) {
            case IntLiteral(long value) -> value < 0;
            case FloatLiteral(double value) -> value < 0;
            case UnaryOp unary -> unary.operator().equals("-") && isStaticallyPositive(unary.operand());
            default -> false;
        };
    }

    private static boolean isStaticallyPositive(Expression expression) {
        return switch (expression) {
            case IntLiteral(long value) -> value > 0;
            case FloatLiteral(double value) -> value > 0;
            case UnaryOp unary -> unary.operator().equals("-") && isStaticallyNegative(unary.operand());
            default -> false;
        };
    }

    private void requireBool(Type type, String context) {
        if (!isNamed(type, "bool")) {
            throw new AlpacaCompilerError(context + " must be bool, got " + typeName(type));
        }
    }

    private void requireNumeric(Type type, String context) {
        if (!isNumeric(type)) {
            throw new AlpacaCompilerError(context + " requires a numeric value, got " + typeName(type));
        }
    }

    private static boolean sameOrBothNumeric(Type left, Type right) {
        return Objects.equals(left, right) || (isNumeric(left) && isNumeric(right));
    }

    private static Type numericResult(Type left, Type right) {
        int rank = Math.max(numericRank(left), numericRank(right));
        return switch (rank) {
            case 0 -> new NamedType("int");
            case 1 -> new NamedType("unsigned int");
            case 2 -> new NamedType("long");
            case 3 -> new NamedType("unsigned long");
            case 4 -> new NamedType("float");
            default -> new NamedType("double");
        };
    }

    private static int numericRank(Type type) {
        if (type instanceof NamedType(String name)) {
            Integer rank = NUMERIC_RANK.get(name);
            if (rank != null) {
                return rank;
            }
        }
        return -1; // not numeric
    }

    private static boolean isNumeric(Type type) {
        return numericRank(type) >= 0;
    }

    private static boolean isNamed(Type type, String name) {
        return type instanceof NamedType(String typeName) && typeName.equals(name);
    }

    private static boolean isUnsigned(Type type) {
        return isNamed(type, "unsigned int") || isNamed(type, "unsigned long");
    }

    private static boolean isVoid(Type type) {
        return isNamed(type, "void");
    }

    private static String targetName(Expression target) {
        return switch (target) {
            case Identifier identifier -> identifier.name();
            case MemberAccess memberAccess -> memberAccess.member();
            case IndexAccess indexAccess -> "index access";
            default -> "value";
        };
    }

    private static String typeName(Type type) {
        return switch (type) {
            case NamedType(String name) -> name;
            case ArrayType(Type element) -> typeName(element) + "[]";
            case TupleType(List<Type> elements) -> {
                StringBuilder sb = new StringBuilder("(");
                for (int i = 0; i < elements.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(typeName(elements.get(i)));
                }
                yield sb.append(")").toString();
            }
            case GenericType(String base, List<Type> args) -> {
                StringBuilder sb = new StringBuilder(base).append("<");
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(typeName(args.get(i)));
                }
                yield sb.append(">").toString();
            }
            case FunctionType(List<Type> paramTypes, Type returnType) -> "function";
        };
    }
}
