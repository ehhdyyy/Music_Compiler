package backend.compiler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.antlr.v4.runtime.ParserRuleContext;

import frontend.MusicBaseVisitor;
import frontend.MusicParser.*;
import intermediate.symtab.Symtab;
import intermediate.symtab.SymtabEntry;
import intermediate.symtab.SymtabEntry.Kind;

/**
 * Walks the parse tree and emits Jasmin code.
 * Every value is a JVM int; bool is 0/1.
 * Each local variable's JVM slot number is stored as its symtab entry value.
 */
public class CodeGenerator extends MusicBaseVisitor<Void>
{
    static final String RUNTIME = "runtime/MusicRuntime";

    /** Signature of a user function or built-in. */
    static class FuncSig
    {
        final String name; final String owner; final List<String> params; final String ret;
        FuncSig(String name, String owner, List<String> params, String ret)
        { this.name = name; this.owner = owner; this.params = params; this.ret = ret; }

        String descriptor()
        {
            return "(" + "I".repeat(params.size()) + ")" + (ret.equals("void") ? "V" : "I");
        }
    }

    private final Emitter emitter = new Emitter();
    private final Map<String, FuncSig> functions = new LinkedHashMap<>();
    private final List<String> errors = new ArrayList<>();
    private final Map<String, FuncDeclContext> funcDecls = new java.util.HashMap<>();

    private String className;
    private Deque<Symtab> scopes;       // innermost scope first
    private int nextSlot;
    private int maxSlot;
    private FuncSig currentFunc;

    /** Every scope created, in order, with a label such as "main block (line 22)". */
    private final List<Map.Entry<String, Symtab>> allScopes = new ArrayList<>();

    private Symtab newScope(String label)
    {
        Symtab s = new Symtab();
        allScopes.add(Map.entry(label, s));
        return s;
    }

    /** Text listing of the functions and of every local scope. */
    public String symtabReport()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("===== FUNCTIONS =====\n");
        sb.append(String.format("%-12s %-8s %-22s %s%n", "Name", "Return", "Params", "Owner"));
        for (FuncSig f : functions.values())
            sb.append(String.format("%-12s %-8s %-22s %s%n",
                      f.name, f.ret, "(" + String.join(", ", f.params) + ")",
                      f.owner.equals(RUNTIME) ? "built-in" : f.owner));

        for (Map.Entry<String, Symtab> scope : allScopes) {
            if (scope.getValue().isEmpty()) continue;   // skip scopes that declare nothing
            sb.append("\n===== ").append(scope.getKey()).append(" =====\n");
            sb.append(String.format("%-12s %-16s %-6s %-5s %s%n", "Name", "Kind", "Type", "Slot", "Lines"));
            for (SymtabEntry e : scope.getValue().values())
                sb.append(String.format("%-12s %-16s %-6s %-5d %s%n",
                          e.getName(), e.getKind(), types.getOrDefault(e, "?"),
                          slot(e), e.getLineNumbers()));
        }
        return sb.toString();
    }

    /** Declared type of each entry (SymtabEntry has no type field). */
    private final Map<SymtabEntry, String> types = new java.util.HashMap<>();

    public CodeGenerator()
    {
        builtin("play",       "void", "int", "int");  // play(pitch, beats)
        builtin("chord",      "void", "int", "int", "int"); // chord(root, quality, beats)
        builtin("rest",       "void", "int");         // rest(beats)
        builtin("tempo",      "void", "int");         // tempo(bpm)
        builtin("instrument", "void", "int");         // instrument(program 0-127)
        builtin("volume",     "void", "int");         // volume(velocity 0-127)
        builtin("print",      "void", "int");         // print(value)
    }

    private void builtin(String name, String ret, String... params)
    {
        functions.put(name, new FuncSig(name, RUNTIME, List.of(params), ret));
    }

    public List<String> getErrors() { return errors; }
    public Emitter getEmitter()     { return emitter; }
    public String getClassName()    { return className; }

    private void error(ParserRuleContext ctx, String msg)
    {
        errors.add("line " + ctx.getStart().getLine() + ": " + msg);
    }

    // ---------------- program ----------------

    @Override
    public Void visitProgram(ProgramContext ctx)
    {
        className = ctx.ID().getText();

        // Pass 1: record every function signature so calls can come before declarations.
        for (TopLevelDeclContext t : ctx.topLevelDecl()) {
            FuncDeclContext f = t.funcDecl();
            if (f != null)
            {
                String name = f.ID().getText();
                if (functions.containsKey(name)) { error(f, "function '" + name + "' already defined"); continue; }
                funcDecls.put(name, f);
                List<String> params = new ArrayList<>();
                if (f.paramList() != null)
                    for (ParamContext p : f.paramList().param()) params.add(p.type().getText());
                functions.put(name, new FuncSig(name, className, params, f.retType().getText()));
            }
        }

        emitter.directive(".class public " + className);
        emitter.directive(".super java/lang/Object");
        emitter.beginMethod("public <init>()V");
        emitter.emit("aload_0");
        emitter.emit("invokenonvirtual java/lang/Object/<init>()V");
        emitter.emit("return");
        emitter.endMethod(1, 1);

        for (TopLevelDeclContext decl : ctx.topLevelDecl()) {
            FuncDeclContext function = decl.funcDecl();
            if (function != null) visit(function);
            else error(decl, "song declarations are not supported yet");
        }
        visit(ctx.mainBlock());
        return null;
    }

    @Override
    public Void visitFuncDecl(FuncDeclContext ctx)
    {
        FuncSig sig = functions.get(ctx.ID().getText());
        // Skip builtins and duplicate declarations (already reported in pass 1).
        if (sig == null || !sig.owner.equals(className) || funcDecls.get(sig.name) != ctx) return null;
        currentFunc = sig;
        startScopes(0);

        emitter.beginMethod("private static " + sig.name + sig.descriptor());
        if (ctx.paramList() != null)
            for (ParamContext p : ctx.paramList().param())
                declare(p, p.ID().getText(), p.type().getText(), Kind.VALUE_PARAMETER);

        visit(ctx.block());

        // Fall-through at the end of the method.
        if (sig.ret.equals("void")) emitter.emit("return");
        else { emitter.emit("iconst_0"); emitter.emit("ireturn"); }
        emitter.endMethod(64, Math.max(maxSlot, 1));
        return null;
    }

    @Override
    public Void visitMainBlock(MainBlockContext ctx)
    {
        currentFunc = new FuncSig("main", className, List.of(), "void");
        startScopes(1);  // slot 0 holds String[] args

        emitter.beginMethod("public static main([Ljava/lang/String;)V");
        visit(ctx.block());
        emitter.emit("invokestatic " + RUNTIME + "/finish()V");
        emitter.emit("return");
        emitter.endMethod(64, Math.max(maxSlot, 1));
        return null;
    }

    // ---------------- scopes ----------------

    private void startScopes(int firstSlot)
    {
        scopes = new ArrayDeque<>();
        scopes.push(newScope(currentFunc.name + (currentFunc.name.equals("main") ? "" : " parameters")));
        nextSlot = maxSlot = firstSlot;
    }

    private SymtabEntry declare(ParserRuleContext ctx, String name, String type, Kind kind)
    {
        SymtabEntry e = declare(ctx, name, kind);
        if (e != null) types.put(e, type);
        return e;
    }

    private SymtabEntry declare(ParserRuleContext ctx, String name, Kind kind)
    {
        Symtab scope = scopes.peek();
        if (scope.lookup(name) != null) {
            error(ctx, "'" + name + "' already declared in this scope");
            return null;
        }
        SymtabEntry e = scope.enter(name, kind);
        e.setValue((double) nextSlot++);
        e.appendLineNumber(ctx.getStart().getLine());
        maxSlot = Math.max(maxSlot, nextSlot);
        return e;
    }

    private SymtabEntry lookupVar(ParserRuleContext ctx, String name)
    {
        for (Symtab s : scopes) { SymtabEntry e = s.lookup(name); if (e != null) return e; }
        error(ctx, "undeclared variable '" + name + "'");
        return null;
    }

    private static int slot(SymtabEntry e) { return e.getValue().intValue(); }

    // ---------------- statements ----------------

    @Override
    public Void visitBlock(BlockContext ctx)
    {
        scopes.push(newScope(currentFunc.name + " block (line " + ctx.getStart().getLine()
                             + ", depth " + scopes.size() + ")"));
        int saved = nextSlot;
        for (StmtContext s : ctx.stmt()) visit(s);
        nextSlot = saved;   // slots of block locals can be reused afterwards
        scopes.pop();
        return null;
    }

    @Override
    public Void visitVarDecl(VarDeclContext ctx)
    {
        if (ctx.expr() != null) {
            expect(ctx, ctx.expr(), kind(ctx.type().getText()), "initializer of '" + ctx.ID().getText() + "'");
            visit(ctx.expr());
        } else emitter.emit("iconst_0");
        SymtabEntry e = declare(ctx, ctx.ID().getText(), ctx.type().getText(), Kind.VARIABLE);
        if (e != null) emitter.emit("istore " + slot(e)); else emitter.emit("pop");
        return null;
    }

    @Override
    public Void visitAssignStmt(AssignStmtContext ctx)
    {
        visit(ctx.expr());
        SymtabEntry e = lookupVar(ctx, ctx.ID().getText());
        if (e != null) {
            expect(ctx, ctx.expr(), kind(types.getOrDefault(e, "int")), "assignment to '" + ctx.ID().getText() + "'");
            emitter.emit("istore " + slot(e));
        } else emitter.emit("pop");
        return null;
    }

    @Override
    public Void visitIfStmt(IfStmtContext ctx)
    {
        String elseLabel = emitter.newLabel(), endLabel = emitter.newLabel();
        expect(ctx, ctx.expr(), "bool", "if condition");
        visit(ctx.expr());
        emitter.emit("ifeq " + elseLabel);
        visit(ctx.block(0));
        emitter.emit("goto " + endLabel);
        emitter.label(elseLabel);
        if (ctx.ifStmt() != null) visit(ctx.ifStmt());
        else if (ctx.block().size() > 1) visit(ctx.block(1));
        emitter.label(endLabel);
        return null;
    }

    @Override
    public Void visitWhileStmt(WhileStmtContext ctx)
    {
        String top = emitter.newLabel(), exit = emitter.newLabel();
        emitter.label(top);
        expect(ctx, ctx.expr(), "bool", "while condition");
        visit(ctx.expr());
        emitter.emit("ifeq " + exit);
        visit(ctx.block());
        emitter.emit("goto " + top);
        emitter.label(exit);
        return null;
    }

    @Override
    public Void visitForStmt(ForStmtContext ctx)
    {
        scopes.push(newScope(currentFunc.name + " for (line " + ctx.getStart().getLine() + ")"));
        int saved = nextSlot;
        visit(ctx.forInit());
        String top = emitter.newLabel(), exit = emitter.newLabel();
        emitter.label(top);
        expect(ctx, ctx.expr(0), "bool", "for condition");
        visit(ctx.expr(0));
        emitter.emit("ifeq " + exit);
        visit(ctx.block());
        visit(ctx.expr(1));
        SymtabEntry e = lookupVar(ctx, ctx.ID().getText());
        if (e != null) {
            expect(ctx, ctx.expr(1), kind(types.getOrDefault(e, "int")),
                   "assignment to '" + ctx.ID().getText() + "'");
            emitter.emit("istore " + slot(e));
        } else emitter.emit("pop");
        emitter.emit("goto " + top);
        emitter.label(exit);
        nextSlot = saved;
        scopes.pop();
        return null;
    }

    @Override
    public Void visitReturnStmt(ReturnStmtContext ctx)
    {
        boolean isVoid = currentFunc.ret.equals("void");
        // An early return in main must still close the synthesizer.
        if (currentFunc.name.equals("main")) emitter.emit("invokestatic " + RUNTIME + "/finish()V");
        if (ctx.expr() != null) {
            if (isVoid) error(ctx, "void function cannot return a value");
            else expect(ctx, ctx.expr(), kind(currentFunc.ret), "return value");
            visit(ctx.expr());
            emitter.emit(isVoid ? "return" : "ireturn");
        } else {
            if (!isVoid) error(ctx, "function '" + currentFunc.name + "' must return a value");
            emitter.emit("return");
        }
        return null;
    }

    @Override
    public Void visitCallStmt(CallStmtContext ctx)
    {
        FuncSig sig = emitCall(ctx.call());
        if (sig != null && !sig.ret.equals("void")) emitter.emit("pop");  // discard unused result
        return null;
    }

    /** Push the arguments and invoke. Returns null (and emits nothing) on error. */
    private FuncSig emitCall(CallContext ctx)
    {
        String name = ctx.ID().getText();
        List<ExprContext> args = ctx.argList() == null ? List.of() : ctx.argList().expr();
        return emitInvocation(ctx, name, args);
    }

    private FuncSig emitInvocation(ParserRuleContext ctx, String name, List<ExprContext> args)
    {
        FuncSig sig = functions.get(name);
        if (sig == null) { error(ctx, "undefined function '" + name + "'"); return null; }
        if (args.size() != sig.params.size()) {
            error(ctx, "'" + name + "' expects " + sig.params.size()
                       + " argument(s), got " + args.size());
            return null;
        }
        for (int i = 0; i < args.size(); i++)
            expect(args.get(i), args.get(i), kind(sig.params.get(i)), "argument " + (i + 1) + " of '" + name + "'");
        for (ExprContext a : args) visit(a);
        emitter.emit("invokestatic " + sig.owner + "/" + sig.name + sig.descriptor());
        return sig;
    }

    // ---------------- type checking ----------------
    // Two kinds: "bool" and "int". Pitch, Duration, Chord and the other music
    // types are int-compatible. "?" means unknown (an error was already reported).

    private static String kind(String type)
    {
        return type.equals("bool") || type.equals("void") ? type : "int";
    }

    /** Type of an expression, computed without emitting code or reporting errors. */
    private String typeOf(ExprContext e)
    {
        if (e instanceof ParensContext p) return typeOf(p.expr());
        if (e instanceof UnaryContext u) return u.op.getText().equals("!") ? "bool" : "int";
        if (e instanceof MulDivContext || e instanceof AddSubContext
            || e instanceof IntLitContext || e instanceof EnumExprContext) return "int";
        if (e instanceof RelationalContext || e instanceof EqualityContext
            || e instanceof AndContext || e instanceof OrContext || e instanceof BoolLitContext) return "bool";
        if (e instanceof CallExprContext c) {
            FuncSig sig = functions.get(c.call().ID().getText());
            return sig == null || sig.ret.equals("void") ? "?" : kind(sig.ret);
        }
        if (e instanceof VarContext v) {
            for (Symtab s : scopes) {
                SymtabEntry entry = s.lookup(v.ID().getText());
                if (entry != null) return kind(types.getOrDefault(entry, "int"));
            }
        }
        return "?";
    }

    /** Report an error unless e has the wanted type. */
    private void expect(ParserRuleContext at, ExprContext e, String want, String what)
    {
        String got = typeOf(e);
        if (!got.equals("?") && !got.equals(want))
            error(at, "type mismatch in " + what + ": expected " + want + ", got " + got);
    }

    // ---------------- expressions ----------------

    @Override
    public Void visitParens(ParensContext ctx) { return visit(ctx.expr()); }

    @Override
    public Void visitIntLit(IntLitContext ctx)
    {
        try { emitter.loadInt(Integer.parseInt(ctx.INT().getText())); }
        catch (NumberFormatException e) {
            error(ctx, "integer literal too large: " + ctx.INT().getText());
            emitter.emit("iconst_0");
        }
        return null;
    }
    
    //CHORD_QUALITIES must match the order of the CHORDS array in MusicRuntime.java, and DURATIONS must match the order of the durations in MusicRuntime.java.
    private static final String[] CHORD_QUALITIES = {"Major", "Minor", "Dim", "Aug", "Dom7", "Maj7", "Min7"};
    private static final String[] DURATIONS = {"Whole", "Half", "Quarter"};      // 4, 2, 1 beats
    private static final java.util.regex.Pattern NOTE_NAME =
        java.util.regex.Pattern.compile("([A-G])(s|b)?(\\d)");                   // C4, Fs3, Bb2

    /** Pitch.C4 / Chord.Major / Duration.Quarter compile to int constants. */
    @Override
    public Void visitEnumExpr(EnumExprContext ctx)
    {
        String type = ctx.enumLit().getChild(0).getText(), name = ctx.enumLit().ID().getText();
        int value = -1;
        switch (type) {
            case "Chord" -> value = java.util.Arrays.asList(CHORD_QUALITIES).indexOf(name);
            case "Duration" -> {
                int i = java.util.Arrays.asList(DURATIONS).indexOf(name);
                value = i < 0 ? -1 : 4 >> i;
            }
            default -> {
                var m = NOTE_NAME.matcher(name);
                if (m.matches()) {
                    int semitone = "C D EF G A B".indexOf(m.group(1));
                    if (m.group(2) != null) semitone += m.group(2).equals("s") ? 1 : -1;
                    value = 12 * (Integer.parseInt(m.group(3)) + 1) + semitone;   // C4 = 60
                    if (value < 0 || value > 127) value = -1;
                }
            }
        }
        if (value < 0) { error(ctx, "unknown " + type + " constant '" + name + "'"); value = 0; }
        emitter.loadInt(value);
        return null;
    }

    @Override
    public Void visitBoolLit(BoolLitContext ctx)
    {
        emitter.emit(ctx.getText().equals("true") ? "iconst_1" : "iconst_0");
        return null;
    }

    @Override
    public Void visitVar(VarContext ctx)
    {
        SymtabEntry e = lookupVar(ctx, ctx.ID().getText());
        emitter.emit(e != null ? "iload " + slot(e) : "iconst_0");
        return null;
    }

    @Override
    public Void visitCallExpr(CallExprContext ctx)
    {
        FuncSig sig = emitCall(ctx.call());
        if (sig == null) emitter.emit("iconst_0");
        else if (sig.ret.equals("void")) {
            error(ctx, "void function '" + sig.name + "' used as a value");
            emitter.emit("iconst_0");
        }
        return null;
    }

    @Override
    public Void visitUnary(UnaryContext ctx)
    {
        boolean not = ctx.op.getText().equals("!");
        expect(ctx, ctx.expr(), not ? "bool" : "int", "operand of '" + ctx.op.getText() + "'");
        visit(ctx.expr());
        if (!not) { emitter.emit("ineg"); return null; }
        String isZero = emitter.newLabel(), end = emitter.newLabel();
        emitter.emit("ifeq " + isZero);
        emitter.emit("iconst_0");
        emitter.emit("goto " + end);
        emitter.label(isZero);
        emitter.emit("iconst_1");
        emitter.label(end);
        return null;
    }

    @Override
    public Void visitMulDiv(MulDivContext ctx)
    {
        checkOperands(ctx, ctx.expr(0), ctx.expr(1), "int");
        visit(ctx.expr(0)); visit(ctx.expr(1));
        switch (ctx.op.getText()) {
            case "*" -> emitter.emit("imul");
            case "/" -> emitter.emit("idiv");
            default  -> emitter.emit("irem");
        }
        return null;
    }

    @Override
    public Void visitAddSub(AddSubContext ctx)
    {
        checkOperands(ctx, ctx.expr(0), ctx.expr(1), "int");
        visit(ctx.expr(0)); visit(ctx.expr(1));
        emitter.emit(ctx.op.getText().equals("+") ? "iadd" : "isub");
        return null;
    }

    @Override
    public Void visitRelational(RelationalContext ctx)
    {
        checkOperands(ctx, ctx.expr(0), ctx.expr(1), "int");
        String jump = switch (ctx.op.getText()) {
            case "<"  -> "if_icmplt";
            case "<=" -> "if_icmple";
            case ">"  -> "if_icmpgt";
            default   -> "if_icmpge";
        };
        compare(ctx.expr(0), ctx.expr(1), jump);
        return null;
    }

    @Override
    public Void visitEquality(EqualityContext ctx)
    {
        String l = typeOf(ctx.expr(0)), r = typeOf(ctx.expr(1));
        if (!l.equals("?") && !r.equals("?") && !l.equals(r))
            error(ctx, "type mismatch in '" + ctx.op.getText() + "': " + l + " vs " + r);
        compare(ctx.expr(0), ctx.expr(1), ctx.op.getText().equals("==") ? "if_icmpeq" : "if_icmpne");
        return null;
    }

    /** Both operands must have the wanted type. */
    private void checkOperands(ParserRuleContext ctx, ExprContext left, ExprContext right, String want)
    {
        String op = ctx.getChild(1).getText();
        expect(ctx, left, want, "left operand of '" + op + "'");
        expect(ctx, right, want, "right operand of '" + op + "'");
    }

    /** Push 1 if (left jump right) holds, else 0. */
    private void compare(ExprContext left, ExprContext right, String jump)
    {
        String isTrue = emitter.newLabel(), end = emitter.newLabel();
        visit(left); visit(right);
        emitter.emit(jump + " " + isTrue);
        emitter.emit("iconst_0");
        emitter.emit("goto " + end);
        emitter.label(isTrue);
        emitter.emit("iconst_1");
        emitter.label(end);
    }

    @Override
    public Void visitAnd(AndContext ctx)
    {
        checkOperands(ctx, ctx.expr(0), ctx.expr(1), "bool");
        String isFalse = emitter.newLabel(), end = emitter.newLabel();
        visit(ctx.expr(0));
        emitter.emit("ifeq " + isFalse);     // short-circuit
        visit(ctx.expr(1));
        emitter.emit("ifeq " + isFalse);
        emitter.emit("iconst_1");
        emitter.emit("goto " + end);
        emitter.label(isFalse);
        emitter.emit("iconst_0");
        emitter.label(end);
        return null;
    }

    @Override
    public Void visitOr(OrContext ctx)
    {
        checkOperands(ctx, ctx.expr(0), ctx.expr(1), "bool");
        String isTrue = emitter.newLabel(), end = emitter.newLabel();
        visit(ctx.expr(0));
        emitter.emit("ifne " + isTrue);      // short-circuit
        visit(ctx.expr(1));
        emitter.emit("ifne " + isTrue);
        emitter.emit("iconst_0");
        emitter.emit("goto " + end);
        emitter.label(isTrue);
        emitter.emit("iconst_1");
        emitter.label(end);
        return null;
    }
}
