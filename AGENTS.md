# AGENTS.md — Alpaca

## Projektüberblick

**Alpaca** ist eine eigene, kleine Programmiersprache. Sie wird
**transpiliert zu C-Code**, der anschließend mit `gcc` kompiliert und ausgeführt wird.
Der Compiler **`alpacac`** ist vollständig in **Java** geschrieben (Gradle-Projekt).

Design-Grundsätze:

- **Keine OOP.** Die Sprache unterstützt keine Objektorientierung.
- **Objektbasiert** einfache Klassen **ohne** inheritance und **ohne**
  polymorphism — reine Daten-/Verhaltensbündelung, keine Vererbungshierarchien.

## Architektur & Compiler-Pipeline

Einstiegspunkt: `net.rusrusme.alpaca.runtime.core.Execute` (Main-Class).

Quelltext-Pakete:

- `net.rusrusme.alpaca` — Runtime, Entry-Point, mitgelieferte Stdlib (`lib/`)
- `net.rusrusme.alpacac` — der Compiler

Fehlerbehandlung: Compiler-Fehler → `AlpacaCompilerError`, Laufzeitfehler →
`AlpacaRuntimeException`; beides fängt `Execution` und meldet `alpaca: <art> error: <msg>`.

## Sprachfeatures

noch leer

## Standard-Library (in alpaca selbst geschrieben)

Liegt als `.alpaca`-Quellcode unter `src/main/java/net/rusrusme/alpaca/runtime/lib/`:

## Build & Run

```bash
./gradlew build # baut build/libs/alpaca.jar (fat jar, Main-Class gesetzt)
java -jar build/libs/alpaca.jar example.alpaca
```

## Konventionen

- **Commits:** Conventional Commits, Bereich `compiler`, `gradle`, `utility`, `git`, z.B.
  `feat(compiler): add array support` — ein Feature pro Commit, nur Englisch erlaubt.
- **Sprache im Code:** Java-Code, Kommentare und Commit-Messages nur auf Englisch;
  Alpaca-Code mit englischen Bezeichnern.
- Bei Änderungen an Grammatik/Sprache: **GRAMMAR.md immer synchron halten.**
