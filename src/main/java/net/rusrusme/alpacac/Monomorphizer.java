package net.rusrusme.alpacac;

import net.rusrusme.alpacac.SymbolTable.Kind;
import net.rusrusme.alpacac.SymbolTable.Symbol;
import net.rusrusme.alpacac.SyntaxTree.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Monomorphizer for Alpaca (milestone 3). Turns {@code @generic} declarations
 * into concrete copies: every use of a generic function or object with concrete
 * type arguments is instantiated, type parameters are substituted, and the
 * result is a generic-free program for the C emitter (milestone 4).
 * <p>
 * Input: the checked program plus the side info collected by the earlier
 * passes ({@link SemanticAnalyzer#resolvedSymbols()} and
 * {@link TypeChecker#expressionTypes()}); output: a concrete program that can
 * be re-analyzed by M1/M2, which then fail on any leftover generic construct.
 * The monomorphizer itself computes no expression types - it only reads the
 * types the type checker already recorded (check-then-monomorphize) and
 * applies the current substitution to them.
 * <p>
 * Instantiation is demand-driven: a worklist is seeded from all non-generic
 * code (function bodies, object method bodies, global initializers) and from
 * every generic type that appears in a declaration; each instantiation may
 * pull in further instantiations (generic functions calling generic functions,
 * generic objects referencing generic objects). Generic declarations that are
 * never instantiated are dropped.
 * <p>
 * Concrete copies are named {@code name$T1$T2...} with {@code $} as the
 * separator (Alpaca identifiers cannot contain {@code $}, so the mangled names
 * cannot collide with user code). Every {@code GenericType} in a declaration
 * position becomes a {@code NamedType} with the mangled name, so the output
 * references only objects that actually exist.
 */
public final class Monomorphizer {
    private final SymbolTable symbols;
    private final Map<Expression, Symbol> resolved;
    private final Map<Expression, Type> expressionTypes;

    /** Generic function and object declarations by name. */
    private final Map<String, FunctionDeclaration> genericFunctions = new HashMap<>();
    private final Map<String, ObjectDeclaration> genericObjects = new HashMap<>();
    /** Declared type parameter names of each generic declaration. */
    private final Map<String, List<String>> genericTypeParams = new HashMap<>();
    /** All type parameter names across the program (for concreteness checks). */
    private final Set<String> typeParamNames = new HashSet<>();

    /** Concrete clones of generic functions, keyed by mangled name. */
    private final Map<String, FunctionDeclaration> functionInstances = new LinkedHashMap<>();
    /** Concrete clones of generic objects, keyed by mangled name. */
    private final Map<String, ObjectDeclaration> objectInstances = new LinkedHashMap<>();
    /** Mangled clone names per original generic declaration, in creation order. */
    private final Map<String, List<String>> clonesByOriginal = new HashMap<>();
    /** Pending instantiations, drained until empty (fixpoint). */
    private final Deque<Pending> worklist = new ArrayDeque<>();

    private sealed interface Pending permits PendingFunction, PendingObject {}

    private record PendingFunction(FunctionDeclaration declaration, Map<String, Type> substitution, String mangledName)
            implements Pending {}

    private record PendingObject(ObjectDeclaration declaration, Map<String, Type> substitution, String mangledName)
            implements Pending {}

    /** A transformed expression together with its concrete type under the active substitution. */
    private record Transformed(Expression expression, Type type) {}

    /** The target of a generic call: the mangled clone name and its concrete result type. */
    private record GenericCall(String mangledName, Type resultType) {}

    public Monomorphizer(SymbolTable symbols, Map<Expression, Symbol> resolved, Map<Expression, Type> expressionTypes) {
        this.symbols = symbols;
        this.resolved = resolved;
        this.expressionTypes = expressionTypes;
    }

    // --------------------------------------------------------------------------
    // Entry point
    // --------------------------------------------------------------------------

    public Program process(Program program) {
        collectGenericDeclarations(program.declarations());

        Map<Declaration, Declaration> transformed = new IdentityHashMap<>();
        for (Declaration declaration : program.declarations()) {
            switch (declaration) {
                case FunctionDeclaration function when isGeneric(function) -> {
                    // Instantiated on demand; clones are inserted at this position later.
                }
                case ObjectDeclaration object when isGeneric(object) -> {
                    // Instantiated on demand; clones are inserted at this position later.
                }
                case FunctionDeclaration function ->
                        transformed.put(function, transformFunction(function, Map.of()));
                case ObjectDeclaration object ->
                        transformed.put(object, transformObject(object));
                case GlobalVariable global ->
                        transformed.put(global, new GlobalVariable(
                                global.decorators(), transformDeclarationType(global.type(), Map.of()), global.name(),
                                global.initializer().map(init -> transformExpression(init, Map.of()).expression()),
                                global.visibility()));
                case StatementDeclaration _ -> { /* rejected by M1; cannot appear */ }
            }
        }
        drainWorklist();

        List<Declaration> output = new ArrayList<>();
        for (Declaration declaration : program.declarations()) {
            if (declaration instanceof FunctionDeclaration function && isGeneric(function)) {
                for (String mangled : clonesByOriginal.getOrDefault(function.name(), List.of())) {
                    output.add(functionInstances.get(mangled));
                }
            }
            else if (declaration instanceof ObjectDeclaration object && isGeneric(object)) {
                for (String mangled : clonesByOriginal.getOrDefault(object.name(), List.of())) {
                    output.add(objectInstances.get(mangled));
                }
            }
            else {
                output.add(transformed.get(declaration));
            }
        }
        return new Program(program.name(), List.of(), output);
    }

    private void collectGenericDeclarations(List<Declaration> declarations) {
        for (Declaration declaration : declarations) {
            if (declaration instanceof FunctionDeclaration function && isGeneric(function)) {
                genericFunctions.put(function.name(), function);
                List<String> typeParams = typeParameterNames(function.decorators());
                genericTypeParams.put(function.name(), typeParams);
                typeParamNames.addAll(typeParams);
            }
            else if (declaration instanceof ObjectDeclaration object && isGeneric(object)) {
                genericObjects.put(object.name(), object);
                List<String> typeParams = typeParameterNames(object.decorators());
                genericTypeParams.put(object.name(), typeParams);
                typeParamNames.addAll(typeParams);
            }
        }
    }

    private void drainWorklist() {
        while (!worklist.isEmpty()) {
            Pending pending = worklist.poll();
            switch (pending) {
                case PendingFunction function -> instantiateFunctionClone(function);
                case PendingObject object -> instantiateObjectClone(object);
            }
        }
    }

    private void instantiateFunctionClone(PendingFunction pending) {
        String mangled = pending.mangledName();
        if (functionInstances.containsKey(mangled)) {
            return; // already instantiated (possibly from another call site)
        }
        FunctionDeclaration original = pending.declaration();
        Map<String, Type> sigma = pending.substitution();
        Block body = original.body().map(block -> transformBlock(block, sigma)).orElse(null);
        functionInstances.put(mangled, new FunctionDeclaration(
                withoutGeneric(original.decorators()),
                transformDeclarationType(original.returnType(), sigma),
                mangled,
                transformParameters(original.parameters(), sigma),
                Optional.ofNullable(body),
                original.visibility()));
        clonesByOriginal.computeIfAbsent(original.name(), n -> new ArrayList<>()).add(mangled);
    }

    private void instantiateObjectClone(PendingObject pending) {
        String mangled = pending.mangledName();
        if (objectInstances.containsKey(mangled)) {
            return;
        }
        ObjectDeclaration original = pending.declaration();
        Map<String, Type> sigma = pending.substitution();
        List<Field> fields = original.fields().stream()
                .map(field -> new Field(field.decorators(),
                        transformDeclarationType(field.type(), sigma),
                        field.name(),
                        field.initializer().map(init -> transformExpression(init, sigma).expression()),
                        field.visibility()))
                .toList();
        List<FunctionDeclaration> methods = original.methods().stream()
                .map(method -> transformFunction(method, sigma))
                .toList();
        Optional<ConstructDecl> constructor = original.constructor().map(c -> new ConstructDecl(
                c.decorators(), c.visibility(), transformParameters(c.parameters(), sigma),
                transformBlock(c.body(), sigma)));
        objectInstances.put(mangled, new ObjectDeclaration(
                withoutGeneric(original.decorators()), mangled, fields, methods, constructor, original.visibility()));
        clonesByOriginal.computeIfAbsent(original.name(), n -> new ArrayList<>()).add(mangled);
    }

    // --------------------------------------------------------------------------
    // Declarations
    // --------------------------------------------------------------------------

    private FunctionDeclaration transformFunction(FunctionDeclaration function, Map<String, Type> sigma) {
        return new FunctionDeclaration(
                function.decorators(),
                transformDeclarationType(function.returnType(), sigma),
                function.name(),
                transformParameters(function.parameters(), sigma),
                function.body().map(body -> transformBlock(body, sigma)),
                function.visibility());
    }

    private ObjectDeclaration transformObject(ObjectDeclaration object) {
        if (isGeneric(object)) {
            return object; // replaced by its clones in the assembly
        }
        return new ObjectDeclaration(
                object.decorators(),
                object.name(),
                object.fields().stream().map(field -> new Field(
                        field.decorators(),
                        transformDeclarationType(field.type(), Map.of()),
                        field.name(),
                        field.initializer().map(init -> transformExpression(init, Map.of()).expression()),
                        field.visibility())).toList(),
                object.methods().stream().map(method -> transformFunction(method, Map.of())).toList(),
                object.constructor().map(constructor -> new ConstructDecl(
                        constructor.decorators(), constructor.visibility(),
                        transformParameters(constructor.parameters(), Map.of()),
                        transformBlock(constructor.body(), Map.of()))),
                object.visibility());
    }

    /**
     * Substitutes a type in a declaration position (local variable, field,
     * parameter, return type, global): applies the active substitution, pulls
     * in object instantiations for every concrete generic type it mentions,
     * and renders generic types as their mangled {@code NamedType} so the
     * output only references objects that exist.
     */
    private Type transformDeclarationType(Type type, Map<String, Type> sigma) {
        Type substituted = substitute(type, sigma);
        collectObjectInstantiations(substituted);
        return concretize(substituted);
    }

    // --------------------------------------------------------------------------
    // Statements
    // --------------------------------------------------------------------------

    private Block transformBlock(Block block, Map<String, Type> sigma) {
        List<Statement> statements = new ArrayList<>(block.statements().size());
        for (Statement statement : block.statements()) {
            statements.add(transformStatement(statement, sigma));
        }
        return new Block(statements);
    }

    private Statement transformStatement(Statement statement, Map<String, Type> sigma) {
        return switch (statement) {
            case Block block -> transformBlock(block, sigma);
            case IfStatement ifs -> new IfStatement(
                    transformExpression(ifs.condition(), sigma).expression(),
                    transformStatement(ifs.thenBranch(), sigma),
                    ifs.elseBranch().map(elseBranch -> transformStatement(elseBranch, sigma)));
            case WhileStatement whileStatement -> new WhileStatement(
                    transformExpression(whileStatement.condition(), sigma).expression(),
                    transformStatement(whileStatement.body(), sigma));
            case DoWhileStatement doWhileStatement -> new DoWhileStatement(
                    transformStatement(doWhileStatement.body(), sigma),
                    transformExpression(doWhileStatement.condition(), sigma).expression());
            case ForStatement forStatement -> new ForStatement(
                    forStatement.init().map(init -> transformSimple(init, sigma)),
                    forStatement.condition().map(condition -> transformExpression(condition, sigma).expression()),
                    forStatement.update().map(update -> transformSimple(update, sigma)),
                    transformStatement(forStatement.body(), sigma));
            case SwitchStatement switchStatement -> new SwitchStatement(
                    transformExpression(switchStatement.selector(), sigma).expression(),
                    switchStatement.cases().stream().map(switchCase -> new SwitchCase(
                            switchCase.label() != null
                                    ? transformExpression(switchCase.label(), sigma).expression()
                                    : null,
                            transformStatement(switchCase.body(), sigma))).toList());
            case SimpleStatement simple -> transformSimple(simple, sigma);
        };
    }

    private SimpleStatement transformSimple(SimpleStatement statement, Map<String, Type> sigma) {
        return switch (statement) {
            case LocalVariable local -> new LocalVariable(
                    transformDeclarationType(local.type(), sigma),
                    local.name(),
                    local.initializer().map(init -> transformExpression(init, sigma).expression()));
            case Assignment assignment -> new Assignment(
                    transformExpression(assignment.target(), sigma).expression(),
                    transformExpression(assignment.value(), sigma).expression());
            case ExpressionStatement expressionStatement -> new ExpressionStatement(
                    transformExpression(expressionStatement.expression(), sigma).expression());
            case ReturnStatement returnStatement -> new ReturnStatement(
                    returnStatement.value().map(value -> transformExpression(value, sigma).expression()));
            case UnsafeStatement unsafe -> new UnsafeStatement(unsafe.code());
            case BreakStatement breakStatement -> new BreakStatement();
            case ContinueStatement continueStatement -> new ContinueStatement();
        };
    }

    // --------------------------------------------------------------------------
    // Expressions
    // --------------------------------------------------------------------------

    private Transformed transformExpression(Expression expression, Map<String, Type> sigma) {
        return switch (expression) {
            // Leaves are copied: two instantiations of the same generic
            // declaration share the original body, and the re-analysis keys
            // its maps by node identity - shared nodes would collide (the
            // resolve of the second clone would overwrite the first one's).
            case Literal literal -> new Transformed(copyLiteral(literal), substitutedType(expression, sigma));
            case Identifier identifier -> new Transformed(new Identifier(identifier.name()), substitutedType(expression, sigma));
            case ThisExpression _ -> new Transformed(new ThisExpression(), null); // never used as a value, type unknown
            case UnaryOp unary -> {
                Transformed operand = transformExpression(unary.operand(), sigma);
                yield new Transformed(new UnaryOp(unary.operator(), operand.expression()), substitutedType(expression, sigma));
            }
            case BinaryOp binary -> {
                Transformed left = transformExpression(binary.left(), sigma);
                Transformed right = transformExpression(binary.right(), sigma);
                yield new Transformed(new BinaryOp(left.expression(), binary.operator(), right.expression()),
                        substitutedType(expression, sigma));
            }
            case FunctionCall call -> transformCall(call, sigma);
            case TupleExpression tuple -> {
                List<Expression> elements = new ArrayList<>(tuple.elements().size());
                for (Expression element : tuple.elements()) {
                    elements.add(transformExpression(element, sigma).expression());
                }
                yield new Transformed(new TupleExpression(elements), substitutedType(expression, sigma));
            }
            case ArrayLiteral array -> {
                List<Expression> elements = new ArrayList<>(array.elements().size());
                for (Expression element : array.elements()) {
                    elements.add(transformExpression(element, sigma).expression());
                }
                yield new Transformed(new ArrayLiteral(elements), substitutedType(expression, sigma));
            }
            case MemberAccess memberAccess -> {
                Transformed object = transformExpression(memberAccess.object(), sigma);
                yield new Transformed(new MemberAccess(object.expression(), memberAccess.member()),
                        substitutedType(expression, sigma));
            }
            case IsExpression isExpression -> new Transformed(
                    new IsExpression(transformExpression(isExpression.expression(), sigma).expression(),
                            transformDeclarationType(isExpression.targetType(), sigma)),
                    substitutedType(expression, sigma));
            case IndexAccess indexAccess -> {
                Transformed array = transformExpression(indexAccess.array(), sigma);
                Transformed index = transformExpression(indexAccess.index(), sigma);
                yield new Transformed(new IndexAccess(array.expression(), index.expression()),
                        substitutedType(expression, sigma));
            }
            case PostfixOp postfix -> {
                Transformed operand = transformExpression(postfix.operand(), sigma);
                yield new Transformed(new PostfixOp(postfix.operator(), operand.expression()),
                        substitutedType(expression, sigma));
            }
        };
    }

    private Transformed transformCall(FunctionCall call, Map<String, Type> sigma) {
        List<Transformed> args = call.arguments().stream()
                .map(argument -> transformExpression(argument, sigma))
                .toList();
        if (call.callee() instanceof Identifier identifier) {
            Symbol symbol = symbols.lookup(identifier.name());
            if (symbol != null && symbol.kind() == Kind.FUNCTION && genericFunctions.containsKey(symbol.name())) {
                GenericCall target = instantiateFunction(symbol.name(), args);
                return new Transformed(
                        new FunctionCall(new Identifier(target.mangledName()), argExpressions(args)),
                        target.resultType());
            }
            if (symbol != null && symbol.kind() == Kind.OBJECT && genericObjects.containsKey(symbol.name())) {
                return transformConstruction(symbol.name(), call, args, sigma);
            }
            // Non-generic function call or construction of a non-generic object.
            // The callee identifier is copied (see transformExpression: leaves
            // must not be shared between instantiations).
            return new Transformed(new FunctionCall(new Identifier(identifier.name()), argExpressions(args)),
                    substitutedType(call, sigma));
        }
        if (call.callee() instanceof MemberAccess memberAccess) {
            Transformed object = transformExpression(memberAccess.object(), sigma);
            if (object.type() instanceof GenericType(String base, List<Type> typeArgs)
                    && genericObjects.containsKey(base) && isConcrete(typeArgs)) {
                instantiateObject(base, typeArgs); // the receiver's object must exist in the output
            }
            return new Transformed(
                    new FunctionCall(new MemberAccess(object.expression(), memberAccess.member()), argExpressions(args)),
                    substitutedType(call, sigma));
        }
        return new Transformed(
                new FunctionCall(transformExpression(call.callee(), sigma).expression(), argExpressions(args)),
                substitutedType(call, sigma));
    }

    private static List<Expression> argExpressions(List<Transformed> args) {
        return args.stream().map(Transformed::expression).toList();
    }

    /**
     * The type the type checker recorded for an expression, with the active
     * substitution applied. Recorded types only mention type parameters of the
     * enclosing declaration, so the substitution makes them concrete.
     * <p>
     * Returns {@code null} for expressions the type checker never typed (the
     * receiver of a statically resolved member access, {@code this}): those
     * positions carry no type information, and their type is never consumed.
     */
    private Type substitutedType(Expression expression, Map<String, Type> sigma) {
        Type type = expressionTypes.get(expression);
        return type == null ? null : substitute(type, sigma);
    }

    // --------------------------------------------------------------------------
    // Generic object instantiation
    // --------------------------------------------------------------------------

    /**
     * Handles the construction call of a generic object. The type parameter
     * binding comes from the call's recorded type (the type checker's
     * expected-type inference, e.g. {@code Array<int> arr = Array(10)} makes
     * the call {@code Array<int>}); without a generic type context the binding
     * is deduced from the constructor arguments instead.
     */
    private Transformed transformConstruction(String objectName, FunctionCall call,
                                              List<Transformed> args, Map<String, Type> sigma) {
        Type recorded = substitutedType(call, sigma);
        if (recorded instanceof GenericType(String base, List<Type> typeArgs) && base.equals(objectName)) {
            if (!isConcrete(typeArgs)) {
                throw new AlpacaCompilerError("cannot deduce the type arguments of construction '"
                        + objectName + "(...)': no concrete type context is available here");
            }
            String mangled = instantiateObject(objectName, typeArgs);
            return new Transformed(new FunctionCall(new Identifier(mangled), argExpressions(args)), recorded);
        }
        // Raw construction (no generic type context): deduce from the arguments.
        ObjectDeclaration object = genericObjects.get(objectName);
        ConstructDecl constructor = object.constructor().orElseThrow(() ->
                new AlpacaCompilerError("internal error: object '" + objectName + "' has no constructor"));
        Map<String, Type> binding = deduceBinding(constructor.parameters(), args,
                genericTypeParams.get(objectName), objectName);
        String mangled = mangleObjectName(objectName, genericTypeParams.get(objectName), binding);
        if (!objectInstances.containsKey(mangled)) {
            worklist.add(new PendingObject(object, binding, mangled));
        }
        Type resultType = new GenericType(objectName, typeArguments(genericTypeParams.get(objectName), binding));
        return new Transformed(new FunctionCall(new Identifier(mangled), argExpressions(args)), resultType);
    }

    /** Enqueues the instantiation of a generic object with concrete type arguments. */
    private String instantiateObject(String base, List<Type> args) {
        List<String> typeParams = genericTypeParams.get(base);
        if (typeParams.size() != args.size()) {
            throw new AlpacaCompilerError("object '" + base + "' expects " + typeParams.size()
                    + " type arguments but got " + args.size());
        }
        Map<String, Type> binding = new HashMap<>();
        for (int i = 0; i < typeParams.size(); i++) {
            binding.put(typeParams.get(i), args.get(i));
        }
        String mangled = mangleObjectName(base, args);
        if (!objectInstances.containsKey(mangled)) {
            worklist.add(new PendingObject(genericObjects.get(base), binding, mangled));
        }
        return mangled;
    }

    /**
     * Walks a type and enqueues the instantiation of every generic object it
     * mentions with concrete type arguments (e.g. {@code Optional<int>} in a
     * field or parameter type).
     */
    private void collectObjectInstantiations(Type type) {
        switch (type) {
            case GenericType(String base, List<Type> args) -> {
                if (genericObjects.containsKey(base) && isConcrete(args)) {
                    instantiateObject(base, args);
                }
                args.forEach(this::collectObjectInstantiations);
            }
            case ArrayType(Type element) -> collectObjectInstantiations(element);
            case TupleType(List<Type> elements) -> elements.forEach(this::collectObjectInstantiations);
            case FunctionType(List<Type> paramTypes, Type returnType) -> {
                paramTypes.forEach(this::collectObjectInstantiations);
                collectObjectInstantiations(returnType);
            }
            case NamedType _ -> { }
        }
    }

    // --------------------------------------------------------------------------
    // Generic function instantiation
    // --------------------------------------------------------------------------

    /**
     * Deduces the type parameter binding of a generic function call from the
     * (already substituted) argument types, enqueues the instantiation on the
     * worklist, and returns the mangled clone name and the concrete result type.
     */
    private GenericCall instantiateFunction(String functionName, List<Transformed> args) {
        FunctionDeclaration declaration = genericFunctions.get(functionName);
        Map<String, Type> binding = deduceBinding(declaration.parameters(), args,
                genericTypeParams.get(functionName), functionName);
        String mangled = mangleFunctionName(functionName, genericTypeParams.get(functionName), binding);
        if (!functionInstances.containsKey(mangled)) {
            worklist.add(new PendingFunction(declaration, binding, mangled));
        }
        return new GenericCall(mangled, substitute(declaration.returnType(), binding));
    }

    /**
     * Unifies the argument types against the formal parameter types of a
     * generic declaration, collecting bindings for its type parameters. All
     * type parameters must be bound by the end, otherwise the call cannot be
     * instantiated.
     */
    private Map<String, Type> deduceBinding(List<Parameter> parameters, List<Transformed> args,
                                            List<String> typeParams, String calleeName) {
        if (args.size() != parameters.size()) {
            throw new AlpacaCompilerError("internal error: arity mismatch at call to '" + calleeName + "'");
        }
        Map<String, Type> binding = new HashMap<>();
        for (int i = 0; i < parameters.size(); i++) {
            Type argType = args.get(i).type();
            if (argType == null) {
                throw new AlpacaCompilerError("internal error: no recorded type for argument "
                        + (i + 1) + " of call to '" + calleeName + "'");
            }
            unify(argType, parameters.get(i).type(), typeParams, binding, calleeName);
        }
        for (String typeParam : typeParams) {
            if (!binding.containsKey(typeParam)) {
                throw new AlpacaCompilerError("cannot deduce type parameter '" + typeParam
                        + "' of '" + calleeName + "' from its arguments");
            }
        }
        return binding;
    }

    /**
     * Structural unification of an actual argument type against a formal
     * parameter type, collecting bindings for the callee's type parameters.
     * M2's lax rules guarantee that assignability already holds, so a mismatch
     * here means the monomorphizer cannot derive the concrete arguments.
     */
    private void unify(Type actual, Type expected, List<String> typeParams,
                       Map<String, Type> binding, String calleeName) {
        if (expected instanceof NamedType(String name) && typeParams.contains(name)) {
            Type previous = binding.putIfAbsent(name, actual);
            if (previous != null && !previous.equals(actual)) {
                throw new AlpacaCompilerError("conflicting type arguments for type parameter '" + name
                        + "' of '" + calleeName + "'");
            }
            return;
        }
        if (actual instanceof NamedType && expected instanceof NamedType && actual.equals(expected)) {
            return;
        }
        if (actual instanceof ArrayType(Type actualElement) && expected instanceof ArrayType(Type expectedElement)) {
            unify(actualElement, expectedElement, typeParams, binding, calleeName);
            return;
        }
        if (actual instanceof GenericType(String actualBase, List<Type> actualArgs)
                && expected instanceof GenericType(String expectedBase, List<Type> expectedArgs)
                && actualBase.equals(expectedBase) && actualArgs.size() == expectedArgs.size()) {
            for (int i = 0; i < actualArgs.size(); i++) {
                unify(actualArgs.get(i), expectedArgs.get(i), typeParams, binding, calleeName);
            }
            return;
        }
        if (actual instanceof TupleType(List<Type> actualElements) && expected instanceof TupleType(List<Type> expectedElements)
                && actualElements.size() == expectedElements.size()) {
            for (int i = 0; i < actualElements.size(); i++) {
                unify(actualElements.get(i), expectedElements.get(i), typeParams, binding, calleeName);
            }
            return;
        }
        if (actual instanceof FunctionType(List<Type> actualParams, Type actualReturn)
                && expected instanceof FunctionType(List<Type> expectedParams, Type expectedReturn)
                && actualParams.size() == expectedParams.size()) {
            for (int i = 0; i < actualParams.size(); i++) {
                unify(actualParams.get(i), expectedParams.get(i), typeParams, binding, calleeName);
            }
            unify(actualReturn, expectedReturn, typeParams, binding, calleeName);
            return;
        }
        throw new AlpacaCompilerError("cannot deduce type parameters of '" + calleeName
                + "' from an argument of type " + typeName(actual)
                + " (formal parameter has type " + typeName(expected) + ")");
    }

    // --------------------------------------------------------------------------
    // Concreteness and mangling
    // --------------------------------------------------------------------------

    /** True when no type argument mentions a type parameter name. */
    private boolean isConcrete(List<Type> args) {
        return args.stream().noneMatch(this::containsTypeParam);
    }

    private boolean containsTypeParam(Type type) {
        return switch (type) {
            case NamedType(String name) -> typeParamNames.contains(name);
            case ArrayType(Type element) -> containsTypeParam(element);
            case TupleType(List<Type> elements) -> elements.stream().anyMatch(this::containsTypeParam);
            case GenericType(_, List<Type> args) -> args.stream().anyMatch(this::containsTypeParam);
            case FunctionType(List<Type> paramTypes, Type returnType) ->
                    paramTypes.stream().anyMatch(this::containsTypeParam) || containsTypeParam(returnType);
        };
    }

    /**
     * Renders a generic type as the mangled name of its concrete clone
     * ({@code Optional<int>} -> {@code Optional$int}).
     */
    private static Type concretize(Type type) {
        return switch (type) {
            case NamedType _ -> type;
            case ArrayType(Type element) -> new ArrayType(concretize(element));
            case TupleType(List<Type> elements) ->
                    new TupleType(elements.stream().map(Monomorphizer::concretize).toList());
            case GenericType(String base, List<Type> args) ->
                    new NamedType(mangleType(new GenericType(base, args.stream().map(Monomorphizer::concretize).toList())));
            case FunctionType(List<Type> paramTypes, Type returnType) ->
                    new FunctionType(paramTypes.stream().map(Monomorphizer::concretize).toList(), concretize(returnType));
        };
    }

    private static String mangleFunctionName(String name, List<String> typeParams, Map<String, Type> binding) {
        StringBuilder sb = new StringBuilder(name);
        for (String typeParam : typeParams) {
            sb.append('$').append(mangleType(binding.get(typeParam)));
        }
        return sb.toString();
    }

    private static String mangleObjectName(String base, List<String> typeParams, Map<String, Type> binding) {
        StringBuilder sb = new StringBuilder(base);
        for (String typeParam : typeParams) {
            sb.append('$').append(mangleType(binding.get(typeParam)));
        }
        return sb.toString();
    }

    private static String mangleObjectName(String base, List<Type> args) {
        StringBuilder sb = new StringBuilder(base);
        for (Type arg : args) {
            sb.append('$').append(mangleType(arg));
        }
        return sb.toString();
    }

    private static List<Type> typeArguments(List<String> typeParams, Map<String, Type> binding) {
        return typeParams.stream().map(binding::get).toList();
    }

    /**
     * Renders a type as part of a mangled name. {@code $} separates components;
     * the separator cannot appear in user identifiers, and spaces (as in
     * {@code unsigned int}) are replaced by underscores to keep the name
     * C-identifier friendly for the emitter.
     */
    private static String mangleType(Type type) {
        return switch (type) {
            case NamedType(String name) -> name.replace(' ', '_');
            case ArrayType(Type element) -> mangleType(element) + "$array";
            case TupleType(List<Type> elements) -> {
                StringBuilder sb = new StringBuilder();
                for (Type element : elements) {
                    sb.append(mangleType(element)).append('$');
                }
                yield sb.append("tuple").toString();
            }
            case GenericType(String base, List<Type> args) -> {
                StringBuilder sb = new StringBuilder(base.replace(' ', '_'));
                for (Type arg : args) {
                    sb.append('$').append(mangleType(arg));
                }
                yield sb.toString();
            }
            case FunctionType _ -> "function";
        };
    }

    // --------------------------------------------------------------------------
    // Helpers
    // --------------------------------------------------------------------------

    private static boolean isGeneric(FunctionDeclaration function) {
        return function.decorators().stream().anyMatch(decorator -> decorator instanceof GenericDecorator);
    }

    private static boolean isGeneric(ObjectDeclaration object) {
        return object.decorators().stream().anyMatch(decorator -> decorator instanceof GenericDecorator);
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

    private static List<Decorator> withoutGeneric(List<Decorator> decorators) {
        return decorators.stream()
                .filter(decorator -> !(decorator instanceof GenericDecorator))
                .toList();
    }

    private static Literal copyLiteral(Literal literal) {
        return switch (literal) {
            case IntLiteral(long value) -> new IntLiteral(value);
            case FloatLiteral(double value) -> new FloatLiteral(value);
            case BoolLiteral(boolean value) -> new BoolLiteral(value);
            case CharLiteral(char value) -> new CharLiteral(value);
            case StringLiteral(String value) -> new StringLiteral(value);
        };
    }

    private List<Parameter> transformParameters(List<Parameter> parameters, Map<String, Type> sigma) {
        List<Parameter> transformed = new ArrayList<>(parameters.size());
        for (Parameter parameter : parameters) {
            transformed.add(new Parameter(transformDeclarationType(parameter.type(), sigma), parameter.name()));
        }
        return transformed;
    }

    /**
     * Applies a type parameter substitution to a type: every {@code NamedType}
     * whose name is a key of {@code substitution} is replaced by its value;
     * composite types (arrays, tuples, generics, function types) are
     * substituted recursively. Names not present in the substitution are left
     * untouched, as is the base name of a {@code GenericType}.
     */
    public static Type substitute(Type type, Map<String, Type> substitution) {
        return switch (type) {
            case NamedType(String name) -> substitution.getOrDefault(name, type);
            case ArrayType(Type element) -> new ArrayType(substitute(element, substitution));
            case TupleType(List<Type> elements) -> {
                List<Type> substituted = new ArrayList<>(elements.size());
                for (Type element : elements) {
                    substituted.add(substitute(element, substitution));
                }
                yield new TupleType(substituted);
            }
            case GenericType(String base, List<Type> args) -> {
                List<Type> substituted = new ArrayList<>(args.size());
                for (Type arg : args) {
                    substituted.add(substitute(arg, substitution));
                }
                yield new GenericType(base, substituted);
            }
            case FunctionType(List<Type> paramTypes, Type returnType) -> {
                List<Type> substituted = new ArrayList<>(paramTypes.size());
                for (Type param : paramTypes) {
                    substituted.add(substitute(param, substitution));
                }
                yield new FunctionType(substituted, substitute(returnType, substitution));
            }
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
            case FunctionType _ -> "function";
        };
    }
}
