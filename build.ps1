# Usage: .\build.ps1 [file.mus]      (default: Scale.mus)
# 1. ANTLR  -> src/frontend         2. javac  -> build/classes
# 3. Compiler -> build/<Name>.j     4. Jasmin -> build/<Name>.class     5. run it
#        .\build.ps1 Scale.mus -Symtab   (also print the symbol table)
param([string]$Source = "Scale.mus", [switch]$Symtab)
$ErrorActionPreference = "Stop"
$antlr  = "lib\antlr-4.13.2-complete.jar"
$jasmin = "lib\jasmin.jar"
$cls    = "build\classes"

java -jar $antlr -visitor -no-listener -package frontend -Xexact-output-dir -o src\frontend Music.g4
if ($LASTEXITCODE) { exit 1 }

New-Item -ItemType Directory -Force $cls | Out-Null
$files = Get-ChildItem -Recurse src -Filter *.java | ForEach-Object { $_.FullName }
javac -cp $antlr -d $cls $files
if ($LASTEXITCODE) { exit 1 }

$flags = @(); if ($Symtab) { $flags += "-symtab" }
java -cp "$antlr;$cls" backend.compiler.Compiler @flags $Source build
if ($LASTEXITCODE) { exit 1 }

$name = (Select-String -Path $Source -Pattern '^\s*compose\s+(\w+)').Matches[0].Groups[1].Value
java -jar $jasmin -d build "build\$name.j"
if ($LASTEXITCODE) { exit 1 }

java -cp "build;$cls" $name
