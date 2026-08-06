import net.rusrusme.alpacac.*;
import net.rusrusme.alpacac.SyntaxTree.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class TypeCheckerTests {

    private Program parse(String source) {
        return new AlpacaParser("test.alpaca", new AlpacaLexer().tokenize(source)).construct();
    }

    private void analyze(String source) {
        Program program = parse(source);
        SemanticAnalyzer analyzer = new SemanticAnalyzer();
        analyzer.analyze(program);
        new TypeChecker(analyzer.symbolTable(), analyzer.resolvedSymbols()).check(program);
    }

    private AlpacaCompilerError analyzeError(String source) {
        Program program = parse(source);
        try {
            SemanticAnalyzer analyzer = new SemanticAnalyzer();
            analyzer.analyze(program);
            new TypeChecker(analyzer.symbolTable(), analyzer.resolvedSymbols()).check(program);
            fail("expected AlpacaCompilerError");
            return null;
        }
        catch (AlpacaCompilerError e) {
            return e;
        }
    }

    // ============================================================
    //  Valid programs
    // ============================================================

    @Test
    @DisplayName("Accepts arithmetic, comparisons and boolean conditions")
    void testValidArithmeticAndConditions() {
        analyze("""
                void f() {
                    int x = 1 + 2 * 3;
                    float g = 1.5 + 2;
                    bool b = 1 < 2 && true;
                    while (b) {
                        x = x - 1;
                    }
                }
                """);
    }

    @Test
    @DisplayName("Accepts int widening to float")
    void testIntWidensToFloat() {
        analyze("void f() { float g = 3; }");
    }

    @Test
    @DisplayName("Accepts string concatenation and comparison")
    void testStringOperations() {
        analyze("""
                void f() {
                    string s = "a" + "b";
                    bool b = s == "ab";
                }
                """);
    }

    @Test
    @DisplayName("Accepts member access on a call result")
    void testMemberAccessOnCallResult() {
        analyze("""
                object House {
                    public int price;
                }
                House get_house() {
                    House h;
                    return h;
                }
                void f() {
                    int p = get_house().price;
                }
                """);
    }

    @Test
    @DisplayName("Accepts a method call on a call result with arguments")
    void testMethodCallOnCallResult() {
        analyze("""
                object House {
                    public int price;
                    public void set_price(int p) {
                        price = p;
                    }
                }
                House get_house() {
                    House h;
                    return h;
                }
                void f() {
                    get_house().set_price(5);
                }
                """);
    }

    @Test
    @DisplayName("Accepts array index access and assignment")
    void testArrayIndexAccess() {
        analyze("""
                void f() {
                    int[] a;
                    a[0] = 5;
                    int x = a[1];
                }
                """);
    }

    @Test
    @DisplayName("Accepts 'is' expressions as bool")
    void testIsExpressionTypesToBool() {
        analyze("void f() { int x; bool b = x is int; }");
    }

    @Test
    @DisplayName("Accepts object methods with this and fields")
    void testObjectMethods() {
        analyze("""
                object Counter {
                    public int count;
                    public void increment() {
                        count++;
                    }
                    public int value() {
                        return count;
                    }
                }
                void f() {
                    Counter c;
                    c.increment();
                    int v = c.value();
                }
                """);
    }

    // ============================================================
    //  Invalid programs
    // ============================================================

    @Test
    @DisplayName("Rejects assigning float to int")
    void testAssignFloatToInt() {
        AlpacaCompilerError e = analyzeError("void f() { int a; a = 3.5; }");
        assertTrue(e.getMessage().contains("cannot assign float to int"));
    }

    @Test
    @DisplayName("Rejects a return value of the wrong type")
    void testReturnTypeMismatch() {
        AlpacaCompilerError e = analyzeError("int f() { return \"x\"; }");
        assertTrue(e.getMessage().contains("cannot assign string to int"));
    }

    @Test
    @DisplayName("Rejects returning a value from a void function")
    void testReturnValueFromVoid() {
        AlpacaCompilerError e = analyzeError("void f() { return 1; }");
        assertTrue(e.getMessage().contains("cannot return a value from a void function"));
    }

    @Test
    @DisplayName("Rejects a missing return value in a non-void function")
    void testMissingReturnValue() {
        AlpacaCompilerError e = analyzeError("int f() { return; }");
        assertTrue(e.getMessage().contains("missing return value"));
    }

    @Test
    @DisplayName("Rejects non-boolean conditions")
    void testConditionNotBool() {
        AlpacaCompilerError e = analyzeError("void f() { if (1) { } }");
        assertTrue(e.getMessage().contains("if condition must be bool"));
    }

    @Test
    @DisplayName("Rejects calls with the wrong number of arguments")
    void testCallArityMismatch() {
        AlpacaCompilerError e = analyzeError("""
                int max(int a, int b) { return a; }
                void f() { int x = max(1, 2, 3); }
                """);
        assertTrue(e.getMessage().contains("expects 2 arguments but got 3"));
    }

    @Test
    @DisplayName("Rejects calls with a mismatched argument type")
    void testCallArgumentTypeMismatch() {
        AlpacaCompilerError e = analyzeError("""
                int max(int a, int b) { return a; }
                void f() { int x = max("a", 2); }
                """);
        assertTrue(e.getMessage().contains("argument 1 of 'max'"));
    }

    @Test
    @DisplayName("Rejects calling a non-function value")
    void testCallNonFunction() {
        AlpacaCompilerError e = analyzeError("void f() { (1 + 2)(); }");
        assertTrue(e.getMessage().contains("cannot call a non-function value"));
    }

    @Test
    @DisplayName("Rejects indexing a non-array value")
    void testIndexNonArray() {
        AlpacaCompilerError e = analyzeError("void f() { int x; int y = x[0]; }");
        assertTrue(e.getMessage().contains("indexing a non-array value"));
    }

    @Test
    @DisplayName("Rejects a non-int array index")
    void testIndexNotInt() {
        AlpacaCompilerError e = analyzeError("void f() { int[] a; int y = a[\"x\"]; }");
        assertTrue(e.getMessage().contains("array index must be int"));
    }

    @Test
    @DisplayName("Rejects comparing strings with ordering operators")
    void testStringOrderingOperator() {
        AlpacaCompilerError e = analyzeError("void f() { bool b = \"a\" < \"b\"; }");
        assertTrue(e.getMessage().contains("requires a numeric value"));
    }

    @Test
    @DisplayName("Rejects using a method as a value")
    void testMethodAsValue() {
        AlpacaCompilerError e = analyzeError("""
                object A {
                    int get() { return 1; }
                    void f() { int x = this.get; }
                }
                """);
        assertTrue(e.getMessage().contains("cannot use method 'get' as a value"));
    }

    @Test
    @DisplayName("Rejects member access on a primitive value")
    void testMemberOnPrimitive() {
        AlpacaCompilerError e = analyzeError("void f() { int x; x.foo(); }");
        assertTrue(e.getMessage().contains("cannot access member 'foo' on a value of type int"));
    }

    @Test
    @DisplayName("Rejects a local initializer type mismatch")
    void testLocalInitMismatch() {
        AlpacaCompilerError e = analyzeError("void f() { int x = \"s\"; }");
        assertTrue(e.getMessage().contains("initializer of variable 'x'"));
    }

    @Test
    @DisplayName("Rejects postfix on a non-numeric value")
    void testPostfixOnString() {
        AlpacaCompilerError e = analyzeError("void f() { string s; s++; }");
        assertTrue(e.getMessage().contains("requires a numeric value"));
    }

    @Test
    @DisplayName("Rejects arithmetic on strings")
    void testArithmeticOnString() {
        AlpacaCompilerError e = analyzeError("void f() { string s; int x = s * 2; }");
        assertTrue(e.getMessage().contains("requires a numeric value"));
    }

    // ============================================================
    //  Numeric types: long, double, unsigned int/long
    // ============================================================

    @Test
    @DisplayName("Accepts unsigned int and unsigned long initializers")
    void testUnsignedTypes() {
        analyze("""
                void f() {
                    unsigned int num = 10;
                    unsigned long big = 5;
                    long l = 5;
                    double d = 3.5;
                }
                """);
    }

    @Test
    @DisplayName("Accepts numeric widening along the rank chain")
    void testNumericWideningChain() {
        analyze("""
                void f() {
                    int i = 1;
                    unsigned int ui = i;
                    long l = ui;
                    unsigned long ul = l;
                    float f = ul;
                    double d = f;
                }
                """);
    }

    @Test
    @DisplayName("Accepts arithmetic mixing unsigned and int")
    void testUnsignedArithmetic() {
        analyze("""
                void f() {
                    unsigned int a = 2;
                    unsigned int b = a + 1;
                    bool ok = b > a;
                }
                """);
    }

    @Test
    @DisplayName("Rejects a negative literal initializer for unsigned types")
    void testUnsignedNegativeLiteral() {
        AlpacaCompilerError e = analyzeError("void f() { unsigned int num = -1; }");
        assertTrue(e.getMessage().contains("cannot assign a negative value to unsigned int"));
    }

    @Test
    @DisplayName("Rejects a negative float initializer for unsigned types")
    void testUnsignedNegativeFloat() {
        AlpacaCompilerError e = analyzeError("void f() { unsigned int num = -1.5; }");
        assertTrue(e.getMessage().contains("cannot assign a negative value to unsigned int"));
    }

    @Test
    @DisplayName("Rejects assigning a negative value to an unsigned variable")
    void testUnsignedNegativeAssignment() {
        AlpacaCompilerError e = analyzeError("void f() { unsigned int num = 1; num = -2; }");
        assertTrue(e.getMessage().contains("cannot assign a negative value to unsigned int"));
    }

    @Test
    @DisplayName("Rejects a negative argument for an unsigned parameter")
    void testUnsignedNegativeArgument() {
        AlpacaCompilerError e = analyzeError("""
                void f(unsigned int u) { }
                void g() { f(-1); }
                """);
        assertTrue(e.getMessage().contains("cannot assign a negative value to unsigned int"));
    }

    @Test
    @DisplayName("Rejects narrowing long to int")
    void testLongToIntRejected() {
        AlpacaCompilerError e = analyzeError("void f() { long l = 1; int x = l; }");
        assertTrue(e.getMessage().contains("cannot assign long to int"));
    }

    @Test
    @DisplayName("Rejects narrowing double to float")
    void testDoubleToFloatRejected() {
        AlpacaCompilerError e = analyzeError("void f() { double d = 1.5; float f = d; }");
        assertTrue(e.getMessage().contains("cannot assign double to float"));
    }

    @Test
    @DisplayName("Rejects narrowing unsigned int to int")
    void testUnsignedToIntRejected() {
        AlpacaCompilerError e = analyzeError("void f() { unsigned int u = 1; int x = u; }");
        assertTrue(e.getMessage().contains("cannot assign unsigned int to int"));
    }

    // ============================================================
    //  Generic object types
    // ============================================================

    @Test
    @DisplayName("Accepts member access on generic object types")
    void testGenericObjectMemberAccess() {
        analyze("""
                @generic(E)
                public object Array {
                    E[] data;
                    public int length;
                
                    public Array<E> new(int length) {
                        Array<E> arr;
                        arr.length = length;
                        return arr;
                    }
                }
                void f() {
                    Array<E> arr;
                    arr.length = 5;
                }
                """);
    }

    @Test
    @DisplayName("Rejects unknown members on generic object types")
    void testGenericObjectUnknownMember() {
        AlpacaCompilerError e = analyzeError("""
                @generic(E)
                public object Array {
                    E[] data;
                }
                void f() {
                    Array<int> arr;
                    arr.unknown = 1;
                }
                """);
        assertTrue(e.getMessage().contains("unknown member 'unknown' on object 'Array'"));
    }

    // ============================================================
    //  Constructors
    // ============================================================

    @Test
    @DisplayName("Accepts constructing an object with matching arguments")
    void testConstruction() {
        analyze("""
                object Point {
                    public int x;
                    public int y;
                    public construct(int x, int y) {
                        this.x = x;
                        this.y = y;
                    }
                }
                void f() {
                    Point p = Point(1, 2);
                }
                """);
    }

    @Test
    @DisplayName("Accepts a constructor call widening an argument")
    void testConstructionWidening() {
        analyze("""
                object Box {
                    public construct(float size) { }
                }
                void f() {
                    Box b = Box(1);
                }
                """);
    }

    @Test
    @DisplayName("Rejects construction with the wrong number of arguments")
    void testConstructionArityMismatch() {
        AlpacaCompilerError e = analyzeError("""
                object Point {
                    public construct(int x, int y) { }
                }
                void f() {
                    Point p = Point(1);
                }
                """);
        assertTrue(e.getMessage().contains("expects 2 arguments but got 1"));
    }

    @Test
    @DisplayName("Rejects construction with a mismatched argument type")
    void testConstructionArgumentTypeMismatch() {
        AlpacaCompilerError e = analyzeError("""
                object Point {
                    public construct(int x, int y) { }
                }
                void f() {
                    Point p = Point("a", 2);
                }
                """);
        assertTrue(e.getMessage().contains("argument 1 of 'Point'"));
    }

    @Test
    @DisplayName("Rejects returning a value from a constructor")
    void testConstructorReturnValue() {
        AlpacaCompilerError e = analyzeError("object A { public construct() { return 1; } }");
        assertTrue(e.getMessage().contains("cannot return a value from a void function"));
    }

    @Test
    @DisplayName("Rejects assigning the wrong type inside a constructor body")
    void testConstructorBodyTypeMismatch() {
        AlpacaCompilerError e = analyzeError("""
                object A {
                    public int x;
                    public construct() {
                        x = "s";
                    }
                }
                """);
        assertTrue(e.getMessage().contains("cannot assign string to int"));
    }

    @Test
    @DisplayName("Accepts constructing a generic object via expected-type inference")
    void testGenericConstructionExpectedType() {
        analyze("""
                @generic(E)
                public object Array {
                    E[] data;
                    int length;
                
                    public construct(string name) {
                        name = "Hello";
                    }
                }
                void f() {
                    Array<int> arr = Array("Hi");
                }
                """);
    }

    @Test
    @DisplayName("Accepts expected-type inference through a return statement")
    void testGenericConstructionInReturn() {
        analyze("""
                @generic(E)
                public object Array {
                    E[] data;
                    int length;
                
                    public construct(string name) {
                        name = "Hello";
                    }
                }
                Array<int> make() {
                    return Array("Hi");
                }
                """);
    }

    @Test
    @DisplayName("Accepts expected-type inference through a function argument")
    void testGenericConstructionInArgument() {
        analyze("""
                @generic(E)
                public object Array {
                    E[] data;
                    int length;
                
                    public construct(string name) {
                        name = "Hello";
                    }
                }
                void take(Array<int> a) { }
                void f() {
                    take(Array("Hi"));
                }
                """);
    }

    @Test
    @DisplayName("Accepts raw construction without a generic target type")
    void testGenericConstructionRawTarget() {
        analyze("""
                @generic(E)
                public object Array {
                    E[] data;
                    int length;
                
                    public construct(string name) {
                        name = "Hello";
                    }
                }
                void f() {
                    Array arr = Array("Hi");
                }
                """);
    }

    @Test
    @DisplayName("Rejects expected-type inference with a mismatching base name")
    void testGenericConstructionWrongTarget() {
        AlpacaCompilerError e = analyzeError("""
                @generic(E)
                public object Array {
                    E[] data;
                    int length;

                    public construct(string name) {
                        name = "Hello";
                    }
                }
                object Point {
                    public construct() { }
                }
                void f() {
                    Point p = Array("Hi");
                }
                """);
        assertTrue(e.getMessage().contains("cannot assign Array to Point"));
    }

    // ============================================================
    //  Generic stdlib: unconstrained type parameters
    // ============================================================

    @Test
    @DisplayName("Accepts generic method calls with concrete arguments")
    void testGenericMethodCallConcreteArgs() {
        analyze("""
                @generic(E)
                public object Array {
                    E[] data;
                    int length;
                    public construct(int length) {
                        this.length = length;
                    }
                    public void add(E element) { }
                }
                void f() {
                    Array<int> arr = Array(10);
                    arr.add(5);
                }
                """);
    }

    @Test
    @DisplayName("Accepts a generic Optional with some, none and methods")
    void testGenericOptional() {
        analyze("""
                @generic(E)
                public object Optional {
                    private E value;
                    private bool present;
                    public construct(E value, bool present) {
                        this.value = value;
                        this.present = present;
                    }
                    public bool isPresent() {
                        return present;
                    }
                    public E get() {
                        return value;
                    }
                }
                @generic(E)
                public Optional<E> none(E dummy) {
                    return Optional(dummy, false);
                }
                @generic(E)
                public Optional<E> some(E value) {
                    return Optional(value, true);
                }
                void f() {
                    Optional<int> o = some(5);
                    Optional<int> n = none(0);
                    bool b = o.isPresent();
                    int v = o.get();
                }
                """);
    }

    @Test
    @DisplayName("Accepts Optional as the return type of a generic method")
    void testGenericMethodReturningOptional() {
        analyze("""
                @generic(E)
                public object Optional {
                    private E value;
                    private bool present;
                    public construct(E value, bool present) {
                        this.value = value;
                        this.present = present;
                    }
                    public bool isPresent() {
                        return present;
                    }
                    public E get() {
                        return value;
                    }
                }
                @generic(E)
                public Optional<E> some(E value) {
                    return Optional(value, true);
                }
                @generic(E)
                public object Array {
                    E[] data;
                    public construct(int length) { }
                    public Optional<E> get(int index) {
                        return some(data[0]);
                    }
                }
                void f() {
                    Array<int> arr = Array(10);
                    Optional<int> o = arr.get(0);
                }
                """);
    }

    @Test
    @DisplayName("Still rejects mismatching concrete generic arguments")
    void testGenericConcreteMismatch() {
        AlpacaCompilerError e = analyzeError("""
                @generic(E)
                public object Box {
                    public construct(int capacity) { }
                }
                void f() {
                    Box<int> a = Box(1);
                    Box<string> b = a;
                }
                """);
        assertTrue(e.getMessage().contains("cannot assign Box<int> to Box<string>"));
    }

    // ============================================================
    //  Edge cases
    // ============================================================

    @Test
    @DisplayName("Accepts an empty array literal assigned to a typed array")
    void testEmptyArrayLiteralWithExpectedType() {
        analyze("void f() { int[] a = {}; }");
    }

    @Test
    @DisplayName("Rejects an array literal with mismatching element types")
    void testArrayLiteralElementMismatch() {
        AlpacaCompilerError e = analyzeError("void f() { int[] a = {1, 2.5}; }");
        assertTrue(e.getMessage().contains("array element"));
    }

    @Test
    @DisplayName("Rejects assigning an int array to a float array (no array widening)")
    void testArrayTypeNoWidening() {
        AlpacaCompilerError e = analyzeError("""
                void f() {
                    int[] a = {1, 2};
                    float[] b = a;
                }
                """);
        assertTrue(e.getMessage().contains("cannot assign"));
    }

    @Test
    @DisplayName("Rejects assigning an int to a bool")
    void testIntToBoolRejected() {
        AlpacaCompilerError e = analyzeError("void f() { bool b = 1; }");
        assertTrue(e.getMessage().contains("cannot assign int to bool"));
    }

    @Test
    @DisplayName("Rejects arithmetic on chars (char is not numeric in this milestone)")
    void testCharNotNumeric() {
        AlpacaCompilerError e = analyzeError("void f() { char c = 'a'; int i = c + 1; }");
        assertTrue(e.getMessage().contains("requires a numeric value"));
    }

    @Test
    @DisplayName("Accepts a tuple assignment with matching element types")
    void testTupleMatch() {
        analyze("void f() { (int, string) p = (1, \"a\"); }");
    }

    @Test
    @DisplayName("Rejects a tuple assignment with mismatching element types")
    void testTupleMismatch() {
        AlpacaCompilerError e = analyzeError("void f() { (int, string) p = (1, 2); }");
        assertTrue(e.getMessage().contains("cannot assign"));
    }

    @Test
    @DisplayName("Rejects a negative constant for an unsigned constructor parameter")
    void testUnsignedNegativeConstructorArgument() {
        AlpacaCompilerError e = analyzeError("""
                object Box {
                    public construct(unsigned int capacity) { }
                }
                void f() {
                    Box b = Box(-1);
                }
                """);
        assertTrue(e.getMessage().contains("cannot assign a negative value to unsigned int"));
    }

    @Test
    @DisplayName("Accepts a construction call as a standalone statement")
    void testConstructionAsStatement() {
        analyze("""
                object Point {
                    public construct(int x) { }
                }
                void f() {
                    Point(1);
                }
                """);
    }

    @Test
    @DisplayName("Rejects accessing a private member on a call result")
    void testPrivateMemberOnCallResult() {
        AlpacaCompilerError e = analyzeError("""
                object House {
                    private int price;
                }
                House get_house() {
                    House h;
                    return h;
                }
                void f() {
                    int p = get_house().price;
                }
                """);
        assertTrue(e.getMessage().contains("cannot access private member 'price'"));
    }

    @Test
    @DisplayName("Rejects a global initializer of the wrong type")
    void testGlobalInitializerMismatch() {
        AlpacaCompilerError e = analyzeError("int g = \"x\";");
        assertTrue(e.getMessage().contains("initializer of global 'g'"));
    }

    @Test
    @DisplayName("Accepts nested generic construction via expected type")
    void testNestedGenericConstruction() {
        analyze("""
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
                }
                void f() {
                    Optional<Array<int>> o = Optional(Array(10), true);
                }
                """);
    }

    @Test
    @DisplayName("Rejects a bool condition that is a comparison of incompatible types")
    void testIncompatibleComparison() {
        AlpacaCompilerError e = analyzeError("void f() { bool b = \"a\" == 1; }");
        assertTrue(e.getMessage().contains("cannot compare string with int"));
    }

    // ============================================================
    //  expressionTypes() (milestone 3.1: monomorphizer input)
    // ============================================================

    private record Checked(Program program, TypeChecker checker) {}

    private Checked checked(String source) {
        Program program = parse(source);
        SemanticAnalyzer analyzer = new SemanticAnalyzer();
        analyzer.analyze(program);
        TypeChecker checker = new TypeChecker(analyzer.symbolTable(), analyzer.resolvedSymbols());
        checker.check(program);
        return new Checked(program, checker);
    }

    /**
     * Collects every expression the type checker inspects: statement trees are
     * walked recursively, call callees are skipped (the checker resolves them
     * via the symbol table instead of typing them).
     */
    private static List<Expression> expressions(Program program, Predicate<Expression> filter) {
        List<Expression> result = new ArrayList<>();
        for (Declaration declaration : program.declarations()) {
            if (declaration instanceof FunctionDeclaration function) {
                function.body().ifPresent(body -> collectStatement(body, filter, result));
            }
        }
        return result;
    }

    private static void collectStatement(Statement statement, Predicate<Expression> filter, List<Expression> out) {
        switch (statement) {
            case Block block -> block.statements().forEach(s -> collectStatement(s, filter, out));
            case IfStatement ifs -> {
                collectExpression(ifs.condition(), filter, out);
                collectStatement(ifs.thenBranch(), filter, out);
                ifs.elseBranch().ifPresent(s -> collectStatement(s, filter, out));
            }
            case WhileStatement whileStatement -> {
                collectExpression(whileStatement.condition(), filter, out);
                collectStatement(whileStatement.body(), filter, out);
            }
            case DoWhileStatement doWhileStatement -> {
                collectStatement(doWhileStatement.body(), filter, out);
                collectExpression(doWhileStatement.condition(), filter, out);
            }
            case ForStatement forStatement -> {
                forStatement.init().ifPresent(s -> collectSimpleStatement(s, filter, out));
                forStatement.condition().ifPresent(e -> collectExpression(e, filter, out));
                forStatement.update().ifPresent(s -> collectSimpleStatement(s, filter, out));
                collectStatement(forStatement.body(), filter, out);
            }
            case SwitchStatement switchStatement -> {
                collectExpression(switchStatement.selector(), filter, out);
                for (SwitchCase switchCase : switchStatement.cases()) {
                    if (switchCase.label() != null) {
                        collectExpression(switchCase.label(), filter, out);
                    }
                    collectStatement(switchCase.body(), filter, out);
                }
            }
            case SimpleStatement simple -> collectSimpleStatement(simple, filter, out);
        }
    }

    private static void collectSimpleStatement(SimpleStatement statement, Predicate<Expression> filter, List<Expression> out) {
        switch (statement) {
            case LocalVariable local -> local.initializer().ifPresent(init -> collectExpression(init, filter, out));
            case Assignment assignment -> {
                collectExpression(assignment.target(), filter, out);
                collectExpression(assignment.value(), filter, out);
            }
            case ExpressionStatement expressionStatement -> collectExpression(expressionStatement.expression(), filter, out);
            case ReturnStatement returnStatement -> returnStatement.value().ifPresent(value -> collectExpression(value, filter, out));
            default -> { /* Unsafe/Break/Continue carry no expressions */ }
        }
    }

    private static void collectExpression(Expression expression, Predicate<Expression> filter, List<Expression> out) {
        if (filter.test(expression)) {
            out.add(expression);
        }
        switch (expression) {
            case UnaryOp unary -> collectExpression(unary.operand(), filter, out);
            case BinaryOp binary -> {
                collectExpression(binary.left(), filter, out);
                collectExpression(binary.right(), filter, out);
            }
            case FunctionCall call -> call.arguments().forEach(argument -> collectExpression(argument, filter, out));
            case TupleExpression tuple -> tuple.elements().forEach(element -> collectExpression(element, filter, out));
            case ArrayLiteral array -> array.elements().forEach(element -> collectExpression(element, filter, out));
            case MemberAccess memberAccess -> collectExpression(memberAccess.object(), filter, out);
            case IsExpression isExpression -> collectExpression(isExpression.expression(), filter, out);
            case IndexAccess indexAccess -> {
                collectExpression(indexAccess.array(), filter, out);
                collectExpression(indexAccess.index(), filter, out);
            }
            case PostfixOp postfix -> collectExpression(postfix.operand(), filter, out);
            default -> { /* literals, identifiers, this */ }
        }
    }

    @Test
    @DisplayName("Records a type for every checked expression in object-free code")
    void testEveryExpressionRecorded() {
        Checked c = checked("""
                void f() {
                    int x = 1 + 2 * 3;
                    float g = 1.5 + x;
                    bool b = x > 1 && true;
                    string s = "a" + "b";
                    int[] arr = {1, 2, 3};
                    int y = arr[0];
                }
                """);
        List<Expression> collected = expressions(c.program(), e -> true);
        assertFalse(collected.isEmpty());
        for (Expression expression : collected) {
            assertTrue(c.checker().expressionTypes().containsKey(expression),
                    "no type recorded for expression " + expression);
        }
    }

    @Test
    @DisplayName("Records literal, operator result and concatenation types")
    void testExpressionTypesBasic() {
        Checked c = checked("""
                void f() {
                    int x = 1 + 2;
                    string s = "a" + "b";
                    bool b = x < 5;
                }
                """);
        Map<Expression, Type> types = c.checker().expressionTypes();

        BinaryOp sum = (BinaryOp) expressions(c.program(), e -> e instanceof BinaryOp
                && ((BinaryOp) e).operator().equals("+")
                && ((BinaryOp) e).left() instanceof IntLiteral).getFirst();
        assertEquals(new NamedType("int"), types.get(sum));

        BinaryOp concat = (BinaryOp) expressions(c.program(), e -> e instanceof BinaryOp
                && ((BinaryOp) e).operator().equals("+")
                && ((BinaryOp) e).left() instanceof StringLiteral).getFirst();
        assertEquals(new NamedType("string"), types.get(concat));

        BinaryOp comparison = (BinaryOp) expressions(c.program(), e -> e instanceof BinaryOp
                && ((BinaryOp) e).operator().equals("<")).getFirst();
        assertEquals(new NamedType("bool"), types.get(comparison));
    }

    @Test
    @DisplayName("Records the inferred generic type of a construction call")
    void testConstructionCallTypeRecorded() {
        Checked c = checked("""
                @generic(E)
                public object Array {
                    E[] data;
                    public construct(int length) { }
                }
                void f() {
                    Array<int> arr = Array(10);
                }
                """);
        FunctionCall call = (FunctionCall) expressions(c.program(), e -> e instanceof FunctionCall).getFirst();
        Type type = c.checker().expressionTypes().get(call);
        assertTrue(type instanceof GenericType(String base, List<Type> args)
                        && base.equals("Array")
                        && args.size() == 1
                        && args.getFirst().equals(new NamedType("int")),
                "expected Array<int>, got " + type);
    }

    @Test
    @DisplayName("Records the receiver type of a method call on a generic object")
    void testMethodCallReceiverTypeRecorded() {
        Checked c = checked("""
                @generic(E)
                public object Array {
                    E[] data;
                    public construct(int length) { }
                    public void add(E element) { }
                }
                void f() {
                    Array<int> arr = Array(10);
                    arr.add(5);
                }
                """);
        FunctionCall call = (FunctionCall) expressions(c.program(), e -> e instanceof FunctionCall
                && ((FunctionCall) e).callee() instanceof MemberAccess
                && ((MemberAccess) ((FunctionCall) e).callee()).member().equals("add")).getFirst();
        Expression receiver = ((MemberAccess) call.callee()).object();
        assertEquals(new GenericType("Array", List.of(new NamedType("int"))),
                c.checker().expressionTypes().get(receiver));
        // The call itself returns void, like the add method.
        assertEquals(new NamedType("void"), c.checker().expressionTypes().get(call));
    }

    @Test
    @DisplayName("Records the type of a member access on a call result")
    void testMemberAccessOnCallResultTypeRecorded() {
        Checked c = checked("""
                object House {
                    public int price;
                }
                House get_house() {
                    House h;
                    return h;
                }
                void f() {
                    int p = get_house().price;
                }
                """);
        MemberAccess access = (MemberAccess) expressions(c.program(),
                e -> e instanceof MemberAccess).getFirst();
        assertEquals(new NamedType("int"), c.checker().expressionTypes().get(access));

        FunctionCall call = (FunctionCall) expressions(c.program(),
                e -> e instanceof FunctionCall).getFirst();
        assertEquals(new NamedType("House"), c.checker().expressionTypes().get(call));
    }

    @Test
    @DisplayName("Records the lax generic return type of a generic function call")
    void testGenericFunctionCallTypeRecorded() {
        Checked c = checked("""
                @generic(E)
                public Optional<E> some(E value) {
                    return value;
                }
                void f() {
                    some(5);
                }
                """);
        FunctionCall call = (FunctionCall) expressions(c.program(), e -> e instanceof FunctionCall
                && ((FunctionCall) e).callee() instanceof Identifier
                && ((Identifier) ((FunctionCall) e).callee()).name().equals("some")).getFirst();
        Type type = c.checker().expressionTypes().get(call);
        // M2 is deliberately lax: the return type still mentions the unbound E.
        assertTrue(type instanceof GenericType(String base, List<Type> args)
                        && base.equals("Optional")
                        && args.size() == 1
                        && args.getFirst().equals(new NamedType("E")),
                "expected Optional<E>, got " + type);
    }
}
