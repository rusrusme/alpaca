import net.rusrusme.alpacac.*;
import net.rusrusme.alpacac.SyntaxTree.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class MonomorphizerTests {

    private static final Map<String, Type> E_INT = Map.of("E", new NamedType("int"));
    private static final Path LIB_DIR = Path.of("src/main/java/net/rusrusme/alpaca/runtime/lib");

    // ============================================================
    //  Type substitution (milestone 3.2)
    // ============================================================

    @Test
    @DisplayName("Leaves a named type without a substitution entry untouched")
    void testNamedTypeUnchanged() {
        Type type = new NamedType("string");
        assertEquals(type, Monomorphizer.substitute(type, E_INT));
    }

    @Test
    @DisplayName("Replaces a named type that is a substitution key")
    void testNamedTypeReplaced() {
        assertEquals(new NamedType("int"), Monomorphizer.substitute(new NamedType("E"), E_INT));
    }

    @Test
    @DisplayName("Substitutes the element type of an array type")
    void testArrayType() {
        assertEquals(new ArrayType(new NamedType("int")),
                Monomorphizer.substitute(new ArrayType(new NamedType("E")), E_INT));
    }

    @Test
    @DisplayName("Substitutes every element of a tuple type")
    void testTupleType() {
        Type tuple = new TupleType(List.of(new NamedType("E"), new NamedType("string"), new NamedType("E")));
        Type expected = new TupleType(List.of(new NamedType("int"), new NamedType("string"), new NamedType("int")));
        assertEquals(expected, Monomorphizer.substitute(tuple, E_INT));
    }

    @Test
    @DisplayName("Substitutes type arguments of a generic type and keeps the base")
    void testGenericType() {
        Type generic = new GenericType("Array", List.of(new NamedType("E")));
        assertEquals(new GenericType("Array", List.of(new NamedType("int"))),
                Monomorphizer.substitute(generic, E_INT));
    }

    @Test
    @DisplayName("Substitutes nested generic types recursively")
    void testNestedGenericType() {
        Type nested = new GenericType("Array", List.of(
                new GenericType("Optional", List.of(new NamedType("E")))));
        Type expected = new GenericType("Array", List.of(
                new GenericType("Optional", List.of(new NamedType("int")))));
        assertEquals(expected, Monomorphizer.substitute(nested, E_INT));
    }

    @Test
    @DisplayName("Substitutes parameter and return types of a function type")
    void testFunctionType() {
        Type function = new FunctionType(List.of(new NamedType("E"), new NamedType("bool")), new NamedType("E"));
        Type expected = new FunctionType(List.of(new NamedType("int"), new NamedType("bool")), new NamedType("int"));
        assertEquals(expected, Monomorphizer.substitute(function, E_INT));
    }

    @Test
    @DisplayName("An empty substitution returns an equal type")
    void testEmptySubstitution() {
        Type type = new GenericType("Array", List.of(new NamedType("E")));
        assertEquals(type, Monomorphizer.substitute(type, Map.of()));
    }

    // ============================================================
    //  Generic function instantiation (milestone 3.3)
    // ============================================================

    private Program monomorphize(String source) {
        Program program = new AlpacaParser("test.alpaca", new AlpacaLexer().tokenize(source)).construct();
        SemanticAnalyzer analyzer = new SemanticAnalyzer();
        analyzer.analyze(program);
        TypeChecker checker = new TypeChecker(analyzer.symbolTable(), analyzer.resolvedSymbols());
        checker.check(program);
        return new Monomorphizer(analyzer.symbolTable(), analyzer.resolvedSymbols(), checker.expressionTypes())
                .process(program);
    }

    private AlpacaCompilerError monomorphizeError(String source) {
        try {
            monomorphize(source);
            fail("expected AlpacaCompilerError");
            return null;
        }
        catch (AlpacaCompilerError e) {
            return e;
        }
    }

    private static FunctionDeclaration function(Program program, String name) {
        for (Declaration declaration : program.declarations()) {
            if (declaration instanceof FunctionDeclaration function && function.name().equals(name)) {
                return function;
            }
        }
        return null;
    }

    private static List<FunctionDeclaration> functionsNamed(Program program, String name) {
        List<FunctionDeclaration> result = new ArrayList<>();
        for (Declaration declaration : program.declarations()) {
            if (declaration instanceof FunctionDeclaration function && function.name().equals(name)) {
                result.add(function);
            }
        }
        return result;
    }

    private static List<FunctionCall> callsIn(FunctionDeclaration function) {
        List<FunctionCall> calls = new ArrayList<>();
        function.body().ifPresent(body -> collectCalls(body, calls));
        return calls;
    }

    private static void collectCalls(Statement statement, List<FunctionCall> out) {
        switch (statement) {
            case Block block -> block.statements().forEach(s -> collectCalls(s, out));
            case IfStatement ifs -> {
                collectExprCalls(ifs.condition(), out);
                collectCalls(ifs.thenBranch(), out);
                ifs.elseBranch().ifPresent(s -> collectCalls(s, out));
            }
            case WhileStatement whileStatement -> {
                collectExprCalls(whileStatement.condition(), out);
                collectCalls(whileStatement.body(), out);
            }
            case DoWhileStatement doWhileStatement -> {
                collectCalls(doWhileStatement.body(), out);
                collectExprCalls(doWhileStatement.condition(), out);
            }
            case ForStatement forStatement -> {
                forStatement.init().ifPresent(s -> collectSimpleCalls(s, out));
                forStatement.condition().ifPresent(e -> collectExprCalls(e, out));
                forStatement.update().ifPresent(s -> collectSimpleCalls(s, out));
                collectCalls(forStatement.body(), out);
            }
            case SwitchStatement switchStatement -> {
                collectExprCalls(switchStatement.selector(), out);
                for (SwitchCase switchCase : switchStatement.cases()) {
                    if (switchCase.label() != null) {
                        collectExprCalls(switchCase.label(), out);
                    }
                    collectCalls(switchCase.body(), out);
                }
            }
            case SimpleStatement simple -> collectSimpleCalls(simple, out);
        }
    }

    private static void collectSimpleCalls(SimpleStatement statement, List<FunctionCall> out) {
        switch (statement) {
            case LocalVariable local -> local.initializer().ifPresent(init -> collectExprCalls(init, out));
            case Assignment assignment -> {
                collectExprCalls(assignment.target(), out);
                collectExprCalls(assignment.value(), out);
            }
            case ExpressionStatement expressionStatement -> collectExprCalls(expressionStatement.expression(), out);
            case ReturnStatement returnStatement -> returnStatement.value().ifPresent(value -> collectExprCalls(value, out));
            default -> { /* Unsafe/Break/Continue carry no calls */ }
        }
    }

    private static void collectExprCalls(Expression expression, List<FunctionCall> out) {
        switch (expression) {
            case FunctionCall call -> {
                out.add(call);
                collectExprCalls(call.callee(), out);
                call.arguments().forEach(argument -> collectExprCalls(argument, out));
            }
            case UnaryOp unary -> collectExprCalls(unary.operand(), out);
            case BinaryOp binary -> {
                collectExprCalls(binary.left(), out);
                collectExprCalls(binary.right(), out);
            }
            case TupleExpression tuple -> tuple.elements().forEach(element -> collectExprCalls(element, out));
            case ArrayLiteral array -> array.elements().forEach(element -> collectExprCalls(element, out));
            case MemberAccess memberAccess -> collectExprCalls(memberAccess.object(), out);
            case IsExpression isExpression -> collectExprCalls(isExpression.expression(), out);
            case IndexAccess indexAccess -> {
                collectExprCalls(indexAccess.array(), out);
                collectExprCalls(indexAccess.index(), out);
            }
            case PostfixOp postfix -> collectExprCalls(postfix.operand(), out);
            default -> { /* literals, identifiers, this */ }
        }
    }

    @Test
    @DisplayName("Instantiates a generic function per concrete type argument")
    void testGenericFunctionInstantiated() {
        Program out = monomorphize("""
                @generic(E)
                public E id(E value) { return value; }
                void main() {
                    int x = id(5);
                    string s = id("hi");
                }
                """);
        FunctionDeclaration idInt = function(out, "id$int");
        assertNotNull(idInt);
        assertEquals(new NamedType("int"), idInt.returnType());
        assertEquals(1, idInt.parameters().size());
        assertEquals(new NamedType("int"), idInt.parameters().getFirst().type());

        FunctionDeclaration idString = function(out, "id$string");
        assertNotNull(idString);
        assertEquals(new NamedType("string"), idString.returnType());

        assertNull(function(out, "id")); // the generic template itself is gone

        FunctionDeclaration main = function(out, "main");
        List<FunctionCall> calls = callsIn(main);
        assertEquals(2, calls.size());
        assertEquals("id$int", ((Identifier) calls.get(0).callee()).name());
        assertEquals("id$string", ((Identifier) calls.get(1).callee()).name());
    }

    @Test
    @DisplayName("Instantiates generic functions called from other generic functions")
    void testNestedGenericInstantiation() {
        Program out = monomorphize("""
                @generic(E)
                public E id(E value) { return value; }
                @generic(E)
                public E twice(E value) { return id(value); }
                void main() {
                    int x = twice(5);
                }
                """);
        assertNotNull(function(out, "id$int"));
        FunctionDeclaration twiceInt = function(out, "twice$int");
        assertNotNull(twiceInt);
        assertEquals(new NamedType("int"), twiceInt.returnType());
        List<FunctionCall> calls = callsIn(twiceInt);
        assertEquals(1, calls.size());
        assertEquals("id$int", ((Identifier) calls.getFirst().callee()).name());
    }

    @Test
    @DisplayName("Deduces type parameters through a generic call result type")
    void testNestedDeductionThroughGenericResult() {
        Program out = monomorphize("""
                @generic(E)
                public Optional<E> some(E value) { return value; }
                @generic(T)
                public T take(T value) { return value; }
                void main() {
                    Optional<int> o = take(some(5));
                }
                """);
        assertNotNull(function(out, "some$int"));
        FunctionDeclaration takeOptionalInt = function(out, "take$Optional$int");
        assertNotNull(takeOptionalInt);
        assertEquals(new NamedType("Optional$int"), takeOptionalInt.returnType());
        FunctionDeclaration main = function(out, "main");
        assertEquals("take$Optional$int",
                ((Identifier) callsIn(main).getFirst().callee()).name());
    }

    @Test
    @DisplayName("Errors when a type parameter cannot be deduced from the arguments")
    void testUnboundTypeParameter() {
        AlpacaCompilerError e = monomorphizeError("""
                @generic(T)
                public T make() {
                    T x;
                    return x;
                }
                void main() {
                    int x = make();
                }
                """);
        assertTrue(e.getMessage().contains("cannot deduce type parameter 'T'"));
    }

    @Test
    @DisplayName("Drops generic functions that are never instantiated")
    void testUninstantiatedGenericDropped() {
        Program out = monomorphize("""
                @generic(E)
                public E unused(E value) { return value; }
                void main() { int x = 1; }
                """);
        assertNull(function(out, "unused"));
        assertEquals(1, out.declarations().size()); // only main remains
    }

    @Test
    @DisplayName("Instantiates a generic function only once per type argument")
    void testDuplicateInstantiationDeduplicated() {
        Program out = monomorphize("""
                @generic(E)
                public E id(E value) { return value; }
                void main() {
                    int a = id(5);
                    int b = id(6);
                }
                """);
        assertEquals(1, functionsNamed(out, "id$int").size());
    }

    @Test
    @DisplayName("Mangles multiple type parameters in declaration order")
    void testMultipleTypeParameters() {
        Program out = monomorphize("""
                @generic(T, U)
                public void pair(T first, U second) { }
                void main() { pair(1, "x"); }
                """);
        FunctionDeclaration pair = function(out, "pair$int$string");
        assertNotNull(pair);
        assertEquals(new NamedType("int"), pair.parameters().get(0).type());
        assertEquals(new NamedType("string"), pair.parameters().get(1).type());
    }

    @Test
    @DisplayName("Transforms generic calls inside non-generic object methods")
    void testGenericCallInObjectMethod() {
        Program out = monomorphize("""
                @generic(E)
                public E id(E value) { return value; }
                object House {
                    public int value;
                    public int get() {
                        return id(value);
                    }
                }
                void main() {
                    House h;
                    int x = h.get();
                }
                """);
        assertNotNull(function(out, "id$int"));
        ObjectDeclaration house = null;
        for (Declaration declaration : out.declarations()) {
            if (declaration instanceof ObjectDeclaration object && object.name().equals("House")) {
                house = object;
            }
        }
        assertNotNull(house);
        FunctionDeclaration get = house.methods().getFirst();
        assertEquals("id$int", ((Identifier) callsIn(get).getFirst().callee()).name());
    }

    // ============================================================
    //  Generic object instantiation (milestone 3.4)
    // ============================================================

    private static ObjectDeclaration object(Program program, String name) {
        for (Declaration declaration : program.declarations()) {
            if (declaration instanceof ObjectDeclaration object && object.name().equals(name)) {
                return object;
            }
        }
        return null;
    }

    private static Field field(ObjectDeclaration object, String name) {
        for (Field field : object.fields()) {
            if (field.name().equals(name)) {
                return field;
            }
        }
        return null;
    }

    private static FunctionDeclaration method(ObjectDeclaration object, String name) {
        for (FunctionDeclaration method : object.methods()) {
            if (method.name().equals(name)) {
                return method;
            }
        }
        return null;
    }

    @Test
    @DisplayName("Instantiates a generic object with substituted fields, methods and constructor")
    void testGenericObjectInstantiated() {
        Program out = monomorphize("""
                @generic(E)
                public object Array {
                    E[] data;
                    public construct(int length) { }
                    public void add(E element) { }
                }
                void main() {
                    Array<int> arr = Array(10);
                    arr.add(5);
                }
                """);
        ObjectDeclaration arrayInt = object(out, "Array$int");
        assertNotNull(arrayInt);
        assertEquals(new ArrayType(new NamedType("int")), field(arrayInt, "data").type());
        assertEquals(new NamedType("int"), method(arrayInt, "add").parameters().getFirst().type());
        assertEquals(new NamedType("int"), arrayInt.constructor().get().parameters().getFirst().type());
        assertNull(object(out, "Array")); // the generic template itself is gone

        FunctionDeclaration main = function(out, "main");
        List<FunctionCall> calls = callsIn(main);
        assertEquals("Array$int", ((Identifier) calls.get(0).callee()).name());
        // The receiver's declared type is concretized, so the method call is
        // dispatched to the clone by re-analysis.
        LocalVariable arr = (LocalVariable) ((Block) main.body().get()).statements().getFirst();
        assertEquals(new NamedType("Array$int"), arr.type());
    }

    @Test
    @DisplayName("Pulls in a generic object referenced by a clone's signature")
    void testNestedObjectInstantiation() {
        Program out = monomorphize("""
                @generic(E)
                public object Optional {
                    private E value;
                    private bool present;
                    public construct(E value, bool present) {
                        this.value = value;
                        this.present = present;
                    }
                }
                @generic(E)
                public object Array {
                    E[] data;
                    public construct(int length) { }
                    public Optional<E> remove(int index) { return Optional(data[0], true); }
                }
                void main() {
                    Array<int> arr = Array(10);
                    Optional<int> o = arr.remove(0);
                }
                """);
        assertNotNull(object(out, "Optional$int"));
        ObjectDeclaration arrayInt = object(out, "Array$int");
        assertNotNull(arrayInt);
        assertEquals(new NamedType("Optional$int"), method(arrayInt, "remove").returnType());
        // The construction inside the clone's body is renamed as well.
        FunctionDeclaration remove = method(arrayInt, "remove");
        assertEquals("Optional$int", ((Identifier) callsIn(remove).getFirst().callee()).name());
    }

    @Test
    @DisplayName("Deduces object type arguments from the constructor arguments")
    void testObjectConstructionArgumentDeduction() {
        Program out = monomorphize("""
                @generic(E)
                public object Optional {
                    private E value;
                    private bool present;
                    public construct(E value, bool present) {
                        this.value = value;
                        this.present = present;
                    }
                    public bool is_present() {
                        return present;
                    }
                }
                void main() {
                    bool b = Optional(5, true).is_present();
                }
                """);
        assertNotNull(object(out, "Optional$int"));
        FunctionDeclaration main = function(out, "main");
        FunctionCall construction = callsIn(main).stream()
                .filter(call -> call.callee() instanceof Identifier)
                .findFirst().orElseThrow();
        assertEquals("Optional$int", ((Identifier) construction.callee()).name());
    }

    @Test
    @DisplayName("Instantiates a generic object per concrete type argument")
    void testTwoObjectInstantiations() {
        Program out = monomorphize("""
                @generic(E)
                public object Box {
                    E value;
                    public construct(E value) {
                        this.value = value;
                    }
                }
                void main() {
                    Box<int> b1 = Box(5);
                    Box<string> b2 = Box("hi");
                }
                """);
        ObjectDeclaration boxInt = object(out, "Box$int");
        ObjectDeclaration boxString = object(out, "Box$string");
        assertNotNull(boxInt);
        assertNotNull(boxString);
        assertEquals(new NamedType("int"), field(boxInt, "value").type());
        assertEquals(new NamedType("string"), field(boxString, "value").type());
    }

    @Test
    @DisplayName("Drops generic objects that are never instantiated")
    void testUninstantiatedGenericObjectDropped() {
        Program out = monomorphize("""
                @generic(E)
                public object Box {
                    E value;
                    public construct(E value) { }
                }
                void main() { int x = 1; }
                """);
        assertNull(object(out, "Box"));
        assertEquals(1, out.declarations().size()); // only main remains
    }

    @Test
    @DisplayName("Instantiates the receiver's object for a method call on a call result")
    void testMethodCallOnGenericCallResult() {
        Program out = monomorphize("""
                @generic(E)
                public object Array {
                    public construct(int length) { }
                    public void add(E element) { }
                }
                Array<int> make_array() {
                    Array<int> a = Array(10);
                    return a;
                }
                void main() {
                    make_array().add(5);
                }
                """);
        assertNotNull(object(out, "Array$int"));
        assertEquals(new NamedType("Array$int"), function(out, "make_array").returnType());
    }

    @Test
    @DisplayName("Errors when object type arguments have no concrete context")
    void testConstructionWithoutConcreteContext() {
        AlpacaCompilerError e = monomorphizeError("""
                @generic(E)
                public object Array {
                    public construct(int length) { }
                }
                @generic(T)
                public T take(T value) { return value; }
                void main() {
                    take(Array(10));
                }
                """);
        assertTrue(e.getMessage().contains("cannot deduce type parameter 'E'"));
    }

    @Test
    @DisplayName("Instantiates generic functions called from generic object methods")
    void testGenericFunctionCalledFromObjectMethod() {
        Program out = monomorphize("""
                @generic(E)
                public E id(E value) { return value; }
                @generic(E)
                public object Box {
                    E value;
                    public construct(E value) {
                        this.value = value;
                    }
                    public E get() {
                        return id(value);
                    }
                }
                void main() {
                    Box<int> b = Box(5);
                    int x = b.get();
                }
                """);
        assertNotNull(function(out, "id$int"));
        ObjectDeclaration boxInt = object(out, "Box$int");
        assertNotNull(boxInt);
        FunctionDeclaration get = method(boxInt, "get");
        assertEquals(new NamedType("int"), get.returnType());
        assertEquals("id$int", ((Identifier) callsIn(get).getFirst().callee()).name());
    }

    @Test
    @DisplayName("Re-analyzes two instantiations of the same generic function")
    void testTwoInstantiationsReanalyze() {
        // Regression: two clones of the same generic declaration share the
        // original body's leaf nodes; the re-analysis keys its maps by node
        // identity, so the resolve of the second clone used to overwrite the
        // first one's (println$int's 'value' typed as string -> mismatch).
        Program concrete = monomorphize("""
                @generic(V)
                void print(V value) {
                    switch(value) {
                        case value is int -> unsafe { printf("%d", value); }
                        case value is string -> unsafe { printf("%s", value); }
                    }
                }
                @generic(V)
                void println(V value) {
                    print(value);
                }
                @main
                void run() {
                    println(10);
                    println("Hi");
                }
                """);
        assertNotNull(function(concrete, "print$int"));
        assertNotNull(function(concrete, "print$string"));
        assertNotNull(function(concrete, "println$int"));
        assertNotNull(function(concrete, "println$string"));

        // F5 verification: the concrete program must pass fresh M1+M2 passes.
        SemanticAnalyzer reanalyzer = new SemanticAnalyzer();
        reanalyzer.analyze(concrete);
        new TypeChecker(reanalyzer.symbolTable(), reanalyzer.resolvedSymbols()).check(concrete);
    }

    // ============================================================
    //  Worklist fixpoint (milestone 3.5)
    // ============================================================

    /** Runs the full pipeline (M1 + M2 + M3) on a program that imports the bundled stdlib. */
    private Program monomorphizeWithStdlib(String source) {
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
        TypeChecker checker = new TypeChecker(analyzer.symbolTable(), analyzer.resolvedSymbols());
        checker.check(program);
        return new Monomorphizer(analyzer.symbolTable(), analyzer.resolvedSymbols(),
                checker.expressionTypes()).process(program);
    }

    /**
     * Runs the full pipeline including monomorphization on a program that uses
     * the bundled stdlib, then re-analyzes the concrete output with fresh M1/M2
     * passes (which run strict: no type parameters remain).
     */
    @Test
    @DisplayName("Monomorphizes the bundled stdlib and re-analyzes the concrete program")
    void testStdlibMonomorphizedAndReanalyzed() {
        Program concrete = monomorphizeWithStdlib("""
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

        // The fixpoint: Array<int> pulls in Optional$int, some$int and none$int.
        assertNotNull(object(concrete, "Array$int"));
        assertNotNull(object(concrete, "Optional$int"));
        assertNotNull(function(concrete, "some$int"));
        assertNotNull(function(concrete, "none$int"));
        assertNull(object(concrete, "Array"));
        assertNull(object(concrete, "Optional"));

        // F5 verification: the concrete program passes fresh M1+M2 passes.
        SemanticAnalyzer reanalyzer = new SemanticAnalyzer();
        reanalyzer.analyze(concrete);
        new TypeChecker(reanalyzer.symbolTable(), reanalyzer.resolvedSymbols()).check(concrete);
    }

    @Test
    @DisplayName("The monomorphized output contains no generics at all")
    void testOutputIsGenericFree() {
        Program concrete = monomorphizeWithStdlib("""
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
        for (Declaration declaration : concrete.declarations()) {
            switch (declaration) {
                case FunctionDeclaration function -> {
                    assertTrue(function.decorators().stream().noneMatch(d -> d instanceof GenericDecorator));
                    assertTypeFree(function.returnType());
                    function.parameters().forEach(p -> assertTypeFree(p.type()));
                    function.body().ifPresent(MonomorphizerTests::assertStatementsFree);
                }
                case ObjectDeclaration object -> {
                    assertTrue(object.decorators().stream().noneMatch(d -> d instanceof GenericDecorator));
                    for (Field field : object.fields()) {
                        assertTypeFree(field.type());
                        field.initializer().ifPresent(MonomorphizerTests::assertExpressionFree);
                    }
                    for (FunctionDeclaration method : object.methods()) {
                        assertTypeFree(method.returnType());
                        method.parameters().forEach(p -> assertTypeFree(p.type()));
                        method.body().ifPresent(MonomorphizerTests::assertStatementsFree);
                    }
                    object.constructor().ifPresent(constructor -> {
                        constructor.parameters().forEach(p -> assertTypeFree(p.type()));
                        assertStatementsFree(constructor.body());
                    });
                }
                case GlobalVariable global -> {
                    assertTypeFree(global.type());
                    global.initializer().ifPresent(MonomorphizerTests::assertExpressionFree);
                }
                case StatementDeclaration _ -> { }
            }
        }
    }

    private static void assertTypeFree(Type type) {
        assertTrue(!(type instanceof GenericType), "generic type left in output: " + type);
        switch (type) {
            case ArrayType(Type element) -> assertTypeFree(element);
            case TupleType(List<Type> elements) -> elements.forEach(MonomorphizerTests::assertTypeFree);
            case GenericType(_, List<Type> args) -> args.forEach(MonomorphizerTests::assertTypeFree);
            case FunctionType(List<Type> paramTypes, Type returnType) -> {
                paramTypes.forEach(MonomorphizerTests::assertTypeFree);
                assertTypeFree(returnType);
            }
            case NamedType _ -> { }
        }
    }

    private static void assertStatementsFree(Statement statement) {
        switch (statement) {
            case Block block -> block.statements().forEach(MonomorphizerTests::assertStatementsFree);
            case IfStatement ifs -> {
                assertExpressionFree(ifs.condition());
                assertStatementsFree(ifs.thenBranch());
                ifs.elseBranch().ifPresent(MonomorphizerTests::assertStatementsFree);
            }
            case WhileStatement whileStatement -> {
                assertExpressionFree(whileStatement.condition());
                assertStatementsFree(whileStatement.body());
            }
            case DoWhileStatement doWhileStatement -> {
                assertStatementsFree(doWhileStatement.body());
                assertExpressionFree(doWhileStatement.condition());
            }
            case ForStatement forStatement -> {
                forStatement.init().ifPresent(MonomorphizerTests::assertSimpleFree);
                forStatement.condition().ifPresent(MonomorphizerTests::assertExpressionFree);
                forStatement.update().ifPresent(MonomorphizerTests::assertSimpleFree);
                assertStatementsFree(forStatement.body());
            }
            case SwitchStatement switchStatement -> {
                assertExpressionFree(switchStatement.selector());
                switchStatement.cases().forEach(switchCase -> {
                    if (switchCase.label() != null) {
                        assertExpressionFree(switchCase.label());
                    }
                    assertStatementsFree(switchCase.body());
                });
            }
            case SimpleStatement simple -> assertSimpleFree(simple);
        }
    }

    private static void assertSimpleFree(SimpleStatement statement) {
        switch (statement) {
            case LocalVariable local -> {
                assertTypeFree(local.type());
                local.initializer().ifPresent(MonomorphizerTests::assertExpressionFree);
            }
            case Assignment assignment -> {
                assertExpressionFree(assignment.target());
                assertExpressionFree(assignment.value());
            }
            case ExpressionStatement expressionStatement -> assertExpressionFree(expressionStatement.expression());
            case ReturnStatement returnStatement -> returnStatement.value().ifPresent(MonomorphizerTests::assertExpressionFree);
            default -> { /* Unsafe/Break/Continue carry no types */ }
        }
    }

    private static void assertExpressionFree(Expression expression) {
        switch (expression) {
            case UnaryOp unary -> assertExpressionFree(unary.operand());
            case BinaryOp binary -> {
                assertExpressionFree(binary.left());
                assertExpressionFree(binary.right());
            }
            case FunctionCall call -> {
                assertExpressionFree(call.callee());
                call.arguments().forEach(MonomorphizerTests::assertExpressionFree);
            }
            case TupleExpression tuple -> tuple.elements().forEach(MonomorphizerTests::assertExpressionFree);
            case ArrayLiteral array -> array.elements().forEach(MonomorphizerTests::assertExpressionFree);
            case MemberAccess memberAccess -> assertExpressionFree(memberAccess.object());
            case IsExpression isExpression -> {
                assertTypeFree(isExpression.targetType());
                assertExpressionFree(isExpression.expression());
            }
            case IndexAccess indexAccess -> {
                assertExpressionFree(indexAccess.array());
                assertExpressionFree(indexAccess.index());
            }
            case PostfixOp postfix -> assertExpressionFree(postfix.operand());
            default -> { /* literals, identifiers, this */ }
        }
    }
}
