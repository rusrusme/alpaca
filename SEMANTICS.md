# Alpaca Semantics

Companion to GRAMMAR.md. GRAMMAR.md defines the syntax (what parses); this
document defines the semantics (what a parsed program means and which programs
are valid). Implemented by `SemanticAnalyzer` + `SymbolTable` (milestone 1:
scope resolution). Type checking is milestone 2 and intentionally not covered
here yet.

---

## 1. Symbols and Scopes

A **symbol** is a declared name together with its attributes:

- `name` - the declared name
- `kind` - VARIABLE, FUNCTION, OBJECT, FIELD, METHOD, TYPE_PARAMETER
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
- writes to `@const` names (assignment, increment, decrement)
- misuse of `this` (outside an object, or as a value)
- invalid assignment targets (calls, literals, ...)
- top-level statements (also rejected by the parser)

### Constants

`@const` is allowed on global variables and object fields. Initialization at
the declaration site is the only permitted write. Error messages distinguish
fields from variables: `cannot assign to constant field 'x'` vs.
`cannot assign to constant 'x'`.

---

## 5. Intentional Gaps (later milestones)

- **Type checking** (milestone 2): expression types, call argument counts and
  compatibility, `is` type narrowing, assignment compatibility, tuple/array
  element types.
- **Loop-context checks** for `break`/`continue`.
- **`@pure`** side-effect analysis.
- **`@main`** validation (exactly one entry point).
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
- **Duplicate detection**: `Map.putIfAbsent` returns the previous value, so
  `defineGlobal`/`defineLocal` report whether the name was already taken; the
  analyzer turns that into the error message.
