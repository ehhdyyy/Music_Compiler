# Music_Compiler

CS153 project: a compiler for `.mus` music programs. The compiler parses the
source with ANTLR, generates Jasmin assembly (`.j`), assembles it into a JVM
`.class` file with Jasmin, and plays the notes through Java MIDI.

```
Scale.mus --ANTLR--> parse tree --CodeGenerator/Emitter--> Scale.j --Jasmin--> Scale.class --java--> music
```

## Requirements

- Java JDK 17 or newer (`java` and `javac` on your PATH)
- Windows PowerShell for `build.ps1`, or macOS Terminal/zsh (commands below)
- `lib/antlr-4.13.2-complete.jar` and `lib/jasmin.jar` 

## Quick start

From the project root in PowerShell:

```powershell
.\build.ps1                 # compiles and plays Scale.mus
.\build.ps1 MySong.mus      # compiles and plays any other .mus file
```

To also print the symbol table (functions, and every scope's variables with
kind, type, JVM slot, and declaration line):

```powershell
.\build.ps1 Scale.mus -Symtab
```

If PowerShell blocks the script ("running scripts is disabled"), run it like this:

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1 Scale.mus
```

## What build.ps1 does

| Step | Command | Output |
|------|---------|--------|
| 1. Generate the parser | `java -jar lib\antlr-4.13.2-complete.jar -visitor -no-listener -package frontend -Xexact-output-dir -o src\frontend Music.g4` | `src/frontend/Music*.java` |
| 2. Compile the compiler and runtime | `javac -cp lib\antlr-4.13.2-complete.jar -d build\classes <all src .java files>` | `build/classes/` |
| 3. Compile the `.mus` file to Jasmin | `java -cp "lib\antlr-4.13.2-complete.jar;build\classes" backend.compiler.Compiler Scale.mus build` | `build/Scale.j` |
| 4. Assemble with Jasmin | `java -jar lib\jasmin.jar -d build build\Scale.j` | `build/Scale.class` |
| 5. Run the program | `java -cp "build;build\classes" Scale` | plays the music |

The class name comes from the first line of the source file: `compose Scale`
produces `Scale.j` and `Scale.class`.

After a build, you can play the program again without rebuilding:

```powershell
java -cp "build;build\classes" Scale
```

`build\classes` must be on the classpath, because the generated code calls
`runtime.MusicRuntime`. On Windows the classpath separator is `;`. Keep the
quotes.

### macOS (Terminal/zsh)

From the project root, run these commands to compile and play `Scale.mus`:

| Step | Command | Output |
|------|---------|--------|
| 1. Generate the parser | `java -jar lib/antlr-4.13.2-complete.jar -visitor -no-listener -package frontend -Xexact-output-dir -o src/frontend Music.g4` | `src/frontend/Music*.java` |
| 2. Create the classes directory | `mkdir -p build/classes` | `build/classes/` |
| 3. Compile the compiler and runtime | `javac -cp lib/antlr-4.13.2-complete.jar -d build/classes $(find src -name '*.java')` | `build/classes/` |
| 4. Compile the `.mus` file to Jasmin | `java -cp "lib/antlr-4.13.2-complete.jar:build/classes" backend.compiler.Compiler Scale.mus build` | `build/Scale.j` |
| 5. Assemble with Jasmin | `java -jar lib/jasmin.jar -d build build/Scale.j` | `build/Scale.class` |
| 6. Run the program | `java -cp "build:build/classes" Scale` | plays the music |


## Testing only the grammar

To check that a `.mus` file parses, without generating code:

```powershell
java -jar lib\antlr-4.13.2-complete.jar -o gen Music.g4
javac -cp lib\antlr-4.13.2-complete.jar -d gen gen\*.java
java -cp "lib\antlr-4.13.2-complete.jar;gen" org.antlr.v4.gui.TestRig Music program -tree Scale.mus
```

On macOS, use these equivalent commands:

```sh
mkdir -p gen
java -jar lib/antlr-4.13.2-complete.jar -o gen Music.g4
javac -cp lib/antlr-4.13.2-complete.jar -d gen $(find gen -name '*.java')
java -cp "lib/antlr-4.13.2-complete.jar:gen" org.antlr.v4.gui.TestRig Music program -tree Scale.mus
```

Replace `-tree` with `-gui` to see the tree in a window, or with `-tokens` to
see the token stream. If no `line X:Y ...` messages appear, the file is valid.

## The language

```
compose Scale

func int nextPitch(int p, int step) {
    return p + step;
}

main {
    tempo(120);
    int pitch = 60;
    while (pitch <= 72) {
        play(pitch, 1);
        pitch = nextPitch(pitch, 2);
    }
}
```

- Types: `int`, `bool`, and `void` (return type only). `Pitch`, `Duration` and
  `Chord` are accepted as `int`-compatible type names (see Named constants).
  `Note`, `Measure`, `Track` and `Song` parse but are not implemented.
- Statements: variable declaration, assignment, `if` / `else if` / `else`, `while`, `for`, `return`, function call
- Operators: `+ - * / %`, `< <= > >= == !=`, `&& || !`, unary `-`
- Comments: `// line` and `/* block */`

### Built-in functions

| Function | Meaning |
|----------|---------|
| `play(pitch, beats)` | Play a MIDI note (60 = middle C) for a number of beats |
| `chord(root, quality, beats)` | Play every note of a chord together (see the quality table below) |
| `rest(beats)` | Silence for a number of beats |
| `tempo(bpm)` | Set the tempo in beats per minute (default 120) |
| `instrument(n)` | Set the General MIDI instrument, 0 to 127 (0 = piano) |
| `volume(v)` | Set the note velocity, 0 to 127 (default 90) |
| `print(value)` | Print an integer |

Each built-in also prints a line such as `play C4 (60) for 1 beat(s)  t=0ms`,
so you can check the output when no sound device is available. The `t=` value
is the milliseconds since the first note, and it is a debug aid.

Chord qualities (the second argument of `chord`). An unknown code plays a
major chord. Notes are clamped to 0 to 127. `Chords.mus` is a sample program.

| Code | Quality | Semitones above the root |
|------|---------|--------------------------|
| 0 | major | 0 4 7 |
| 1 | minor | 0 3 7 |
| 2 | diminished | 0 3 6 |
| 3 | augmented | 0 4 8 |
| 4 | dominant 7th | 0 4 7 10 |
| 5 | major 7th | 0 4 7 11 |
| 6 | minor 7th | 0 3 7 10 |

### Named constants

`Pitch.<name>`, `Chord.<quality>` and `Duration.<name>` compile to int
constants, so they work anywhere an `int` is expected:
`play(Pitch.C4, Duration.Quarter);`

- `Pitch`: a letter A to G, an optional `s` (sharp) or `b` (flat), and an
  octave digit: `C4` = 60, `Fs3`, `Bb2`.
- `Chord`: `Major Minor Dim Aug Dom7 Maj7 Min7` (codes 0 to 6).
- `Duration`: `Whole` (4 beats), `Half` (2), `Quarter` (1).

`song` and `track` blocks parse, but code generation reports
"song declarations are not supported yet".

### Type checking

There are two kinds of value: `bool` and `int`. `Pitch`, `Duration`, `Chord`
and the other music types count as `int`. The compiler reports an error for:

- an initializer, assignment, `return` value or call argument of the wrong kind
- an `if`, `while`, or `for` condition that is not `bool`
- arithmetic (`+ - * / %`) or comparison (`< <= > >=`) on a `bool`
- `!`, `&&` or `||` on an `int`
- `==` or `!=` between an `int` and a `bool`

`Bad.mus` shows each error. `T.mus` exercises the named constants and `!`.

### Errors

- Syntax errors are reported by ANTLR with a line and column.
- Semantic errors stop code generation. These include: an undeclared
  variable, an undefined function, the wrong number of arguments, a variable
  declared twice in the same block, a `return` that does not match the
  function type, a void function used as a value, a type mismatch, an unknown
  `Pitch`/`Chord`/`Duration` constant, an integer literal that is too large,
  and a `song` declaration (not supported yet).

### How the runtime fits in

`MusicRuntime` is a small runtime library, like `libc` for C. The compiler
does not use `javax.sound.midi`. `CodeGenerator` emits a static call such as
`runtime.MusicRuntime.play(60, 1)`, and the runtime sends the MIDI messages to
Java's built-in synthesizer and waits for the length of the note. The
`.class` file is not self-contained: `build\classes` must be on the classpath
when it runs. Playback is live, so no `.mid` file is written.

On the first note, `init()` opens the synthesizer and waits until its audio
clock has advanced (the `start + ...` value in the `while` loop, in
microseconds, with a 5 second limit), so the first notes are not lost. Notes
end on an absolute clock, so timing does not drift over a long song. After you
edit `MusicRuntime.java`, rebuild with `.\build.ps1` so `build\classes` is
updated.

## Project layout

```
Music.g4                              ANTLR grammar
Scale.mus, Chords.mus, Jingle.mus, T.mus   Sample programs
Bad.mus                               Programs with errors (should not compile)
build.ps1                             Build-and-run script
lib/                                  antlr and jasmin jars
src/frontend/                         ANTLR-generated parser (created by build.ps1, not committed)
src/intermediate/symtab/              Symbol table (Symtab, SymtabEntry)
src/backend/compiler/Compiler.java    Main program: .mus -> .j
src/backend/compiler/CodeGenerator.java  Parse-tree visitor that generates code
src/backend/compiler/Emitter.java     Writes the Jasmin text
src/runtime/MusicRuntime.java         play/rest/tempo/... implemented with javax.sound.midi
build/                                Generated .j and .class files (not committed)
```

## Adding a new built-in function

1. In the `CodeGenerator` constructor, add a line such as
   `builtin("transpose", "void", "int", "int");`
2. Add a matching `public static void transpose(int a, int b)` method to
   `src/runtime/MusicRuntime.java`.
3. Run `.\build.ps1` again.
