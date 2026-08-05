import net.rusrusme.alpacac.AlpacaCompilerError;
import net.rusrusme.alpacac.AlpacaLexer;
import net.rusrusme.alpacac.AlpacaParser;
import net.rusrusme.alpacac.SemanticAnalyzer;
import net.rusrusme.alpacac.SyntaxTree.Declaration;
import net.rusrusme.alpacac.SyntaxTree.Program;
import net.rusrusme.alpacac.TypeChecker;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SemanticTests {

    private static void analyze(String src) {
        var program = new AlpacaParser("test.alpaca", new AlpacaLexer().tokenize(src)).construct();
        new SemanticAnalyzer().analyze(program);
    }

    private static AlpacaCompilerError analyzeError(String src) {
        return assertThrows(AlpacaCompilerError.class, () -> analyze(src));
    }

    // ============================================================
    //  Valid programs
    // ============================================================

    @Test
    @DisplayName("Accepts a program using globals, objects, members and calls")
    void testValidProgram() {
        analyze("""
                int global = 10;
                object House {
                    public int price;
                }
                int doublePrice(int x) {
                    House h;
                    return x * 2;
                }
                void run() {
                    int a = global;
                    int b = doublePrice(a);
                    House h;
                    h.price = b;
                }
                """);
    }

    @Test
    @DisplayName("Accepts shadowing of an outer variable in an inner block")
    void testShadowingAllowed() {
        analyze("""
                void f() {
                    int x;
                    {
                        int x;
                    }
                }
                """);
    }

    @Test
    @DisplayName("Accepts private member access from inside the declaring object")
    void testPrivateAccessFromInside() {
        analyze("""
                object House {
                    private int price;
                    int readPrice() {
                        House h;
                        return h.price;
                    }
                }
                """);
    }

    @Test
    @DisplayName("Accepts public member access from outside the object")
    void testPublicAccessFromOutside() {
        analyze("""
                object House {
                    public int price;
                }
                void f() {
                    House h;
                    h.price = 5;
                }
                """);
    }

    @Test
    @DisplayName("Accepts method calls on object variables")
    void testMethodCall() {
        analyze("""
                object House {
                    public int get() {
                        return 1;
                    }
                }
                void f() {
                    House h;
                    int x = h.get();
                }
                """);
    }

    @Test
    @DisplayName("Accepts const initialization and reads")
    void testConstReadAllowed() {
        analyze("""
                @const int MAX = 10;
                void f() {
                    int x = MAX;
                }
                """);
    }

    // ============================================================
    //  Undefined names
    // ============================================================

    @Test
    @DisplayName("Rejects undefined variables")
    void testUndefinedVariable() {
        AlpacaCompilerError e = analyzeError("void f() { unknown = 5; }");
        assertTrue(e.getMessage().contains("undefined variable 'unknown'"));
    }

    @Test
    @DisplayName("Rejects undefined variables in expressions")
    void testUndefinedVariableInExpression() {
        AlpacaCompilerError e = analyzeError("void f() { int x = unknown; }");
        assertTrue(e.getMessage().contains("undefined variable 'unknown'"));
    }

    @Test
    @DisplayName("Rejects undefined functions")
    void testUndefinedFunction() {
        AlpacaCompilerError e = analyzeError("void f() { missing(); }");
        assertTrue(e.getMessage().contains("undefined function 'missing'"));
    }

    @Test
    @DisplayName("Rejects calls on variables")
    void testCallOnVariable() {
        AlpacaCompilerError e = analyzeError("int x; void f() { x(); }");
        assertTrue(e.getMessage().contains("cannot call 'x': not a function"));
    }

    @Test
    @DisplayName("Rejects object names used as values")
    void testObjectNameAsValue() {
        AlpacaCompilerError e = analyzeError("object House {} void f() { House h = House; }");
        assertTrue(e.getMessage().contains("cannot use object 'House' as a value"));
    }

    // ============================================================
    //  Duplicate declarations
    // ============================================================

    @Test
    @DisplayName("Rejects duplicate global variables")
    void testDuplicateGlobal() {
        AlpacaCompilerError e = analyzeError("int x; int x;");
        assertTrue(e.getMessage().contains("duplicate declaration of 'x'"));
    }

    @Test
    @DisplayName("Rejects a function sharing a name with an object")
    void testDuplicateFunctionAndObject() {
        AlpacaCompilerError e = analyzeError("object House {} void House() {}");
        assertTrue(e.getMessage().contains("duplicate declaration of 'House'"));
    }

    @Test
    @DisplayName("Rejects duplicate object members")
    void testDuplicateMember() {
        AlpacaCompilerError e = analyzeError("object A { int x; int x; }");
        assertTrue(e.getMessage().contains("duplicate member 'x' in object 'A'"));
    }

    @Test
    @DisplayName("Rejects a field and method sharing a name")
    void testDuplicateFieldAndMethod() {
        AlpacaCompilerError e = analyzeError("object A { int x; int x() {} }");
        assertTrue(e.getMessage().contains("duplicate member 'x' in object 'A'"));
    }

    @Test
    @DisplayName("Rejects duplicate parameters")
    void testDuplicateParameter() {
        AlpacaCompilerError e = analyzeError("void f(int a, int a) {}");
        assertTrue(e.getMessage().contains("duplicate parameter 'a' in function 'f'"));
    }

    @Test
    @DisplayName("Rejects duplicate local variables")
    void testDuplicateLocal() {
        AlpacaCompilerError e = analyzeError("void f() { int x; int x; }");
        assertTrue(e.getMessage().contains("duplicate local variable 'x'"));
    }

    @Test
    @DisplayName("Rejects duplicate type parameters")
    void testDuplicateTypeParameter() {
        AlpacaCompilerError e = analyzeError("@generic(T, T) void f() {}");
        assertTrue(e.getMessage().contains("duplicate type parameter 'T'"));
    }

    // ============================================================
    //  Visibility
    // ============================================================

    @Test
    @DisplayName("Rejects private member access from outside the object")
    void testPrivateAccessFromOutside() {
        AlpacaCompilerError e = analyzeError("""
                object House { private int price; }
                void f() {
                    House h;
                    h.price = 5;
                }
                """);
        assertTrue(e.getMessage().contains("cannot access private member 'price' of object 'House'"));
    }

    @Test
    @DisplayName("Rejects private member access from a different object")
    void testPrivateAccessFromOtherObject() {
        AlpacaCompilerError e = analyzeError("""
                object House { private int price; }
                object Car {
                    int inspect() {
                        House h;
                        return h.price;
                    }
                }
                """);
        assertTrue(e.getMessage().contains("cannot access private member 'price' of object 'House'"));
    }

    @Test
    @DisplayName("Rejects access to unknown members")
    void testUnknownMember() {
        AlpacaCompilerError e = analyzeError("""
                object House { int price; }
                void f() {
                    House h;
                    h.window = 5;
                }
                """);
        assertTrue(e.getMessage().contains("unknown member 'window' on object 'House'"));
    }

    // ============================================================
    //  @const
    // ============================================================

    @Test
    @DisplayName("Rejects assignment to a const global")
    void testAssignToConstGlobal() {
        AlpacaCompilerError e = analyzeError("@const int MAX = 10; void f() { MAX = 20; }");
        assertTrue(e.getMessage().contains("cannot assign to constant 'MAX'"));
    }

    @Test
    @DisplayName("Rejects increment of a const global")
    void testIncrementConstGlobal() {
        AlpacaCompilerError e = analyzeError("@const int MAX = 10; void f() { MAX++; }");
        assertTrue(e.getMessage().contains("cannot assign to constant 'MAX'"));
    }

    @Test
    @DisplayName("Rejects assignment to a const field")
    void testAssignToConstField() {
        AlpacaCompilerError e = analyzeError("""
                object A { @const public int x = 1; }
                void f() {
                    A a;
                    a.x = 2;
                }
                """);
        assertTrue(e.getMessage().contains("cannot assign to constant field 'x'"));
    }

    // ============================================================
    //  Implicit field access inside objects
    // ============================================================

    @Test
    @DisplayName("Accepts a method that writes its own field (implicit field access)")
    void testMethodMutatesOwnField() {
        analyze("""
                object Person {
                    string name;
                    void set_name(string new_name) {
                        name = new_name;
                    }
                }
                """);
    }

    @Test
    @DisplayName("Accepts a method that reads its own field")
    void testMethodReadsOwnField() {
        analyze("""
                object Person {
                    string name;
                    string get_name() {
                        return name;
                    }
                }
                """);
    }

    @Test
    @DisplayName("Accepts a field initializer referencing another field")
    void testFieldInitializerReferencesField() {
        analyze("""
                object A {
                    int x = 5;
                    int y = x;
                }
                """);
    }

    @Test
    @DisplayName("Rejects a write to a bare name colliding with a field")
    void testParameterFieldConflictOnWrite() {
        AlpacaCompilerError e = analyzeError("""
                object Person {
                    int age;
                    void set_age(int age) {
                        age = age;
                    }
                }
                """);
        assertTrue(e.getMessage().contains("ambiguous reference 'age'"));
    }

    @Test
    @DisplayName("Rejects an increment on a bare name colliding with a field")
    void testParameterFieldConflictOnPostfix() {
        AlpacaCompilerError e = analyzeError("""
                object Person {
                    int age;
                    void bump(int age) {
                        age++;
                    }
                }
                """);
        assertTrue(e.getMessage().contains("ambiguous reference 'age'"));
    }

    @Test
    @DisplayName("Accepts 'this.field' to disambiguate a colliding name")
    void testThisFieldDisambiguates() {
        analyze("""
                object Person {
                    int age;
                    void set_age(int age) {
                        this.age = age;
                    }
                }
                """);
    }

    @Test
    @DisplayName("Accepts reads of a bare name colliding with a field (variable wins)")
    void testReadPrefersVariableOverField() {
        analyze("""
                object Person {
                    int age;
                    int get_age(int age) {
                        return age;
                    }
                }
                """);
    }

    @Test
    @DisplayName("Accepts method calls via 'this'")
    void testThisMethodCall() {
        analyze("""
                object A {
                    int get() {
                        return 1;
                    }
                    int f() {
                        return this.get();
                    }
                }
                """);
    }

    @Test
    @DisplayName("Rejects 'this.member' for unknown members")
    void testThisUnknownMember() {
        AlpacaCompilerError e = analyzeError("""
                object Person {
                    int age;
                    void f() {
                        this.unknown = 1;
                    }
                }
                """);
        assertTrue(e.getMessage().contains("unknown member 'unknown' on object 'Person'"));
    }

    @Test
    @DisplayName("Rejects 'this' outside of an object")
    void testThisOutsideObject() {
        AlpacaCompilerError e = analyzeError("void f() { this.x = 1; }");
        assertTrue(e.getMessage().contains("'this' can only be used inside an object"));
    }

    @Test
    @DisplayName("Rejects 'this' used as a value")
    void testThisAsValue() {
        AlpacaCompilerError e = analyzeError("object A { void f() { int x = this; } }");
        assertTrue(e.getMessage().contains("'this' can only be used with member access"));
    }

    @Test
    @DisplayName("Rejects implicit field access outside the object")
    void testImplicitFieldAccessOutsideObject() {
        AlpacaCompilerError e = analyzeError("""
                object Person { string name; }
                void f() {
                    name = "x";
                }
                """);
        assertTrue(e.getMessage().contains("undefined variable 'name'"));
    }

    @Test
    @DisplayName("Rejects unknown unqualified names inside methods")
    void testUnknownNameInsideMethod() {
        AlpacaCompilerError e = analyzeError("""
                object Person {
                    string name;
                    void f() {
                        unknown_thing = 1;
                    }
                }
                """);
        assertTrue(e.getMessage().contains("undefined variable 'unknown_thing'"));
    }

    @Test
    @DisplayName("Rejects assignment to a const field via implicit access")
    void testAssignToConstFieldImplicitly() {
        AlpacaCompilerError e = analyzeError("""
                object A {
                    @const int x = 1;
                    void f() {
                        x = 2;
                    }
                }
                """);
        assertTrue(e.getMessage().contains("cannot assign to constant field 'x'"));
    }

    // ============================================================
    //  Invalid assignment targets
    // ============================================================

    @Test
    @DisplayName("Rejects assignment to a call expression")
    void testInvalidAssignmentTarget() {
        AlpacaCompilerError e = analyzeError("void g() {} void f() { g() = 5; }");
        assertTrue(e.getMessage().contains("invalid assignment target"));
    }

    // ============================================================
    //  Constructors
    // ============================================================

    @Test
    @DisplayName("Accepts constructing an object with a public constructor")
    void testConstructionWithConstructor() {
        analyze("""
                object Point {
                    public int x;
                    public construct(int x, int y) {
                        this.x = x;
                    }
                }
                void f() {
                    Point p = Point(1, 2);
                }
                """);
    }

    @Test
    @DisplayName("Accepts a constructor without modifier inheriting the object visibility")
    void testConstructorInheritsObjectVisibility() {
        analyze("""
                public object Point {
                    construct() { }
                }
                void f() {
                    Point p = Point();
                }
                """);
    }

    @Test
    @DisplayName("Accepts a private constructor from inside the declaring object")
    void testPrivateConstructorFromInside() {
        analyze("""
                object A {
                    private construct(int x) { }
                    public A make(int x) {
                        return A(x);
                    }
                }
                """);
    }

    @Test
    @DisplayName("Rejects constructing an object without a constructor")
    void testConstructionWithoutConstructor() {
        AlpacaCompilerError e = analyzeError("""
                object A { }
                void f() {
                    A a = A();
                }
                """);
        assertTrue(e.getMessage().contains("has no constructor and cannot be constructed"));
    }

    @Test
    @DisplayName("Rejects accessing a private constructor from outside")
    void testPrivateConstructorFromOutside() {
        AlpacaCompilerError e = analyzeError("""
                object A {
                    private construct() { }
                }
                void f() {
                    A a = A();
                }
                """);
        assertTrue(e.getMessage().contains("cannot access private constructor of object 'A'"));
    }

    @Test
    @DisplayName("Rejects duplicate parameters in a constructor")
    void testConstructorDuplicateParameter() {
        AlpacaCompilerError e = analyzeError("object A { construct(int x, int x) { } }");
        assertTrue(e.getMessage().contains("duplicate parameter 'x'"));
    }

    @Test
    @DisplayName("Accepts implicit field access and this in a constructor body")
    void testConstructorBody() {
        analyze("""
                object Person {
                    public int age;
                    public string name;
                    public construct(int age, string name) {
                        this.age = age;
                        this.name = name;
                    }
                }
                """);
    }

    // ============================================================
    //  Constructor edge cases
    // ============================================================

    @Test
    @DisplayName("Rejects undefined names inside a constructor body")
    void testConstructorUndefinedName() {
        AlpacaCompilerError e = analyzeError("object A { public construct() { missing = 1; } }");
        assertTrue(e.getMessage().contains("undefined variable 'missing'"));
    }

    @Test
    @DisplayName("Rejects construction arguments that do not resolve")
    void testConstructionUndefinedArgument() {
        AlpacaCompilerError e = analyzeError("""
                object A {
                    public construct(int x) { }
                }
                void f() {
                    A a = A(missing);
                }
                """);
        assertTrue(e.getMessage().contains("undefined variable 'missing'"));
    }

    @Test
    @DisplayName("Rejects a private constructor from a field initializer of another object")
    void testPrivateConstructorFromFieldInitializer() {
        AlpacaCompilerError e = analyzeError("""
                object Secret {
                    private construct() { }
                }
                object Holder {
                    int x = Secret();
                }
                """);
        assertTrue(e.getMessage().contains("cannot access private constructor of object 'Secret'"));
    }

    @Test
    @DisplayName("Accepts a private constructor from the object's own field initializer")
    void testPrivateConstructorFromOwnFieldInitializer() {
        analyze("""
                object Secret {
                    private construct() { }
                    Secret next = Secret();
                }
                """);
    }

    @Test
    @DisplayName("Rejects 'this' assignment outside an object")
    void testThisAssignmentOutsideObject() {
        AlpacaCompilerError e = analyzeError("void f() { this.x = 1; }");
        assertTrue(e.getMessage().contains("'this' can only be used inside an object"));
    }

    @Test
    @DisplayName("Rejects 'this' returned as a bare value")
    void testThisBareValueInReturn() {
        AlpacaCompilerError e = analyzeError("object A { int f() { return this; } }");
        assertTrue(e.getMessage().contains("'this' can only be used with member access"));
    }

    @Test
    @DisplayName("Rejects a duplicate top-level name across object and function")
    void testDuplicateObjectAndFunction() {
        AlpacaCompilerError e = analyzeError("object A { } void A() { }");
        assertTrue(e.getMessage().contains("duplicate declaration of 'A'"));
    }

    @Test
    @DisplayName("Rejects using a type parameter as a value")
    void testTypeParameterAsValue() {
        // Type parameters are never registered as symbols, so a bare use falls
        // through to "undefined variable" (the TYPE_PARAMETER kind is unused).
        AlpacaCompilerError e = analyzeError("""
                @generic(T)
                void f() {
                    T x = T;
                }
                """);
        assertTrue(e.getMessage().contains("undefined variable 'T'"));
    }

    @Test
    @DisplayName("Rejects accessing a private method from outside")
    void testPrivateMethodFromOutside() {
        AlpacaCompilerError e = analyzeError("""
                object A {
                    void secret() { }
                }
                void f() {
                    A a;
                    a.secret();
                }
                """);
        assertTrue(e.getMessage().contains("cannot access private member 'secret'"));
    }

    // ============================================================
    //  Bundled stdlib integration
    // ============================================================

    @Test
    @DisplayName("Analyzes every bundled stdlib module without errors")
    void testBundledStdlibAnalyzes() throws IOException {
        Path libDir = Path.of("src/main/java/net/rusrusme/alpaca/runtime/lib");
        List<Declaration> declarations = new ArrayList<>();
        try (var stream = Files.walk(libDir)) {
            for (Path file : stream.filter(path -> path.toString().endsWith(".alpaca")).toList()) {
                Program program = new AlpacaParser(file.getFileName().toString(),
                        new AlpacaLexer().tokenize(Files.readString(file))).construct();
                declarations.addAll(program.declarations());
            }
        }
        Assumptions.assumeFalse(declarations.isEmpty(),
                "no stdlib modules found in runtime/lib; skipping");
        Program program = new Program("stdlib", List.of(), declarations);
        SemanticAnalyzer analyzer = new SemanticAnalyzer();
        analyzer.analyze(program);
        new TypeChecker(analyzer.symbolTable(), analyzer.resolvedSymbols()).check(program);
    }
}
