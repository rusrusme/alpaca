package net.rusrusme.alpacac;

import java.util.List;
import java.util.Optional;

/**
 * The `SyntaxTree` class represents the abstract syntax tree (AST) for the Alpaca programming language.
 * It defines the structure of the language's syntax, including declarations, statements, expressions, and types.
 * The AST is used to represent the parsed source code in a structured format that can be further processed by
 * the compiler or interpreter.
 */
public final class SyntaxTree {
    /**
     * Program represents the root of the syntax tree, containing the program's name, imports, and top-level declarations.
     * @param name
     * @param imports
     * @param declarations
     */
    public record Program(
            String name,
            List<Import> imports,
            List<Declaration> declarations
    ) implements Node {}

    /**
     * Import represents an import statement in the syntax tree, specifying the path of the imported module or file.
     * @param path
     */
    public record Import(String path) implements Node {}

    /**
     * The `Node` interface serves as the base type for all elements in the syntax tree.
     * It is a marker interface that allows for polymorphic handling of different types of nodes in the AST.
     */
    public interface Node {}

    /**
     * Top-level declarations
     * <p>
     * These nodes define program structure, top-level items, and metadata attached to declarations.
     */
    public sealed interface Declaration extends Node permits
            FunctionDeclaration,    // void func() {...}
            ObjectDeclaration,      // object House {...}
            GlobalVariable,         // int x = ...
            StatementDeclaration {}

    /**
     * Decorators are metadata annotations that can be attached to declarations, providing additional information or behavior.
     *
     * @param name
     * @param args
     */
    public record Decorator(String name, List<DecoratorArg> args) implements Node {}

    public sealed interface DecoratorArg extends Node permits ValueArg, TypeArg {}

    public record ValueArg(Expression expression) implements DecoratorArg {}

    public record TypeArg(Type type) implements DecoratorArg {}

    // -----------------------------------------------------------------------------------------------------------------

    public record Parameter(Type type, String name) implements Node {}

    public record Field(List<Decorator> decorators, Type type, String name, Visibility visibility) implements Node {}

    // -----------------------------------------------------------------------------------------------------------------

    public record FunctionDeclaration(
            List<Decorator> decorators,
            Type returnType,
            String name,
            List<Parameter> parameters,
            Optional<Block> body, // Optional for abstract/interface methods
            Visibility visibility
    ) implements Declaration {}

    public record ObjectDeclaration(
            List<Decorator> decorators,
            String name,
            List<Field> fields,
            List<FunctionDeclaration> methods,
            Visibility visibility
    ) implements Declaration {}

    public record GlobalVariable(
            List<Decorator> decorators,
            Type type,
            String name,
            Optional<Expression> initializer,
            Visibility visibility
    ) implements Declaration {}

    public record StatementDeclaration(Statement statement) implements Declaration {}

    /**
     * Statements represent actions or control flow operations executed within a function or block.
     */
    public sealed interface Statement extends Node permits
            Block,
            IfStatement,
            ForStatement,
            WhileStatement,
            DoWhileStatement,
            SwitchStatement,
            SimpleStatement {}

    public record Block(List<Statement> statements) implements Statement {}

    public record IfStatement(
            Expression condition,
            Statement thenBranch,
            Optional<Statement> elseBranch
    ) implements Statement {}

    public record ForStatement(
            Optional<SimpleStatement> init,
            Optional<Expression> condition,
            Optional<SimpleStatement> update,
            Statement body
    ) implements Statement {}

    public record WhileStatement(Expression condition, Statement body) implements Statement {}

    public record DoWhileStatement(Statement body, Expression condition) implements Statement {}

    public record SwitchStatement(Expression selector, List<SwitchCase> cases) implements Statement {}

    public record SwitchCase(List<Expression> labels, List<Statement> statements) implements Node {}

    /**
     * Simple statements are basic actions that can be executed,
     * such as variable declarations, assignments, return statements,
     * expression evaluations, or unsafe code blocks.
     */
    public sealed interface SimpleStatement extends Statement permits
            Assignment,
            BreakStatement,
            ContinueStatement,
            ExpressionStatement,
            ReturnStatement,
            UnsafeStatement,
            LocalVariable {}

    public record Assignment(Expression target, Expression value) implements SimpleStatement {}

    public record BreakStatement() implements SimpleStatement {}

    public record ContinueStatement() implements SimpleStatement {}

    /**
     * ExpressionStatement represents a statement that evaluates an expression for its side effects.
     *
     * @param expression
     */
    public record ExpressionStatement(Expression expression) implements SimpleStatement {}

    public record ReturnStatement(Optional<Expression> value) implements SimpleStatement {}

    /**
     * UnsafeStatement represents a statement that contains raw code compiled directly in C, which may not be type-checked or safe to execute.
     *
     * @param code The raw code to be executed, which is expected to be valid C code.
     */
    public record UnsafeStatement(String code) implements SimpleStatement {}

    public record LocalVariable(Type type, String name, Optional<Expression> initializer) implements SimpleStatement {}

    /**
     * Expressions represent computations, values, and operations that produce a result.
     * They can be literals, identifiers, unary or binary operations, function calls,
     * tuples, arrays, member accesses, or index accesses.
     */
    public sealed interface Expression extends Node permits
            Literal,
            Identifier,
            UnaryOp,
            BinaryOp,
            FunctionCall,
            TupleExpression,
            ArrayLiteral,
            MemberAccess,
            IsExpression,
            IndexAccess {}

    /**
     * Literals represent constant values in the code, such as integers, floats, booleans, characters, and strings.
     */
    public sealed interface Literal extends Expression permits
            IntLiteral,
            FloatLiteral,
            BoolLiteral,
            CharLiteral,
            StringLiteral {}

    public record IntLiteral(long value) implements Literal {}

    public record FloatLiteral(double value) implements Literal {}

    public record BoolLiteral(boolean value) implements Literal {}

    public record CharLiteral(char value) implements Literal {}

    public record StringLiteral(String value) implements Literal {}

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * IsExpression represents a type-checking expression that checks if a given expression is of a specified target type.
     *
     * @param expression
     * @param targetType
     */
    public record IsExpression(Expression expression, Type targetType) implements Expression {}

    public record Identifier(String name) implements Expression {}

    public record UnaryOp(String operator, Expression operand) implements Expression {}

    public record BinaryOp(Expression left, String operator, Expression right) implements Expression {}

    public record FunctionCall(Expression callee, List<Expression> arguments) implements Expression {}

    public record TupleExpression(List<Expression> elements) implements Expression {}

    public record ArrayLiteral(List<Expression> elements) implements Expression {}

    public record MemberAccess(Expression object, String member) implements Expression {}

    public record IndexAccess(Expression array, Expression index) implements Expression {}

    /**
     * Types represent the data types of variables, function return values, and other expressions.
     * They can be named types, generic types, array types, or tuple types.
     */
    public sealed interface Type extends Node permits
            NamedType,
            GenericType,
            ArrayType,
            TupleType,
            FunctionType {}

    /**
     * NamedType represents a simple named type, such as "int", "string", or a user-defined type.
     *
     * @param name
     */
    public record NamedType(String name) implements Type {}

    /**
     * GenericType represents a generic type with a base type and a list of type arguments.
     *
     * @param base The base type name (e.g., "List", "Map").
     * @param args The list of type arguments for the generic type (e.g., "int", "string").
     */
    public record GenericType(String base, List<Type> args) implements Type {}

    public record ArrayType(Type element) implements Type {}

    public record TupleType(List<Type> elements) implements Type {}

    /**
     * FunctionType represents the type of a function, including its parameter types and return type.
     *
     * @param paramTypes
     * @param returnType
     */
    public record FunctionType(List<Type> paramTypes, Type returnType) implements Type {}

    /**
     * Visibility modifiers for declarations, indicating whether they are accessible from outside their defining scope.
     */
    public enum Visibility {PUBLIC, PRIVATE}

    // -----------------------------------------------------------------------------------------------------------------

    private SyntaxTree() {}
}
