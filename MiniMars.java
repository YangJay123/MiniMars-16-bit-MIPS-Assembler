import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** 16-bit Mini-MIPS assembler. Compile: javac MiniMars.java */
public final class MiniMars {

    // Recognize a label at the beginning of a line. Group 1 is the label name;
    // group 2 is anything after the colon
    private static final Pattern LABEL = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*):\\s*(.*)$");
    // Genuine R-type instructions use these 4-bit opcodes. Mini-MIPS has no
    // separate function field; the low three shamt bits are zero for these operations.
    private static final Map<String, Integer> ROPS = new HashMap<>();
    static {
        ROPS.put("AND", 0);
        ROPS.put("OR", 1);
        ROPS.put("ADD", 2);
        ROPS.put("SUB", 6);
        ROPS.put("SLT", 7);
        ROPS.put("MUL", 8);
        ROPS.put("NOR", 12);
    }

    // Keep the original line number and text together so
    // errors can be reported at the assembly line that produced them.
    private static final class Source {
        final int line;
        final String text;

        Source(int line, String text) {
            this.line = line;
            this.text = text;
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException("line " + line + ": " + message + " [" + text + "]");
        }
    }

    // One parsed .WORD item: starting word address, value to store, and number
    // of repetitions. For example, .WORD 5:3 reserves three words containing 5.
    private static final class WordData {
        final Source source;
        final int address, value, count;

        WordData(Source s, int a, int v, int c) {
            source = s;
            address = a;
            value = v;
            count = c;
        }
    }

    // One real instruction after pseudo-instruction expansion. It retains its
    // source location, but its text is the genuine hardware instruction to encode.
    private static final class Instruction {
        final Source source;
        final String text;

        Instruction(Source s, String t) {
            source = s;
            text = t;
        }
    }

    // Per-input assembly state. LinkedHashMap keeps symbol tables in source order.
    // Data labels refer to data-memory addresses; text labels refer to instruction
    // addresses after initialization code and pseudo-instruction expansion.
    private final Map<String, Integer> dataLabels = new LinkedHashMap<>();
    private final Map<String, Integer> textLabels = new LinkedHashMap<>();
    // This shared set detects duplicate label names across both segments.
    private final Set<String> allLabels = new HashSet<>();
    // Parsed declarations/source lines are collected first. Expanded hardware
    // instructions are collected later, and each list index is its word address.
    private final List<WordData> data = new ArrayList<>();
    private final List<Source> text = new ArrayList<>();
    private final List<Instruction> instructions = new ArrayList<>();
    // Log-only comments/labels keyed by the instruction address before which
    // they should appear. These strings never consume instruction memory.
    private final Map<Integer, List<String>> annotations = new LinkedHashMap<>();

    // Command-line driver: assemble every filename independently. A new assembler
    // object prevents one input's symbols from leaking into the next input.
    // Continue after a failed file, but return a failing exit status if any failed.
    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: java MiniMars file1.txt [file2.txt ...]");
            System.exit(1);
        }
        boolean failed = false;
        for (String arg : args) {
            try {
                new MiniMars().assemble(Paths.get(arg));
                System.out.println("Assembled " + arg + " -> " + arg + ".asm, " + arg + ".log");
            } catch (IOException | IllegalArgumentException ex) {
                failed = true;
                System.err.println(arg + ": " + ex.getMessage());
            }
        }
        if (failed)
            System.exit(1);
    }

    // Assembly pipeline: parse -> generate initialization -> expand text and
    // assign addresses -> resolve/encode -> write Logisim output and debug log.
    private void assemble(Path input) throws IOException {
        parse(Files.readAllLines(input, StandardCharsets.UTF_8));
        // Generate executable initialization code, rather than a separate data
        // image. Load each declaration's value into $1, then store it into every
        // reserved word. expand() handles values too large for a real ADDI.
        // Example: .WORD 5:3 at address 0 generates a load of 5 and stores to 0,1,2.
        for (WordData d : data) {
            note("### Data memory initialization: " + d.source.text);
            expand(new Source(d.source.line, "ADDI $1, $0, " + d.value));
            for (int i = 0; i < d.count; i++)
                emit(d.source, "SW $1, " + (d.address + i) + "($0)");
        }
        // Expand source instructions in order. Because initialization instructions
        // are already present, text labels automatically include that prefix.
        note(".TEXT");
        String lastOp = "";
        for (Source s : text) {
            Matcher m = LABEL.matcher(s.text);
            String body = s.text;
            // Bind each label to the next instruction's address. Multiple labels
            // may point to that same address; label-only lines generate no code.
            while (m.matches()) {
                textLabels.put(m.group(1), instructions.size());
                note(m.group(1) + ":");
                body = m.group(2).trim();
                m = LABEL.matcher(body);
            }
            if (body.isEmpty())
                continue;
            Source instruction = new Source(s.line, body);
            lastOp = tokens(body)[0].toUpperCase(Locale.ROOT);
            expand(instruction);
        }
        // STOP becomes a branch to itself. Append it if the source omitted it.
        if (!lastOp.equals("STOP"))
            expand(new Source(0, "STOP"));
        if (instructions.size() > 65536)
            throw new IllegalArgumentException("instruction memory exceeds 65536 words");
        // All text-label addresses are now known, including forward references.
        // Encode each instruction into one 16-bit word and build both output files.
        StringBuilder raw = new StringBuilder("v2.0 raw\n");
        StringBuilder log = new StringBuilder();
        symbolTable(log, ".DATA", dataLabels);
        symbolTable(log, ".TEXT", textLabels);
        for (int pc = 0; pc < instructions.size(); pc++) {
            List<String> notes = annotations.get(pc);
            if (notes != null)
                for (String n : notes)
                    log.append(n).append('\n');
            Instruction ins = instructions.get(pc);
            int code = encode(ins, pc);
            String hex = String.format(Locale.ROOT, "%04X", code);
            raw.append(hex);
            if (pc + 1 < instructions.size()) {
                raw.append(' ');
            } else {
                raw.append('\n');
            }
            log.append(ins.text).append("    ### ").append(pc).append(": machine code = ").append(hex).append('\n');
        }
        // Encode everything before writing either file, so invalid input produces no
        // partial output.
        Files.write(Paths.get(input + ".asm"), raw.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(Paths.get(input + ".log"), log.toString().getBytes(StandardCharsets.UTF_8));
    }

    // TODOTODTODTODOTODOTDOTODO
    private void parse(List<String> lines) {
            /*
            * TODO: Read and organize the assembly source.
            * - Ignore comments and blank lines.
            * - Identify data and text segments.
            * - Record data labels and their memory addresses.
            * - Read .WORD declarations and record their initialization requirements.
            * - Preserve text instructions and labels for later processing.
            * - Report invalid declarations and duplicate labels.
            */
           String segment = "";
           int dataAdd = 0;

           for (int i = 0; i < lines.size(); i++){
            String line = lines.get(i).split("#", 2)[0].trim();
            if (line.isEmpty()){
                continue;
            }
           Source s = new Source (i + 1, line);
           if(line.equalsIgnoreCase(".data")){
            segment = "DATA";
            continue;
           }
           if (line.equalsIgnoreCase(".text")){
            segment = "TEXT";
            continue;
           }
           // Checks if the current segment is TEXT
           // Reads instructions and checks for duplicate labels
           // Stores the instructions and labels for later processing
           if (segment.equals("TEXT")){
             String body = line;
             Matcher matcher = LABEL.matcher(body);
             while (matcher.matches()) {
             String label = matcher.group(1);

             if (!allLabels.add(label)) {
             throw s.error("Duplicate label " + label);
             }

             body = matcher.group(2).trim();
             matcher = LABEL.matcher(body);
             }
             if (body.startsWith(".")){
                String labels = line.substring(0, line.length() - body.length()).trim();
                if (!labels.isEmpty()) {
                    text.add(new Source(i + 1, labels));
                }
             } else {
                 text.add(s);
             }
             continue;
           }
           // Checks if the current segment is DATA
           // Reads data labels and records their memory addresses
           // Processes .WORD values and stores them for initialization
           if (segment.equals("DATA")) {
            String body = line;
            Matcher matcher = LABEL.matcher(body);
            while (matcher.matches()) {
            String label = matcher.group(1);
            if (!allLabels.add(label)) {
            throw s.error("Duplicate label " + label);
            }
            dataLabels.put(label, dataAdd);
            body = matcher.group(2).trim();
            matcher = LABEL.matcher(body);
            }
            if (body.isEmpty()) {
            continue;
            }

            String[] declaration = body.split("\\s+", 2);

            if (declaration[0].equalsIgnoreCase(".word")) {

                if (declaration.length != 2 || declaration[1].trim().isEmpty()) {
                    throw s.error(".WORD needs a value");
                }
                String[] entries = declaration[1].split(",", -1);

                for (String entry : entries) {
                    entry = entry.trim();

                    if (entry.isEmpty()) {
                        throw s.error("Invalid .WORD declaration");
                    }
                    String[] values = entry.split("\\s*:\\s*", -1);

                    if (values.length > 2) {
                        throw s.error("Invalid .WORD declaration");
                    }
                    int value = number(values[0], s);
                    int count;

                    if (values.length == 2) {
                        count = number(values[1], s);
                    } else {
                        count = 1;
                    }
                    range(value, -128, 127, ".WORD value", s);

                    if (count <= 0) {
                        throw s.error("Must be positive");
                    }

                    if ((long) dataAdd + count > 65536) {
                        throw s.error("Out of Memory");
                    }

                    data.add(new WordData(s, dataAdd, value, count));
                    dataAdd += count;

                } 

            } else if (!body.startsWith(".")) {
                throw s.error("Expected .WORD declaration");
            }
         }
          // Checks if the current segment is neither TEXT nor DATA
          // Makes sure instructions are not written outside these segments
          // Throws an error if an invalid instruction is found
         if (!segment.equals("TEXT") && !segment.equals("DATA")) {
         if (!line.startsWith(".")) {
            throw s.error("Instruction outside .DATA or .TEXT");
          }
        }
      }
    }
           
    // Translate each pseudo instruction using the exact sequences in the
    // handout. Genuine instructions remain as text until the encoding pass.
    // Notes preserve the original pseudo instruction in the debug log.
    private void expand(Source s) {
        String[] t = tokens(s.text);
        String op = t[0].toUpperCase(Locale.ROOT);
        switch (op) {
            // AND into read-only $0 has no observable effect.
            case "NOP":
                arity(t, 1, s);
                note("# " + s.text + " # pseudo instruction");
                emit(s, "AND $0, $0, $0");
                break;
            // Copy by adding the source register to constant zero.
            case "MOVE":
                arity(t, 3, s);
                reg(t[1], s);
                reg(t[2], s);
                note("# " + s.text + " # pseudo instruction");
                emit(s, "ADD " + t[1] + ", $0, " + t[2]);
                break;
            // Copy once, then double n times: adding a value to itself shifts
            // left one bit in the 16-bit datapath. No hardware shift opcode exists.
            case "SLL": {
                arity(t, 4, s);
                reg(t[1], s);
                reg(t[2], s);
                int n = number(t[3], s);
                range(n, 0, 127, "shift count", s);
                note("# " + s.text + " # pseudo instruction");
                emit(s, "ADD " + t[1] + ", $0, " + t[2]);
                for (int j = 0; j < n; j++)
                    emit(s, "ADD " + t[1] + ", " + t[1] + ", " + t[1]);
                break;
            }
            // LI uses a numeric value; LA looks up a data label's word address.
            // Once resolved, both use the same immediate-loading sequence.
            case "LI":
            case "LA": {
                arity(t, 3, s);
                reg(t[1], s);
                int n;
                if (op.equals("LA")) {
                    Integer a = dataLabels.get(t[2]);
                    if (a == null)
                        throw s.error("undefined data label " + t[2]);
                    n = a;
                } else
                    n = number(t[2], s);
                range(n, -128, 127, "load immediate/address", s);
                note("# " + s.text + " # pseudo instruction");
                load(s, t[1], n);
                break;
            }
            // A signed 6-bit immediate is a real instruction. Larger signed
            // 8-bit values use the required load-constant sequence plus one ADD.
            // As specified, this clobbers the source if source == destination.
            case "ADDI": {
                arity(t, 4, s);
                reg(t[1], s);
                reg(t[2], s);
                int n = number(t[3], s);
                range(n, -128, 127, "ADDI immediate", s);
                if (n >= -32 && n <= 31)
                    emit(s, s.text);
                else {
                    note("# " + s.text + " # pseudo instruction");
                    load(s, t[1], n);
                    emit(s, "ADD " + t[1] + ", " + t[2] + ", " + t[1]);
                }
                break;
            }
            // Offset -1 branches from the next PC back to this instruction.
            case "STOP":
                arity(t, 1, s);
                note("# " + s.text + " # pseudo instruction");
                emit(s, "BEQ $7, $7, -1");
                break;
            default:
                emit(s, s.text);
        }
    }

    // Load a signed 8-bit constant into a register. Small values need one ADDI.
    // Larger values use (n / 4) * 4 + (n % 4): load the quotient, double twice,
    // and add the remainder. Even a zero remainder gets its required ADDI.
    private void load(Source s, String dest, int n) {
        if (n >= -32 && n <= 31)
            emit(s, "ADDI " + dest + ", $0, " + n);
        else {
            // Java division/remainder truncate toward zero, exactly as n/4 and n%4.
            emit(s, "ADDI " + dest + ", $0, " + n / 4);
            emit(s, "ADD " + dest + ", " + dest + ", " + dest);
            emit(s, "ADD " + dest + ", " + dest + ", " + dest);
            emit(s, "ADDI " + dest + ", " + dest + ", " + n % 4);
        }
    }

    // TODOTODOTODOTODOTODO
    private int encode(Instruction ins, int pc) {

        /*
        * TODO: Convert one real instruction into its 16-bit machine code.
        * - Identify the instruction and validate its operands.
        * - Resolve labels used by branches and jumps.
        * - Check that operand values fit the instruction's fields.
        * - Encode the instruction according to its R, I, or J format.
        * - Report unsupported instructions or invalid operands.
        */
        
       
        String[] t = tokens(ins.text);
        String op = t[0].toUpperCase(Locale.ROOT);
        Source s = ins.source;
        // Checks the instruction type and processes each supported operation
        // Validates registers, immediate values, and addresses for each instruction
        // Converts R-type, I-type, and J-type instructions into 16-bit machine code
        if (ROPS.containsKey(op)){
            arity(t, 4, s);
            int rd = reg(t[1], s);
            int rs = reg(t[2], s);
            int rt = reg(t[3], s);
            int opcode = ROPS.get(op);

            return (opcode << 12) | (rs << 9) | (rt << 6) | (rd << 3);
        }

        if(op.equals("ADDI")){
            arity(t, 4, s);
            int rt = reg(t[1], s);
            int rs = reg(t[2], s);
            int imm = number(t[3], s);
            range(imm, -32, 31, "immediate", s);
            int opcode = 3;

            return (opcode << 12) | (rs << 9) | (rt << 6) | (imm & 0x3F);
        }

        if (op.equals("LW") || op.equals("SW")) {
             arity(t, 4, s);
             int rt = reg(t[1], s);
             int imm = number(t[2], s);
             int rs = reg(t[3], s);
             range(imm, -32, 31, "offset", s);
             int opcode;

             if (op.equals("LW")) {
             opcode = 4;
             } else {
             opcode = 5;
             }
             return (opcode << 12) | (rs << 9) | (rt << 6) | (imm & 0x3F);
        }
        if (op.equals("BEQ") || op.equals("BNE")) {
             arity(t, 4, s);
             int rs = reg(t[1], s);
             int rt = reg(t[2], s);
             int offset;

             if (textLabels.containsKey(t[3])) {
                int target = textLabels.get(t[3]);
                offset = target - (pc + 1);
                }else{
                    offset = number(t[3], s);
                }
            range(offset, -32, 31, "branch offset", s);
            int opcode;

            if(op.equals("BEQ")){
                opcode = 14;
            }else{
                opcode = 13;
            }

            return (opcode << 12) | (rs << 9) | (rt << 6) | (offset & 0x3F);
        }

        if(op.equals("J")){
            arity (t, 2, s);
            int target;

            if(textLabels.containsKey(t[1])){
                target = textLabels.get(t[1]);
            } else {
                target = number(t[1], s);
            }
            range(target, 0, 65535, "jump target", s);

            if ((target >>> 12) != (((pc + 1) & 0xFFFF) >>> 12)) {
                throw s.error("Jump target outside current region");
            }
            int opcode = 15;

            return (opcode << 12) | (target & 0xFFF);
        }

        throw s.error("Unsupported instruction: " + op);

    }

    // Small parsing/validation helpers shared by expansion and encoding.
    // Split operands on whitespace, commas, and memory-address parentheses.
    private static String[] tokens(String text) {
        return text.trim().split("[\\s,()]+");
    }

    // Check the total token count: one mnemonic plus its expected operands.
    private static void arity(String[] t, int expected, Source s) {
        if (t.length != expected)
            throw s.error("wrong number of operands");
    }

    // Validate a register token and convert its digit to the 3-bit register index.
    private static int reg(String token, Source s) {
        if (token.equalsIgnoreCase("$zero")) {
        return 0;
        }
        if (!token.matches("\\$[0-7]"))
            throw s.error("expected register $0 through $7, got " + token);
        return token.charAt(1) - '0';
    }

    // Read signed decimal/hex/binary literals. An optional $ on immediates is
    // tolerated for the quiz examples; register operands are validated separately.
    private static int number(String token, Source s) {
        String n = token.startsWith("$") ? token.substring(1) : token;
        try {
            int sign = 1;
            if (n.startsWith("-") || n.startsWith("+")) {
                if (n.charAt(0) == '-')
                    sign = -1;
                n = n.substring(1);
            }
            int radix = 10;
            if (n.startsWith("0x") || n.startsWith("0X")) {
                radix = 16;
                n = n.substring(2);
            } else if (n.startsWith("0b") || n.startsWith("0B")) {
                radix = 2;
                n = n.substring(2);
            }
            return Math.toIntExact(sign * Long.parseLong(n, radix));
        } catch (NumberFormatException | ArithmeticException e) {
            throw s.error("invalid number or undefined label " + token);
        }
    }

    // Reject out-of-range operands instead of silently truncating their bits.
    private static void range(int n, int min, int max, String name, Source s) {
        if (n < min || n > max)
            throw s.error(name + " must be " + min + ".." + max + ", got " + n);
    }

    // Queue one real instruction; its address is its index in this list.
    private void emit(Source s, String text) {
        instructions.add(new Instruction(s, text));
    }

    // Queue a log annotation at the current address without generating code.
    private void note(String text) {
        annotations.computeIfAbsent(instructions.size(), k -> new ArrayList<>()).add(text);
    }

    // Print labels and their resolved word addresses for debugging.
    private static void symbolTable(StringBuilder log, String segment, Map<String, Integer> labels) {
        log.append("# The symbol table in the ").append(segment).append(" segment.\n");
        if (labels.isEmpty())
            log.append("# ---empty---\n");
        labels.forEach((name, address) -> log.append("# ").append(name).append(": ").append(address).append('\n'));
    }
}
