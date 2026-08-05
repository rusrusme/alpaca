# Alpaca Programming Language
## Official EBNF Formal Grammar Specification

---

> **Design Highlights**
> * **Object-Based Programming (OBP):** Encapsulation via `object` with no class inheritance or dynamic polymorphism.
> * **Zero `null` Pointer Errors:** Definite compile-time assignment combined with `Option<T>`.
> * **Type Narrowing:** Safe generic casting and checking using the `is` operator.
> * **Flexible Syntax:** Supports both inline `:` and block `{}` statement forms.

---

## 1. High-Level Program Structure

```ebnf
Program             ::= { TopLevelDeclaration } ;

TopLevelDeclaration ::= [ DecoratorList ] ( ObjectDecl | FunctionDecl | VarDeclStmt ) ;

DecoratorList       ::= { Decorator } ;
Decorator           ::= GenericDecorator | PureDecorator
                      | DocsDecorator | ConstDecorator ;
GenericDecorator    ::= "@generic" "(" TypeList ")" ;
PureDecorator       ::= "@pure" ;
DocsDecorator       ::= "@docs" "(" StringLiteral ")" ;
ConstDecorator      ::= "@const" ;
```

---

## 2. Type System

```ebnf
Type                ::= PrimitiveType
                      | GenericInstantiation
                      | ArrayType
                      | TupleType
                      | ObjectType ;

PrimitiveType       ::= "void" | "int" | "unsigned int"
                      | "long" | "unsigned long"
                      | "float" | "double"
                      | "string" | "char" | "bool" ;

GenericInstantiation::= Identifier "<" TypeList ">" ;
TypeList            ::= Type { "," Type } ;

ArrayType           ::= Type "[]" ;
TupleType           ::= "(" Type "," Type { "," Type } ")" ;
ObjectType          ::= Identifier ;
```

---

## 3. Objects & Members (OBP)

```ebnf
ObjectDecl          ::= "object" Identifier "{" { MemberDecl } "}" ;

MemberDecl          ::= [ DecoratorList ] [ VisibilityModifier ] ( FieldDecl | MethodDecl | ConstructDecl ) ;

VisibilityModifier  ::= "public" | "private" ;

FieldDecl           ::= Type Identifier [ "=" Expression ] ";" ;
MethodDecl          ::= Type Identifier "(" [ ParameterList ] ")" BlockStmt ;

/* A constructor initializes an object instance. At most one per object;
   without a visibility modifier it inherits the object's visibility. */
ConstructDecl       ::= "construct" "(" [ ParameterList ] ")" BlockStmt ;

ParameterList       ::= Parameter { "," Parameter } ;
Parameter           ::= Type Identifier ;
```

---

## 4. Functions & Declarations

```ebnf
FunctionDecl        ::= Type Identifier "(" [ ParameterList ] ")" BlockStmt ;

VarDeclStmt         ::= Type Identifier [ "=" Expression ] ";" ;
```

---

## 5. Control Structures & Statements

```ebnf
Statement           ::= BlockStmt
                      | VarDeclStmt
                      | ExpressionStmt
                      | IfStmt
                      | SwitchStmt
                      | ForStmt
                      | WhileStmt
                      | DoWhileStmt
                      | ReturnStmt
                      | UnsafeStmt
                      | BreakStmt
                      | ContinueStmt ;

BlockStmt           ::= "{" { Statement } "}" ;

IfStmt              ::= "if" Expression ( ":" Statement | BlockStmt )
                        [ "else" ( IfStmt | ":" Statement | BlockStmt ) ] ;

SwitchStmt          ::= "switch" Expression "{" { SwitchCase } [ DefaultCase ] "}" ;
SwitchCase          ::= "case" Expression [ "->" ] ( Statement | BlockStmt ) ;
DefaultCase         ::= "default" [ "->" ] ( Statement | BlockStmt ) ;

ForStmt             ::= "for" [ ForInit ] ":" [ Expression ] ":" [ Expression ] ( ":" Statement | BlockStmt ) ;
ForInit             ::= Type Identifier [ "=" Expression ] | Expression ;

WhileStmt           ::= "while" Expression ( ":" Statement | BlockStmt ) ;

DoWhileStmt         ::= "do" ( ":" Statement | BlockStmt ) "while" Expression ";" ;

ReturnStmt          ::= "return" [ Expression ] ";" ;

UnsafeStmt          ::= "unsafe" BlockStmt ;

BreakStmt           ::= "break" ";" ;

ContinueStmt        ::= "continue" ";" ;

ExpressionStmt      ::= Expression ";" ;
```

---

## 6. Expressions & Operators

```ebnf
Expression          ::= AssignmentExpr ;

AssignmentExpr      ::= LogicalOrExpr [ "=" AssignmentExpr ] ;

LogicalOrExpr       ::= LogicalAndExpr { "||" LogicalAndExpr } ;
LogicalAndExpr      ::= IsCheckExpr { "&&" IsCheckExpr } ;

/* Type narrowing and type testing operator */
IsCheckExpr         ::= EqualityExpr [ "is" Type ] ;

EqualityExpr        ::= RelationalExpr { ( "==" | "!=" ) RelationalExpr } ;
RelationalExpr      ::= AdditiveExpr { ( "<" | "<=" | ">" | ">=" ) AdditiveExpr } ;
AdditiveExpr        ::= MultiplicativeExpr { ( "+" | "-" ) MultiplicativeExpr } ;
MultiplicativeExpr  ::= UnaryExpr { ( "*" | "/" | "%" ) UnaryExpr } ;

UnaryExpr           ::= ( "!" | "-" | "++" | "--" ) UnaryExpr
                      | PrimaryExpr ;

PrimaryExpr         ::= BaseExpr { PostfixSuffix } ;

BaseExpr            ::= Literal
                      | Identifier
                      | TupleLiteral
                      | ArrayLiteral
                      | ThisExpr
                      | "(" Expression ")" ;

ThisExpr            ::= "this" ;

PostfixSuffix       ::= MemberAccess | CallExpr | PostfixOp ;
MemberAccess        ::= "." Identifier ;
CallExpr            ::= "(" [ ArgumentList ] ")" ;
PostfixOp           ::= "++" | "--" ;

ArgumentList        ::= Expression { "," Expression } ;

TupleLiteral        ::= "(" Expression "," Expression { "," Expression } ")" ;

ArrayLiteral        ::= "{" [ Expression { "," Expression } ] "}" ;
```

---

## 7. Lexical Tokens & Literals

```ebnf
Identifier          ::= ( Letter | "_" ) { Letter | Digit | "_" } ;
Literal             ::= IntLiteral | FloatLiteral | StringLiteral | CharLiteral | BoolLiteral ;

BoolLiteral         ::= "true" | "false" ;
IntLiteral          ::= Digit { Digit } ;
FloatLiteral        ::= Digit { Digit } "." Digit { Digit } ;
StringLiteral       ::= '"' { Char } '"' ;
CharLiteral         ::= "'" Char "'" ;

Letter              ::= "a".."z" | "A".."Z" ;
Digit               ::= "0".."9" ;

Comment ::= "#" { ^( "\n" | "\r" ) } ;
```

---

## 8. Semantic Rules (Static Analysis)

Implemented in milestone 1 of the semantic analyzer (scope resolution).
Semantic errors are reported as `alpaca: compiler error: <message>`; they do
not carry line/column information yet (the AST does not store source positions).

> See **SEMANTICS.md** for the full semantic reference (symbols, scopes,
> resolution order, `this`, write/read ambiguity, intentional gaps).

Milestone 2 (type checking) is implemented by `TypeChecker`, a separate pass
after the scope-resolution analyzer; it types all expressions, validates
assignment/return/conditions/calls/indexing/member access, and reports errors
as `alpaca: compiler error: ...`.

### Names & Scopes

- Top-level declarations (objects, functions, global variables) share a single
  namespace: no two top-level names may be equal.
- Object members (fields and methods) share a namespace per object: no two
  members of the same object may be equal.
- Function parameters must be unique; local variables must be unique within
  their block scope. Shadowing an outer name in an inner block is allowed.
- `@generic` type parameters must be unique within the declaration.
- Top-level statements are not allowed (parser enforces this).

### Resolution

- A variable reference (identifier in an expression) must resolve to a
  parameter, a local variable, or a global variable. Function and object names
  cannot be used as values.
- Inside an object (field initializers and method bodies), an unqualified name
  that is not a parameter, local variable, or global resolves to a field of
  that object (implicit field access).
- `this` refers to the enclosing object and is only valid inside an object, and
  only as the receiver of a member access (`this.field`). `this.member` always
  resolves to a member of the current object; an unknown member is an error.
  Using `this` as a value is an error.
- A bare name that resolves to a variable while a field of the same name exists
  is only ambiguous on writes (`name = ...`, `name++`, `name--`): the compiler
  rejects it and requires `this.name` for the field. Reads prefer the variable.
- A call with a plain identifier callee must resolve to a function, or to an
  object with a constructor (a construction call, see below). A call with a
  member callee must resolve to a method of the receiver's object type.
- Construction: calling an object name (`Point(1, 2)`) creates an instance.
  The object must declare a constructor; an object without one cannot be
  constructed. At most one constructor per object. The constructor's
  visibility is either explicit (`public construct`, `private construct`) or
  inherited from the object; a private constructor is only accessible from
  inside the declaring object. Arity and argument types are checked by the
  type checker; the call's type is the object type (for generic objects the
  call takes the expected type of the surrounding context when the base name
  matches, e.g. `Array<int> arr = Array("Hi");`).
- Member access (`obj.member`) resolves against the declared type of the
  receiver when that type is a known object. Receivers whose type is not
  statically known (e.g. call results) are checked by the type checker.

### Visibility

- `private` members (the default, see `VisibilityModifier`) may only be
  accessed from inside the declaring object, i.e. from field initializers and
  method bodies of that object.

### Constants

- `@const` names (global variables and object fields) may not be assigned to,
  incremented, or decremented. Initialization at the declaration site is the
  only allowed write.