import net.rusrusme.alpacac.AlpacaCompilerError;
import net.rusrusme.alpacac.AlpacaLexer;
import net.rusrusme.alpacac.AlpacaParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static net.rusrusme.alpacac.SyntaxTree.*;
import static org.junit.jupiter.api.Assertions.*;

class ParserTests {

    private Program parse(String src) {
        return new AlpacaParser("test.alpaca", new AlpacaLexer().tokenize(src)).construct();
    }

    private ObjectDeclaration parseObject(String src) {
        return (ObjectDeclaration) parse(src).declarations().getFirst();
    }

    // ============================================================
    //  Decorators
    // ============================================================

    @Test
    @DisplayName("Parses all four decorators before an object declaration")
    void testAllDecorators() {
        ObjectDeclaration decl = parseObject("""
                @generic(T, U)
                @pure
                @docs("A house object")
                @const
                object House {}
                """);
        List<Decorator> decorators = decl.decorators();
        assertEquals(4, decorators.size());
        assertInstanceOf(GenericDecorator.class, decorators.getFirst());
        assertInstanceOf(PureDecorator.class, decorators.get(1));
        assertInstanceOf(DocsDecorator.class, decorators.get(2));
        assertInstanceOf(ConstDecorator.class, decorators.get(3));
    }

    @Test
    @DisplayName("@generic captures type parameters as NamedType")
    void testGenericDecorator() {
        ObjectDeclaration decl = parseObject("@generic(T, U) object House {}");
        GenericDecorator decorator = (GenericDecorator) decl.decorators().getFirst();
        assertEquals(List.of(new NamedType("T"), new NamedType("U")), decorator.typeParams());
    }

    @Test
    @DisplayName("@docs stores the unquoted string literal")
    void testDocsDecorator() {
        ObjectDeclaration decl = parseObject("@docs(\"A house object\") object House {}");
        DocsDecorator decorator = (DocsDecorator) decl.decorators().getFirst();
        assertEquals("A house object", decorator.documentation());
    }

    @Test
    @DisplayName("@docs resolves escape sequences via the token literal")
    void testDocsDecoratorEscapes() {
        ObjectDeclaration decl = parseObject("@docs(\"line\\nbreak\") object House {}");
        DocsDecorator decorator = (DocsDecorator) decl.decorators().getFirst();
        assertEquals("line\nbreak", decorator.documentation());
    }

    // ============================================================
    //  Decorator error cases
    // ============================================================

    @Test
    @DisplayName("Rejects unknown decorators")
    void testUnknownDecorator() {
        AlpacaCompilerError e = assertThrows(AlpacaCompilerError.class, () -> parse("@unknown object A {}"));
        assertTrue(e.getMessage().contains("unknown decorator '@unknown'"));
    }

    @Test
    @DisplayName("Rejects arguments on @pure")
    void testPureWithArguments() {
        AlpacaCompilerError e = assertThrows(AlpacaCompilerError.class, () -> parse("@pure(\"x\") object A {}"));
        assertTrue(e.getMessage().contains("'@pure' takes no arguments"));
    }

    @Test
    @DisplayName("Rejects arguments on @const")
    void testConstWithArguments() {
        AlpacaCompilerError e = assertThrows(AlpacaCompilerError.class, () -> parse("@const(1) object A {}"));
        assertTrue(e.getMessage().contains("'@const' takes no arguments"));
    }

    @Test
    @DisplayName("Rejects non-string argument on @docs")
    void testDocsNonString() {
        AlpacaCompilerError e = assertThrows(AlpacaCompilerError.class, () -> parse("@docs(42) object A {}"));
        assertTrue(e.getMessage().contains("expected a string literal in '@docs'"));
    }

    @Test
    @DisplayName("Rejects empty @generic")
    void testGenericEmpty() {
        AlpacaCompilerError e = assertThrows(AlpacaCompilerError.class, () -> parse("@generic() object A {}"));
        assertTrue(e.getMessage().contains("expected a type name"));
    }

    @Test
    @DisplayName("Rejects object without closing brace")
    void testMissingClosingBrace() {
        AlpacaCompilerError e = assertThrows(AlpacaCompilerError.class, () -> parse("@docs(\"x\") object A {"));
        assertTrue(e.getMessage().contains("expected '}' after object body"));
    }

    // ============================================================
    //  parseType (used by @generic)
    // ============================================================

    @Test
    @DisplayName("@generic accepts primitive types incl. unsigned long")
    void testGenericPrimitive() {
        ObjectDeclaration decl = parseObject("@generic(int, unsigned long) object A {}");
        GenericDecorator decorator = (GenericDecorator) decl.decorators().getFirst();
        assertEquals(List.of(new NamedType("int"), new NamedType("unsigned long")), decorator.typeParams());
    }

    @Test
    @DisplayName("@generic accepts array types")
    void testGenericArray() {
        ObjectDeclaration decl = parseObject("@generic(int[]) object A {}");
        GenericDecorator decorator = (GenericDecorator) decl.decorators().getFirst();
        assertEquals(List.of(new ArrayType(new NamedType("int"))), decorator.typeParams());
    }

    // ============================================================
    //  Functions & globals
    // ============================================================

    @Test
    @DisplayName("Parses a function with parameters and a return statement")
    void testFunctionDeclaration() {
        Program program = parse("""
                int add(int a, int b) {
                    return a + b;
                }
                """);
        FunctionDeclaration fn = (FunctionDeclaration) program.declarations().getFirst();
        assertEquals(new NamedType("int"), fn.returnType());
        assertEquals("add", fn.name());
        assertEquals(List.of(new Parameter(new NamedType("int"), "a"), new Parameter(new NamedType("int"), "b")), fn.parameters());
        assertTrue(fn.body().isPresent());
        ReturnStatement ret = (ReturnStatement) fn.body().get().statements().getFirst();
        assertEquals(new BinaryOp(new Identifier("a"), "+", new Identifier("b")), ret.value().orElseThrow());
    }

    @Test
    @DisplayName("Parses the example program (import, @generic, unsafe)")
    void testExampleProgram() {
        String src = """
                import lama.lib.SysOut;
                
                @generic(T)
                void println(T t) {
                    unsafe {
                        printf("__FORMAT__", t);
                    }
                }
                """;
        Program program = parse(src);
        assertEquals(1, program.imports().size());
        assertEquals("lama.lib.SysOut", program.imports().getFirst().path());

        FunctionDeclaration fn = (FunctionDeclaration) program.declarations().getFirst();
        assertEquals("println", fn.name());
        assertEquals(new NamedType("void"), fn.returnType());
        assertInstanceOf(GenericDecorator.class, fn.decorators().getFirst());
        assertEquals(new Parameter(new NamedType("T"), "t"), fn.parameters().getFirst());

        UnsafeStatement unsafe = (UnsafeStatement) fn.body().get().statements().getFirst();
        assertTrue(unsafe.code().contains("printf"));
    }

    @Test
    @DisplayName("Parses a global variable with initializer")
    void testGlobalVariable() {
        Program program = parse("int answer = 42;");
        GlobalVariable global = (GlobalVariable) program.declarations().getFirst();
        assertEquals(new NamedType("int"), global.type());
        assertEquals("answer", global.name());
        assertEquals(new IntLiteral(42), global.initializer().orElseThrow());
    }

    @Test
    @DisplayName("Parses a global variable without initializer")
    void testGlobalVariableNoInit() {
        Program program = parse("string name;");
        GlobalVariable global = (GlobalVariable) program.declarations().getFirst();
        assertEquals(new NamedType("string"), global.type());
        assertTrue(global.initializer().isEmpty());
    }

    @Test
    @DisplayName("Parses array-typed and generic-typed globals")
    void testGlobalVariableTypes() {
        Program program = parse("int[] arr; Array<int> list; unsigned long big;");
        assertEquals(new ArrayType(new NamedType("int")), ((GlobalVariable) program.declarations().getFirst()).type());
        assertEquals(new GenericType("Array", List.of(new NamedType("int"))), ((GlobalVariable) program.declarations().get(1)).type());
        assertEquals(new NamedType("unsigned long"), ((GlobalVariable) program.declarations().get(2)).type());
    }

    @Test
    @DisplayName("Parses a tuple type")
    void testTupleType() {
        Program program = parse("(int, string) pair;");
        GlobalVariable global = (GlobalVariable) program.declarations().getFirst();
        assertEquals(new TupleType(List.of(new NamedType("int"), new NamedType("string"))), global.type());
    }

    // ============================================================
    //  Objects
    // ============================================================

    @Test
    @DisplayName("Parses an object with fields and methods")
    void testObjectMembers() {
        ObjectDeclaration decl = parseObject("""
                object House {
                    public int size = 10;
                    private string name;
                    public void grow(int n) {
                        size = size + n;
                    }
                }
                """);
        assertEquals("House", decl.name());
        // No visibility modifier on the object itself -> PRIVATE (parser default)
        assertEquals(Visibility.PRIVATE, decl.visibility());

        assertEquals(2, decl.fields().size());
        Field size = decl.fields().getFirst();
        assertEquals(new NamedType("int"), size.type());
        assertEquals("size", size.name());
        assertEquals(Visibility.PUBLIC, size.visibility());
        assertEquals(new IntLiteral(10), size.initializer().orElseThrow());

        Field name = decl.fields().get(1);
        assertEquals(Visibility.PRIVATE, name.visibility());
        assertTrue(name.initializer().isEmpty());

        assertEquals(1, decl.methods().size());
        FunctionDeclaration grow = decl.methods().getFirst();
        assertEquals("grow", grow.name());
        assertEquals(Visibility.PUBLIC, grow.visibility());
        assertEquals(new Parameter(new NamedType("int"), "n"), grow.parameters().getFirst());
    }

    // ============================================================
    //  Statements
    // ============================================================

    private Block parseFunctionBody(String src) {
        Program program = parse("void f() { " + src + " }");
        FunctionDeclaration fn = (FunctionDeclaration) program.declarations().getFirst();
        return fn.body().orElseThrow();
    }

    @Test
    @DisplayName("Parses if with block, else-if and inline forms")
    void testIfStatement() {
        Block body = parseFunctionBody("""
                if x > 0 {
                    x = 1;
                }
                else if x == 0 : x = 2;
                else { x = 3; }
                """);
        IfStatement outer = (IfStatement) body.statements().getFirst();
        assertTrue(outer.elseBranch().isPresent());
        IfStatement middle = (IfStatement) outer.elseBranch().get();
        assertTrue(middle.elseBranch().isPresent());
    }

    @Test
    @DisplayName("Parses while and do-while loops")
    void testLoops() {
        Block body = parseFunctionBody("while x < 10 { x = x + 1; } do : x = x - 1; while x > 0;");
        WhileStatement whileStmt = (WhileStatement) body.statements().getFirst();
        assertEquals(new BinaryOp(new Identifier("x"), "<", new IntLiteral(10)), whileStmt.condition());

        DoWhileStatement doStmt = (DoWhileStatement) body.statements().get(1);
        assertEquals(new BinaryOp(new Identifier("x"), ">", new IntLiteral(0)), doStmt.condition());
        assertInstanceOf(Assignment.class, doStmt.body());
    }

    @Test
    @DisplayName("Parses a for loop with init, condition and update")
    void testForStatement() {
        Block body = parseFunctionBody("for int i = 0 : i < 10 : i++ { x = x + i; }");
        ForStatement forStmt = (ForStatement) body.statements().getFirst();
        LocalVariable init = (LocalVariable) forStmt.init().orElseThrow();
        assertEquals("i", init.name());
        assertEquals(new IntLiteral(0), init.initializer().orElseThrow());
        assertEquals(new BinaryOp(new Identifier("i"), "<", new IntLiteral(10)), forStmt.condition().orElseThrow());
        ExpressionStatement update = (ExpressionStatement) forStmt.update().orElseThrow();
        assertEquals(new PostfixOp("++", new Identifier("i")), update.expression());
    }

    @Test
    @DisplayName("Parses a switch with case, arrow and default")
    void testSwitchStatement() {
        Block body = parseFunctionBody("""
                switch x {
                    case 1 { y = 10; }
                    case 2 -> y = 20;
                    default { y = 0; }
                }
                """);
        SwitchStatement sw = (SwitchStatement) body.statements().getFirst();
        assertEquals(3, sw.cases().size());

        SwitchCase case1 = sw.cases().getFirst();
        assertEquals(new IntLiteral(1), case1.label());
        assertInstanceOf(Block.class, case1.body());

        SwitchCase case2 = sw.cases().get(1);
        assertEquals(new IntLiteral(2), case2.label());
        // "case 2 -> y = 20;" is an assignment statement
        assertInstanceOf(Assignment.class, case2.body());

        SwitchCase def = sw.cases().get(2);
        assertNull(def.label());
    }

    @Test
    @DisplayName("Parses break and continue")
    void testBreakContinue() {
        Block body = parseFunctionBody("while true { break; continue; }");
        WhileStatement whileStmt = (WhileStatement) body.statements().getFirst();
        Block loopBody = (Block) whileStmt.body();
        assertInstanceOf(BreakStatement.class, loopBody.statements().getFirst());
        assertInstanceOf(ContinueStatement.class, loopBody.statements().get(1));
    }

    @Test
    @DisplayName("Parses return with and without value")
    void testReturnStatements() {
        Block body = parseFunctionBody("return; return x + 1;");
        ReturnStatement empty = (ReturnStatement) body.statements().getFirst();
        assertTrue(empty.value().isEmpty());
        ReturnStatement valued = (ReturnStatement) body.statements().get(1);
        assertEquals(new BinaryOp(new Identifier("x"), "+", new IntLiteral(1)), valued.value().orElseThrow());
    }

    @Test
    @DisplayName("Parses local variable declarations and assignments")
    void testLocalVariablesAndAssignments() {
        Block body = parseFunctionBody("int x = 5; x = x + 1;");
        LocalVariable decl = (LocalVariable) body.statements().getFirst();
        assertEquals(new NamedType("int"), decl.type());
        assertEquals("x", decl.name());
        assertEquals(new IntLiteral(5), decl.initializer().orElseThrow());

        Assignment assignment = (Assignment) body.statements().get(1);
        assertEquals(new Identifier("x"), assignment.target());
        assertEquals(new BinaryOp(new Identifier("x"), "+", new IntLiteral(1)), assignment.value());
    }

    // ============================================================
    //  Expressions
    // ============================================================

    @Test
    @DisplayName("Respects operator precedence")
    void testPrecedence() {
        Block body = parseFunctionBody("int x = 1 + 2 * 3;");
        LocalVariable decl = (LocalVariable) body.statements().getFirst();
        assertEquals(new BinaryOp(new IntLiteral(1), "+", new BinaryOp(new IntLiteral(2), "*", new IntLiteral(3))),
                decl.initializer().orElseThrow());
    }

    @Test
    @DisplayName("Respects parentheses in expressions")
    void testGrouping() {
        Block body = parseFunctionBody("int x = (1 + 2) * 3;");
        LocalVariable decl = (LocalVariable) body.statements().getFirst();
        assertEquals(new BinaryOp(new BinaryOp(new IntLiteral(1), "+", new IntLiteral(2)), "*", new IntLiteral(3)),
                decl.initializer().orElseThrow());
    }

    @Test
    @DisplayName("Parses logical, is-check, equality and relational operators")
    void testLogicOperators() {
        Block body = parseFunctionBody("bool b = x is int && y == 1 || !z;");
        LocalVariable decl = (LocalVariable) body.statements().getFirst();
        Expression expr = decl.initializer().orElseThrow();
        assertInstanceOf(BinaryOp.class, expr);
        BinaryOp or = (BinaryOp) expr;
        assertEquals("||", or.operator());
        BinaryOp and = (BinaryOp) or.left();
        assertEquals("&&", and.operator());
        IsExpression is = (IsExpression) and.left();
        assertEquals(new Identifier("x"), is.expression());
        assertEquals(new NamedType("int"), is.targetType());
        assertEquals(new UnaryOp("!", new Identifier("z")), or.right());
    }

    @Test
    @DisplayName("Parses literals")
    void testLiterals() {
        Block body = parseFunctionBody("int a = 42; float b = 3.5; bool c = true; char d = 'x'; string e = \"hi\";");
        assertEquals(new IntLiteral(42), ((LocalVariable) body.statements().getFirst()).initializer().orElseThrow());
        assertEquals(new FloatLiteral(3.5), ((LocalVariable) body.statements().get(1)).initializer().orElseThrow());
        assertEquals(new BoolLiteral(true), ((LocalVariable) body.statements().get(2)).initializer().orElseThrow());
        assertEquals(new CharLiteral('x'), ((LocalVariable) body.statements().get(3)).initializer().orElseThrow());
        assertEquals(new StringLiteral("hi"), ((LocalVariable) body.statements().get(4)).initializer().orElseThrow());
    }

    @Test
    @DisplayName("Parses unary and postfix increment/decrement")
    void testIncrementDecrement() {
        Block body = parseFunctionBody("int a = ++x; int b = x++; int c = --y; int d = y--;");
        assertEquals(new UnaryOp("++", new Identifier("x")), ((LocalVariable) body.statements().getFirst()).initializer().orElseThrow());
        assertEquals(new PostfixOp("++", new Identifier("x")), ((LocalVariable) body.statements().get(1)).initializer().orElseThrow());
        assertEquals(new UnaryOp("--", new Identifier("y")), ((LocalVariable) body.statements().get(2)).initializer().orElseThrow());
        assertEquals(new PostfixOp("--", new Identifier("y")), ((LocalVariable) body.statements().get(3)).initializer().orElseThrow());
    }

    @Test
    @DisplayName("Parses array literals")
    void testArrayLiteral() {
        Block body = parseFunctionBody("int[] a = {1, 2, 3};");
        LocalVariable decl = (LocalVariable) body.statements().getFirst();
        assertEquals(new ArrayType(new NamedType("int")), decl.type());
        assertEquals(new ArrayLiteral(List.of(new IntLiteral(1), new IntLiteral(2), new IntLiteral(3))),
                decl.initializer().orElseThrow());
    }

    @Test
    @DisplayName("Parses empty array literal")
    void testEmptyArrayLiteral() {
        Block body = parseFunctionBody("int[] a = {};");
        LocalVariable decl = (LocalVariable) body.statements().getFirst();
        assertEquals(new ArrayLiteral(List.of()), decl.initializer().orElseThrow());
    }

    @Test
    @DisplayName("Parses tuple literals")
    void testTupleLiteral() {
        Block body = parseFunctionBody("(int, int) p = (1, 2);");
        LocalVariable decl = (LocalVariable) body.statements().getFirst();
        assertEquals(new TupleExpression(List.of(new IntLiteral(1), new IntLiteral(2))),
                decl.initializer().orElseThrow());
    }

    @Test
    @DisplayName("Parses function calls, member access and index access")
    void testPostfixChains() {
        Block body = parseFunctionBody("foo.bar(1, 2)[0] = 5;");
        Assignment assignment = (Assignment) body.statements().getFirst();
        IndexAccess index = (IndexAccess) assignment.target();
        FunctionCall call = (FunctionCall) index.array();
        MemberAccess member = (MemberAccess) call.callee();
        assertEquals(new Identifier("foo"), member.object());
        assertEquals("bar", member.member());
        assertEquals(List.of(new IntLiteral(1), new IntLiteral(2)), call.arguments());
        assertEquals(new IntLiteral(0), index.index());
    }

    @Test
    @DisplayName("Generic functions are called without type arguments")
    void testGenericCallWithoutTypeArgs() {
        Block body = parseFunctionBody("print(10);");
        ExpressionStatement stmt = (ExpressionStatement) body.statements().getFirst();
        FunctionCall call = (FunctionCall) stmt.expression();
        assertEquals(new Identifier("print"), call.callee());
        assertEquals(List.of(new IntLiteral(10)), call.arguments());
    }

    // ============================================================
    //  Error cases
    // ============================================================

    @Test
    @DisplayName("Rejects top-level statements")
    void testNoTopLevelStatements() {
        AlpacaCompilerError e = assertThrows(AlpacaCompilerError.class, () -> parse("print(\"hi\");"));
        assertTrue(e.getMessage().contains("expected a top-level declaration"));
    }

    @Test
    @DisplayName("Rejects missing semicolon")
    void testMissingSemicolon() {
        AlpacaCompilerError e = assertThrows(AlpacaCompilerError.class, () -> parse("void f() { int x = 5 }"));
        assertTrue(e.getMessage().contains("expected ';' after statement"));
    }

    @Test
    @DisplayName("Rejects empty tuple type")
    void testEmptyTupleType() {
        AlpacaCompilerError e = assertThrows(AlpacaCompilerError.class, () -> parse("(int) x;"));
        assertTrue(e.getMessage().contains("expected ',' in tuple type"));
    }

    @Test
    @DisplayName("Rejects dangling else-free branch without ':' or '{'")
    void testMissingBranch() {
        AlpacaCompilerError e = assertThrows(AlpacaCompilerError.class, () -> parse("void f() { if x 5; }"));
        assertTrue(e.getMessage().contains("expected ':' or '{'"));
    }
}
