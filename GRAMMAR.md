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

MemberDecl          ::= [ DecoratorList ] [ VisibilityModifier ] ( FieldDecl | MethodDecl ) ;

VisibilityModifier  ::= "public" | "private" ;

FieldDecl           ::= Type Identifier [ "=" Expression ] ";" ;
MethodDecl          ::= Type Identifier "(" [ ParameterList ] ")" BlockStmt ;

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
                      | "(" Expression ")" ;

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