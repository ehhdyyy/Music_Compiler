package backend.compiler;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Writes Jasmin assembly text. Method bodies are buffered so that the
 * .limit directives can be written after the body size is known.
 */
public class Emitter
{
    private final StringBuilder out = new StringBuilder();
    private StringBuilder body = null;   // non-null while inside a method
    private int labelCount = 0;

    public String newLabel() { return String.format("L%03d", ++labelCount); }

    /** Emit a directive or header line (no indent). */
    public void directive(String text) { target().append(text).append('\n'); }

    /** Emit an instruction (indented). */
    public void emit(String instruction) { target().append("    ").append(instruction).append('\n'); }

    public void label(String label) { target().append(label).append(":\n"); }

    public void comment(String text) { target().append("; ").append(text).append('\n'); }

    /** Load an int constant using the shortest instruction. */
    public void loadInt(int value)
    {
        if (value >= -1 && value <= 5) emit(value == -1 ? "iconst_m1" : "iconst_" + value);
        else if (value >= -128 && value <= 127) emit("bipush " + value);
        else if (value >= -32768 && value <= 32767) emit("sipush " + value);
        else emit("ldc " + value);
    }

    public void beginMethod(String header)
    {
        out.append('\n').append(".method ").append(header).append('\n');
        body = new StringBuilder();
    }

    public void endMethod(int maxStack, int maxLocals)
    {
        out.append(".limit stack ").append(maxStack).append('\n');
        out.append(".limit locals ").append(maxLocals).append('\n');
        out.append(body);
        out.append(".end method\n");
        body = null;
    }

    public void writeTo(String path) throws IOException
    {
        try (PrintWriter pw = new PrintWriter(new FileWriter(path))) { pw.print(out); }
    }

    private StringBuilder target() { return body != null ? body : out; }
}
