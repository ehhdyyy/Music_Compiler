package backend.compiler;

import java.io.File;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;

import frontend.MusicLexer;
import frontend.MusicParser;

/**
 * Usage: java backend.compiler.Compiler <file.mus> [outputDir]
 * Writes <ClassName>.j, where ClassName comes from "compose <ClassName>".
 */
public class Compiler
{
    public static void main(String[] args) throws Exception
    {
        boolean showSymtab = java.util.Arrays.asList(args).contains("-symtab");
        args = java.util.Arrays.stream(args).filter(a -> !a.equals("-symtab")).toArray(String[]::new);
        if (args.length < 1) {
            System.err.println("Usage: Compiler [-symtab] <file.mus> [outputDir]");
            System.exit(1);
        }
        String outDir = args.length > 1 ? args[1] : ".";

        MusicLexer lexer = new MusicLexer(CharStreams.fromFileName(args[0]));
        MusicParser parser = new MusicParser(new CommonTokenStream(lexer));
        ParseTree tree = parser.program();

        if (parser.getNumberOfSyntaxErrors() > 0) {
            System.err.println(parser.getNumberOfSyntaxErrors() + " syntax error(s). No code generated.");
            System.exit(1);
        }

        CodeGenerator gen = new CodeGenerator();
        gen.visit(tree);
        if (showSymtab) System.out.println(gen.symtabReport());

        if (!gen.getErrors().isEmpty()) {
            gen.getErrors().forEach(e -> System.err.println("Semantic error, " + e));
            System.err.println(gen.getErrors().size() + " semantic error(s). No code generated.");
            System.exit(1);
        }

        new File(outDir).mkdirs();
        String path = outDir + File.separator + gen.getClassName() + ".j";
        gen.getEmitter().writeTo(path);
        System.out.println("Wrote " + path);
    }
}
