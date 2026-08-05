import net.rusrusme.alpacac.AlpacaCompilerError;
import net.rusrusme.alpacac.AlpacaLexer;
import net.rusrusme.alpacac.AlpacaParser;
import net.rusrusme.alpacac.SemanticAnalyzer;
import net.rusrusme.alpacac.SyntaxTree.Program;
import net.rusrusme.alpacac.TypeChecker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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
}
