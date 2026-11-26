grammar Music;

// ---------- Parser rules ----------

// A file starts with: compose <Name>   (Name becomes the output class)
program   : 'compose' ID topLevelDecl* mainBlock EOF ;

topLevelDecl : funcDecl | songDecl;
funcDecl  : 'func' retType ID '(' paramList? ')' block ;
retType   : type | 'void' ;
type      : 'int' 
          | 'bool'
          | 'Pitch'
          | 'Duration'
          | 'Note'
          | 'Chord'
          | 'Measure'
          | 'Track'
          | 'Song';

songDecl : 'song' ID '{' songMember* '}';
// Song members and track events are built-in calls: tempo(120); play(60, 1); chord(60, 0, 2); rest(1);
songMember : callStmt | trackDecl ;
trackDecl : 'track' ID '{' callStmt* '}';

// Named constants such as Pitch.C4, Chord.Major, Duration.Quarter
enumLit : ('Pitch' | 'Chord' | 'Duration') '.' ID ;

paramList : param (',' param)* ;
param     : type ID ;

mainBlock : 'main' block ;
block     : '{' stmt* '}' ;

stmt      : varDecl
          | assignStmt
          | ifStmt
          | whileStmt
          | forStmt
          | returnStmt
          | callStmt
          ;

varDecl    : type ID ('=' expr)? ';' ;
assignStmt : ID '=' expr ';' ;
ifStmt     : 'if' '(' expr ')' block ('else' (ifStmt | block))? ;
whileStmt  : 'while' '(' expr ')' block ;
forStmt    : 'for' '(' forInit expr ';' ID '=' expr ')' block ;
forInit    : varDecl | assignStmt ;
returnStmt : 'return' expr? ';' ;
callStmt   : call ';' ;

// play, tempo, rest, ... are built-in functions registered in the symbol table
call      : ID '(' argList? ')' ;
argList   : expr (',' expr)* ;

// Alternatives listed first bind tighter; binary operators are left-associative.
expr
  : '(' expr ')'                             # Parens
  | op=('-' | '!') expr                      # Unary
  | expr op=('*' | '/' | '%') expr           # MulDiv
  | expr op=('+' | '-') expr                 # AddSub
  | expr op=('<' | '<=' | '>' | '>=') expr   # Relational
  | expr op=('==' | '!=') expr               # Equality
  | expr '&&' expr                           # And
  | expr '||' expr                           # Or
  | call                                     # CallExpr
  | enumLit                                  # EnumExpr
  | ID                                       # Var
  | INT                                      # IntLit
  | ('true' | 'false')                       # BoolLit
  ;

// ---------- Lexer rules ----------
// Keyword literals above are defined first, so they win over ID.

INT           : [0-9]+ ;
ID            : [a-zA-Z_] [a-zA-Z_0-9]* ;
WS            : [ \t\r\n]+ -> skip ;
LINE_COMMENT  : '//' ~[\r\n]* -> skip ;
BLOCK_COMMENT : '/*' .*? '*/' -> skip ;

// Must stay last: unknown characters become a token the parser can report.
ERROR_CHAR    : . ;