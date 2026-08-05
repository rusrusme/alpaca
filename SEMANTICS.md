# Alpaca Semantics

Companion to GRAMMAR.md. GRAMMAR.md defines the syntax (what parses); this
document defines the semantics (what a parsed program means and which programs
are valid). Implemented by `SemanticAnalyzer` + `SymbolTable` (milestone 1:
scope resolution) and `TypeChecker` (milestone 2: type checking).

---

## 1. Symbols and Scopes

A **symbol** is a declared name together with its attributes:

- `name` - the declared name
- `kind` - VARIABLE, FUNCTION, OBJECT, FIELD, METHOD, CONSTRUCTOR, TYPE_PARAMETER
- `type` - the declared type (null for objects)
- `visibility` - PUBLIC or PRIVATE
- `constant` - true if declared with `@const`
- `declaringObject` - owning object for FIELD/METHOD, null otherwise

A **scope** is a region where names are visible. Blocks `{ ... }`, function
bodies, and `for` loop bodies open a scope. Scopes nest: an inner scope sees
its outer scopes, not vice versa. The symbol table models this as a stack of
maps: entering a block pushes a map, leaving it pops the map, so the block's
names disappear again. Shadowing falls out naturally: a local `x` hides a
global `x`, and after the block the global is visible again.

**Three namespaces** (deliberate design):

1. Top level: ONE namespace shared by objects, functions, and global
   variables. An object `House` and a function `House` may not both exist
   (they would collide in C anyway).
2. Per-object member maps: fields and methods of each object. Two objects may
   have same-named members.
3. Local scopes: parameters, locals, block-scoped names.

---

## 2. Name Resolution

### Bare names (identifiers)

Resolution order for an unqualified name:

1. parameters and local variables (innermost scope outward)
2. global variables
3. own field of the current object (implicit field access) - only inside an
   object (field initializers and method bodies)

Outside an object the chain stops after step 2; an unresolved name is an
"undefined variable" error. Function and object names cannot be used as
values.

### Member access `obj.member`

- Resolves against the declared type of the receiver. The type must be a
  known object, the member must exist, and `private` members are only
  accessible from inside the declaring object (the default visibility is
  PRIVATE).
- Receivers whose type is not statically known (e.g. the result of a call)
  are not checked in milestone 1; the type checker handles them.

### `this`

- `this` refers to the enclosing object. It is only valid inside an object
  and only as the receiver of a member access (`this.field`,
  `this.method()`); using it as a value is an error.
- `this.member` always resolves to a member of the current object; an unknown
  member is an error. Because the access is by definition inside the object,
  `private` members are always accessible via `this` (no visibility check).
- `this` is optional: bare names fall back to own fields implicitly (see
  above). Use `this.field` to disambiguate explicitly.

### Construction calls `Point(...)`

- Calling an object name constructs an instance: `Point(1, 2)`. The callee
  resolves to the object itself, not to a function.
- The object **must** declare a constructor
  (`construct(params) { ... }`); an object without a constructor cannot be
  constructed. At most one constructor may exist per object (enforced by the
  parser). Constructor overloading is a later milestone.
- The constructor's visibility is either explicit (`public construct`,
  `private construct`) or inherited from the object when no modifier is
  given. A private constructor is only accessible from inside the declaring
  object (field initializers, method bodies, the constructor body itself).
- The constructor body is a scope like a method body: parameters are locals,
  `this` and implicit field access work. It behaves like a `void` function:
  `return;` is allowed, returning a value is an error.
- Arity and argument types are validated by the type checker against the
  constructor's parameters. The call's type is the object type; for generic
  objects the type checker applies **expected-type inference**: when the call
  is assigned to, returned as, or passed as a generic type with the same base
  name, the call takes that type (`Array<int> arr = Array("Hi");` types the
  call as `Array<int>`). Without a matching expected type the call types as
  the raw object name (`Array`). Argument-driven inference (`E` deduced from
  the arguments) is a later milestone together with the monomorphizer.
- Internally the constructor occupies the member name `construct`, so a
  field or method named `construct` collides with it (duplicate member
  error) - but `construct` is not a reserved word: `construct x;` is a field
  of a type named `construct`, and `void construct()` a regular method.

---

## 3. Write vs. Read Ambiguity

A bare name that resolves to a variable while a field of the same name exists
(parameter `age` + field `age`) is treated differently for reads and writes:

- **Write** (`age = ...`, `age++`, `age--`): rejected with
  `ambiguous reference 'age': variable and field of object 'Person'; use
  'this.age' for the field`.
- **Read** (`return age;`): the variable wins silently.

Rationale: `this.age = age;` must be valid - the right-hand `age` also
collides with the field, but clearly means the parameter. Only writes demand
explicit disambiguation.

---

## 4. Static Checks (implemented)

The analyzer rejects:

- duplicate top-level names, duplicate object members, duplicate parameters,
  duplicate local variables within one scope, duplicate `@generic` type
  parameters
- undefined variables and undefined functions
- functions, objects, and type parameters used as values
- access to `private` members from outside the declaring object
- construction calls on objects without a constructor, and access to
  `private` constructors from outside the declaring object
- writes to `@const` names (assignment, increment, decrement)
- misuse of `this` (outside an object, or as a value)
- invalid assignment targets (calls, literals, ...)
- top-level statements (also rejected by the parser)

### Type checks (milestone 2, `TypeChecker`)

- assignment compatibility: exact type match or numeric widening along the
  rank chain `int < unsigned int < long < unsigned long < float < double`;
  narrowing is rejected (`double -> float`, `long -> int`, `unsigned -> int`)
- **type parameters** (declared via `@generic`) are unconstrained in this
  milestone: a value of type `E` is assignable to any type, and any value is
  assignable to `E` - including nested occurrences (`Optional<E>` is
  assignable to `Optional<int>`). Concrete mismatches (`Array<int>` vs
  `Array<string>`) remain errors. The monomorphizer performs the concrete
  checks
- unsigned types (`unsigned int`, `unsigned long`) reject statically negative
  constant values: `unsigned int x = -1;` is an error; non-constant values
  are a runtime concern, as in C
- arithmetic result types follow the same rank chain (`unsigned int + int`
  is `unsigned int`, `int + double` is `double`)
- return statements against the declared return type (including void rules)
- call arity and argument types (global functions and methods)
- conditions of `if`/`while`/`do-while`/`for` must be `bool`
- array indexing: receiver must be an array, index must be `int`
- member access on statically unknown receivers (e.g. call results),
  including the private-visibility rule; generic types resolve via their
  base object (`Array<E>` -> members of `Array`)
- operator operands: arithmetic and ordering on numeric values,
  `+` also concatenates `string`, logical operators on `bool`
- methods cannot be used as values

### Constants

`@const` is allowed on global variables and object fields. Initialization at
the declaration site is the only permitted write. Error messages distinguish
fields from variables: `cannot assign to constant field 'x'` vs.
`cannot assign to constant 'x'`.

---

## 5. Intentional Gaps (later milestones)

- **`is`-type narrowing** in if branches (the `is` expression itself types to
  `bool` already).
- **Loop-context checks** for `break`/`continue`.
- **`@pure`** side-effect analysis.
- **`@main`** validation (exactly one entry point).
- **Overloading** of functions, methods and constructors: today a second
  declaration with the same name (even with different parameters) is a
  duplicate-declaration error. A single overloading pass is planned; until
  then at most one constructor per object.
- **Line/column info** in semantic error messages: the AST stores no source
  positions today.
- **Top-level `private` across module boundaries**: `ModuleLoader` returns
  plain declarations without module provenance, so private top-level names
  cannot be checked across imports yet.

---

## 6. Implementation Notes

- **Two passes**: `collect` registers all names (and rejects duplicates);
  `resolve` walks statements and expressions and performs the checks. The two
  passes make forward references work (`void a() { b(); }` before `void b()`).
- **Context**: `currentObject` tracks the object whose body is being resolved
  (null at top level); it drives implicit field access, `this` handling, and
  private-visibility checks.
- **`resolveExpression` convention**: returns the `Symbol` the expression
  refers to, or `null` when the expression is not statically resolvable to a
  single declaration (literals, call results, computed values). `null` is not
  an error - it means "left for the type checker".
- **TypeChecker contract**: consumes the M1 `resolvedSymbols` map
  (expression -> Symbol, populated during pass 2). Expressions M1 could not
  resolve statically (e.g. member access on a call result) are typed and
  validated by the TypeChecker itself, including the visibility rule. Arity
  and parameter types come from declaration maps the TypeChecker collects
  from the program.
- **Duplicate detection**: `Map.putIfAbsent` returns the previous value, so
  `defineGlobal`/`defineLocal` report whether the name was already taken; the
  analyzer turns that into the error message.
