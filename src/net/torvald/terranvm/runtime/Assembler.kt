package net.torvald.terranvm.runtime

import net.torvald.terranvm.runtime.Assembler.resolveInt
import net.torvald.terranvm.runtime.Assembler.toRegInt


/**
 * ## Syntax
 * - Space/Tab/comma is a delimiter.
 * - Any line starts with # is comment
 * - Any text after # is a comment
 * - Labelling: @label_name
 * - String is surrounded with double quote
 * - Every line must end with ; (@TODO wow, such primitive!)
 *
 * Example program
 * ```
 * .code
 *
 * LOADSTRINLINE 1, Helvetti world!;
 * PRINTSTR;
 * ```
 * This prints out 'Helvetti world!' on the standard output.
 *
 *
 * ## Sections
 *
 * TBAS Assembly can be divided into _sections_ that the assembler supports. If no section is given, 'code' is assumed.
 *
 * Supported sections:
 * - data
 * - func
 * - code
 *
 * Indentation after section header is optional (and you probably don't want it anyway).
 *
 * ### Data
 * Data section has following syntax:
 * ```
 * type label_name (payload)
 * ```
 * Label names are case insensitive.
 * Available types:
 * - STRING (BYTES but will use quotes in the ASM line)
 * - FLOAT (WORD; payload is interpreted as float)
 * - INT (WORD; payload is interpreted as int)
 * - BYTES (byte literals, along with pointer label -- pointer label ONLY!)
 *
 * You use your label in code by '@label_name', just like line labels.
 *
 *
 * ### Literals
 * - Register literals: r1, r2, r3, r4, r5, r6, r7, r8
 * - Hex literals: CAFEBABEh
 * - Integer literals: 80085
 *
 * Defining labels:
 * :label_name;
 *
 * Referring labels:
 * @label_name
 *
 *
 * Created by minjaesong on 2017-05-28.
 */
object Assembler {

    private val delimiters = Regex("""[ \t,]+""")
    private val blankLines = Regex("""(?<=;)[\n ]+""")
    private val stringMarker = Regex("""\"[^\n]*\"""")
    private val labelMarker = '@'
    private val labelDefinitionMarker = ':'
    private val lineEndMarker = ';'
    private val commentMarker = '#'
    private val literalMarker = '"'
    private val sectionHeading = Regex("""\.[A-Za-z0-9_]+""")
    private val matchInteger = Regex("""[0-9]+""")
    private val regexWhitespaceNoSP = Regex("""[\t\r\n\v\f]""")
    private val prependedSpaces = Regex("""^[\s]+""")


    private val dataSectActualData = Regex("""^[A-Za-z]+[m \t]+[A-Za-z0-9_]+[m \t]+""")

    private val registerLiteral = Regex("""r[1-8]""")
    private val hexLiteral = Regex("""[0-9A-Fa-f]+h""")

    private val labelTable = HashMap<String, Int>() // valid name: @label_name_in_lower_case

    private var currentSection = ".CODE"

    val asmSections = hashSetOf<String>(".CODE", ".DATA", ".FUNC")




    val bitmaskRd = 0b00000001110000000000000000000000
    val bitmaskRs = 0b00000000001110000000000000000000
    val bitmaskRm = 0b00000000000001110000000000000000
    val bitmaskR4 = 0b00000000000000001110000000000000
    val bitmaskR5 = 0b00000000000000000001110000000000
    val bitmaskCond = 0b11100000000000000000000000000000
    val conditions: HashMap<String, Int> = hashMapOf(
            "" to 0,
            "Z"  to 0x20000000,
            "NZ" to 0x40000000,
            "GT" to 0x60000000,
            "LS" to 0x80000000L.toInt()
    )
    private val primitiveOpcodes: HashMap<String, Int> = hashMapOf(
            // Mathematical and Register data transfer //

            "MOV" to 0b100000,
            "XCHG" to 0b100001,
            "INC"  to 0b100010,
            "DEC"  to 0b100011,
            "MALLOC" to 0b100100,
            "FTOI" to 0b100101,
            "ITOF" to 0b100110,
            "GOSUB" to 0b111110,
            "RETURN" to 0b111111,

            "ADD"  to 0b000001,
            "SUB"  to 0b000010,
            "MUL"  to 0b000011,
            "DIV"  to 0b000100,
            "POW"  to 0b000101,
            "MOD"  to 0b000110,
            "SHL"  to 0b000111,
            "SHR"  to 0b001000,
            "USHR" to 0b001001,
            "AND"  to 0b001010,
            "OR"   to 0b001011,
            "XOR"  to 0b001100,
            "ABS"  to 0b010000,
            "SIN"  to 0b010001,
            "COS"  to 0b010010,
            "TAN"  to 0b010011,
            "FLOOR" to 0b010100,
            "CEIL" to 0b010101,
            "ROUND" to 0b010110,
            "LOG"  to 0b010111,
            "RNDI" to 0b011000,
            "RND"  to 0b011001,
            "SGN"  to 0b011010,
            "SQRT" to 0b011011,
            "CBRT" to 0b011100,
            "INV"  to 0b011101,
            "RAD"  to 0b011110,
            "NOT"  to 0b011111,

            "HALT" to 0,

            // Load and Store to memory //

            "LOADBYTE"  to 0b0000_000000000_0000000001000_00_0,
            "LOADHWORD" to 0b0000_000000000_0000000001000_01_0,
            "LOADWORD"  to 0b0000_000000000_0000000001000_10_0,
            "STOREBYTE"  to 0b0000_000000000_0000000001000_00_1,
            "STOREHWORD" to 0b0000_000000000_0000000001000_01_1,
            "STOREWORD"  to 0b0000_000000000_0000000001000_10_1,

            // Memory copy //

            "MEMCPY" to 0b0000_000000000000000_0001001000,

            // Compare //

            "CMP" to 0b0001_000000_0000000000000000000,
            "CMPII" to 0b0001_000000_0000000000000000000,
            "CMPIF" to 0b0001_000000_0000000000000000001,
            "CMPFI" to 0b0001_000000_0000000000000000010,
            "CMPFF" to 0b0001_000000_0000000000000000011,

            // Load and Store byte/halfword/word immediate //

            "LOADBYTEI"   to 0b0001_000_000010_00000000_00000000,
            "LOADHWORDI"  to 0b0001_000_000100_0000000000000000,
            "STOREBYTEI"  to 0b0001_000_000011_00000000_00000000,
            "STOREHWORDI" to 0b0001_000_000101_0000000000000000,

            "LOADWORDI"  to 0b0001_000_000110_0000000000000000,

            // Load and Store a word from register to memory //

            "LOADWORDIMEM" to 0b0010.shl(25),
            "STOREWORDIMEM" to 0b0011.shl(25),

            // Push and Pop //

            "PUSH" to 0b0100.shl(25),
            "POP"  to 0b0101.shl(25),
            "PUSHWORDI" to 0b0110.shl(25),
            "POPWORDI"  to 0b0111.shl(25),

            // Conditional jump (WARNING: USES CUSTOM CONDITION HANDLER!) //

            /*"JMP" to 0b000_1000.shl(25),
            "JZ"  to 0b001_1000.shl(25),
            "JNZ" to 0b010_1000.shl(25),
            "JGT" to 0b011_1000.shl(25),
            "JLS" to 0b100_1000.shl(25),
            "JFW" to 0b101_1000.shl(25),
            "JBW" to 0b110_1000.shl(25),*/

            // Call peripheral //

            "CALL" to 0b1111_000_00000000000000_00000000,
            "MEMSIZE" to 0b1111_000_00000000000001_00000000,
            "UPTIME"  to 0b1111_000_00000000000001_11111111,
            "INT" to 0b111111111111111111111_00000000,

            // Assembler-specific commands //

            "NOP" to 0b000_0000_000_000_000_0000000000_100000 // MOV r1, r1
    )
    val opcodes = HashMap<String, Int>()

    /**
     * @return r: register, b: byte, w: halfword, f: full word (LOADWORDI only!) a: address offset
     */
    fun getOpArgs(opcode: Int): String {
        val opcode = opcode.and(0x1FFFFFFF) // drop conditions
        return when (opcode.ushr(25).and(0xF)) {
            0 -> {
                val mathOp = opcode.and(127)
                if (mathOp == 0) {
                    ""
                }
                else if (mathOp == 0b011000 || mathOp == 0b011001) { // random number
                    "r"
                }
                else if (mathOp in 0b000001..0b001111) {
                    "rrr"
                }
                else if (mathOp in 0b010000..0b011111) {
                    "rr"
                }
                else if (mathOp == 0b100000 || mathOp == 0b100001 || mathOp == 0b100100) { // MOV and XCHG; MALLOC
                    "rr"
                }
                else if (mathOp == 0b100010 || mathOp == 0b100011 || mathOp == 0b100101 || mathOp == 0b100110) {
                    "r"
                }
                else if (mathOp == 0b1000000) { // load/store
                    "rrr"
                }
                else if (mathOp == 0b1001000) { // memcpy
                    "rrrrr"
                }
                else {
                    ""
                }
            }
            1 -> {
                val mode = opcode.ushr(17).and(0b11111)
                return when (mode) {
                    0, 0b00100, 0b01000, 0b01100, 0b10000, 0b10100, 0b11000, 0b11100 -> "rr"
                    1 -> "rb"
                    2 -> "rw"
                    3 -> "rf"
                    else -> throw IllegalArgumentException()
                }
            }
            2, 3 -> "ra" // loadi/storei
            4, 5 -> "r"  // push/pop
            6 -> "a" // pushwordi
            7 -> ""  // popwordi
            8 -> "a" // conditional jump
            15 -> {
                val cond = opcode.ushr(8).and(0x3FFF)

                if (cond <= 1) {
                    "rb"
                }
                else if (cond == 0x3FFF) {
                    "b"
                }
                else throw IllegalArgumentException()
            }
            else -> throw IllegalArgumentException()
        }
    }

    init {
        // fill up opcodes with conditions
        conditions.forEach { suffix, bits ->
            primitiveOpcodes.forEach { mnemo, opcode ->
                opcodes[mnemo + suffix] = bits or opcode
            }
        }

        opcodes.putAll(mapOf(
                "JMP" to 0b000_1000.shl(25),
                "JZ"  to 0b001_1000.shl(25),
                "JNZ" to 0b010_1000.shl(25),
                "JGT" to 0b011_1000.shl(25),
                "JLS" to 0b100_1000.shl(25),
                "JFW" to 0b101_1000.shl(25),
                "JBW" to 0b110_1000.shl(25)
        ))
    }






    private fun composeJMP(offset: Int) = opcodes["JMP"]!! or offset


    private fun debug(any: Any?) { if (true) { println(any) } }

    var flagSpecifyJMP = false
    var flagSpecifyJMPLocation = -1


    private fun putLabel(name: String, pointer: Int) {
        val name = labelMarker + name.toLowerCase()
        if (labelTable[name] != null && labelTable[name] != pointer) {
            throw Error("Labeldef conflict for $name -- old: ${labelTable[name]}, new: $pointer")
        }
        else {
            if (labelTable[name] == null) debug("->> put label '$name' with pc $pointer")
            labelTable[name] = pointer
        }
    }

    private fun getLabel(marked_labelname: String): Int {
        val name = marked_labelname.toLowerCase()
        return labelTable[name] ?: throw Error("Label '$name' not defined")
    }

    private fun splitLines(program: String): List<String> {
        val lines = ArrayList<String>()
        val sb = StringBuilder()

        fun split() {
            var str = sb.toString()

            // preprocess some
            // remove prepending whitespace
            str = str.replace(prependedSpaces, "")

            lines.add(str)
            sb.setLength(0)
        }

        var literalMode = false
        var commentMode = false
        var charCtr = 0
        while (charCtr < program.length) {
            val char = program[charCtr]
            val charNext = if (charCtr < program.lastIndex) program[charCtr + 1] else null

            if (!literalMode && !commentMode) {
                if (char == literalMarker) {
                    sb.append(literalMarker)
                    literalMode = true
                }
                else if (char == commentMarker) {
                    commentMode = true
                }
                else if (char == lineEndMarker) {
                    split()
                }
                else if (!char.toString().matches(regexWhitespaceNoSP)) {
                    sb.append(char)
                }
            }
            else if (!commentMode) {
                if (char == literalMarker && charNext == lineEndMarker) { // quote end must be ";
                    sb.append(literalMarker)
                    split()
                    literalMode = false
                }
                else {
                    sb.append(char)
                }
            }
            else { // comment mode
                if (char == '\n') {
                    commentMode = false
                }
            }

            charCtr++
        }

        return lines
    }

    private fun resetStatus() {
        labelTable.clear()
    }

    fun assemblyToOpcode(line: String): IntArray {
        val words = line.split(delimiters)
        val cmd = words[0].toUpperCase()


        if (opcodes[cmd] == null) {
            throw Error("Invalid assembly: $cmd")
        }

        var resultingOpcode = opcodes[cmd]!!
        val arguments = getOpArgs(resultingOpcode)

        // check if user had provided right number of arguments
        if (words.size != arguments.length + 1) {
            throw IllegalArgumentException("Number of arguments doesn't match -- expected ${arguments.length}, got ${words.size - 1}")
        }

        // for each arguments the operation requires... (e.g. "rrr", "rb")
        arguments.forEachIndexed { index, c ->
            val word = words[index + 1]

            if (c == 'f') { // 'f' is only used for LOADWORDI (arg: rf), which outputs TWO opcodes
                val fullword = word.resolveInt()
                val lowhalf = fullword.and(0xFFFF)
                val highhalf = fullword.ushr(16)

                val loadwordiOp = intArrayOf(resultingOpcode, resultingOpcode or 0x10000)
                loadwordiOp[0] = loadwordiOp[0] or lowhalf
                loadwordiOp[1] = loadwordiOp[1] or highhalf


                debug("$line\t-> ${loadwordiOp[0].toString(2).padStart(32, '0')}")
                debug("$line\t-> ${loadwordiOp[1].toString(2).padStart(32, '0')}")


                return loadwordiOp
            }
            else {
                resultingOpcode = resultingOpcode or when (c) {
                    'r' -> word.toRegInt().shl(22 - 3 * index)
                    'b' -> word.resolveInt().and(0xFF)
                    'w' -> word.resolveInt().and(0xFFFF)
                    'a' -> word.resolveInt().and(0x3FFFFF)
                    else -> throw IllegalArgumentException("Unknown argument type: $c")
                }
            }
        }


        debug("$line\t-> ${resultingOpcode.toString(2).padStart(32, '0')}")


        return intArrayOf(resultingOpcode)
    }

    operator fun invoke(userProgram: String): ByteArray {
        resetStatus()


        val ret = ArrayList<Byte>()

        fun getPC() = TerranVM.interruptCount * 4 + ret.size
        fun addBytes(i: Int) {
            i.toLittle().forEach { ret.add(it) }
        }
        fun addBytes(ai: IntArray) {
            ai.forEach { addBytes(it) }
        }
        fun Int.toNextWord() = if (this % 4 == 0) this else this.ushr(2).plus(1).shl(2)


        // pass 1: pre-scan for labels
        debug("\n\n== Pass 1 ==\n\n")
        var virtualPC = TerranVM.interruptCount * 4
        splitLines(userProgram).forEach { lline ->

            var line = lline.replace(Regex("""^ ?[\n]+"""), "") // do not remove  ?, this takes care of spaces prepended on comment marker
            val words = line.split(delimiters)


            if (line.isEmpty() || words.isEmpty()) {
                // NOP
            }
            else if (!line.startsWith(labelMarker)) {

                debug("[TBASASM] line: '$line'")
                //words.forEach { debug("  $it") }


                val cmd = words[0].toUpperCase()


                if (asmSections.contains(cmd)) { // sectioning commands
                    currentSection = cmd
                    // will continue to next statements
                }
                else if (currentSection == ".DATA") { // setup DB

                    // insert JMP instruction that jumps to .code section
                    if (!flagSpecifyJMP) {
                        // write opcode
                        virtualPC += 4
                        flagSpecifyJMP = true
                    }


                    // data syntax:
                    //      type name payload (separated by any delimiters)
                    //      e.g.: string   hai   Hello, world!

                    val type = words[0].toUpperCase()
                    val name = words[1]

                    putLabel(name, virtualPC)
                    // putLabel must be followed by some bytes payload fed to the return array, no gaps in-between

                    when (type) {
                        "STRING" -> {
                            val start = line.indexOf('"') + 1
                            val end = line.lastIndexOf('"')
                            if (end <= start) throw IllegalArgumentException("malformed string declaration syntax -- must be surrounded with pair of \" (double quote)")

                            val data = line.substring(start, end)

                            // write bytes
                            virtualPC += data.toCString().size
                            virtualPC = virtualPC.toNextWord()
                        }
                        "INT", "FLOAT" -> {
                            // write bytes
                            virtualPC += 4
                        }
                        "BYTES" -> {
                            (2..words.lastIndex).forEach {
                                if (words[it].matches(matchInteger) && words[it].resolveInt() in 0..255) { // byte literal
                                    //debug("--> Byte literal payload: ${words[it].toInt()}")
                                    // write bytes
                                    virtualPC += 1
                                }
                                else if (words[it].startsWith(labelMarker)) {
                                    //debug("--> Byte literal payload (label): ${words[it]}")
                                    // write bytes
                                    virtualPC += 4
                                }
                                else {
                                    throw IllegalArgumentException("Illegal byte literal ${words[it]}")
                                }
                            }


                            virtualPC = virtualPC.toNextWord()
                        }
                        else -> throw IllegalArgumentException("Unsupported data type: '$type' (or you missed a semicolon?)")
                    }

                }
                else if (currentSection == ".CODE" || currentSection == ".FUNC") { // interpret codes
                    if (flagSpecifyJMP && currentSection == ".CODE") {
                        flagSpecifyJMP = false
                        // write dest (this PC) at flagSpecifyJMPLocation
                        virtualPC += 4
                    }
                    else if (!flagSpecifyJMP && currentSection == ".FUNC") {
                        // insert JMP instruction that jumps to .code section
                        flagSpecifyJMP = true
                        virtualPC += 4
                    }

                    if (cmd.startsWith(labelDefinitionMarker)) {
                        putLabel(cmd.drop(1).toLowerCase(), virtualPC)
                        // will continue to next statements
                    }
                    else {
                        // sanitise input
                        if (opcodes[cmd] == null) {
                            throw Error("Invalid opcode: $cmd")
                        }

                        // write opcode
                        virtualPC += 4
                    }
                }
            }
            else {
                throw IllegalArgumentException("Invalid line: $line")
            }

        }



        // pass 2: program
        // --> reset flags
        flagSpecifyJMP = false
        // <-- end of reset flags
        debug("\n\n== Pass 2 ==\n\n")
        splitLines(userProgram).forEach { lline ->

            var line = lline.replace(Regex("""^ ?[\n]+"""), "") // do not remove  ?, this takes care of spaces prepended on comment marker
            val words = line.split(delimiters)


            if (line.isEmpty() || words.isEmpty()) {
                // NOP
            }
            else if (!line.startsWith(labelMarker)) {

                debug("[TBASASM] line: '$line'")
                words.forEach { debug("  $it") }


                val cmd = words[0].toUpperCase()


                if (asmSections.contains(cmd)) { // sectioning commands
                    currentSection = cmd
                    // will continue to next statements
                }
                else if (currentSection == ".DATA") { // setup DB

                    // insert JMP instruction that jumps to .code section
                    if (!flagSpecifyJMP) {
                        flagSpecifyJMP = true
                        flagSpecifyJMPLocation = getPC()
                        addBytes(composeJMP(0x3FFFFF))
                    }


                    // data syntax:
                    //      type name payload (separated by any delimiters)
                    //      e.g.: string   hai   Hello, world!

                    val type = words[0].toUpperCase()
                    val name = words[1]

                    putLabel(name, getPC())
                    // putLabel must be followed by some bytes payload fed to the return array, no gaps in-between

                    when (type) {
                        "STRING" -> {
                            debug("->> line: $line")

                            val start = line.indexOf('"') + 1
                            val end = line.lastIndexOf('"')
                            if (end <= start) throw IllegalArgumentException("malformed string declaration syntax -- must be surrounded with pair of \" (double quote)")

                            val data = line.substring(start, end)

                            debug("--> strStart: $start")
                            debug("--> String payload: '$data'")

                            data.toCString().forEach { ret.add(it) }
                            // using toCString(): null terminator is still required as executor requires it (READ_UNTIL_ZERO, literally)

                            // zero filler
                            // 1 -> 3; 3 -> 1; else -> else
                            if (data.length and 1 == 1) {
                                repeat(data.length.and(3) xor 2) { ret.add(0.toByte()) }
                            }
                        }
                        "FLOAT" -> {
                            val number = words[2].toFloat()

                            debug("--> Float payload: '$number'")

                            number.toLittle().forEach { ret.add(it) }
                        }
                        "INT" -> {
                            val int = words[2].toInt()

                            debug("--> Int payload: '$int'")

                            int.toLittle().forEach { ret.add(it) }
                        }
                        "BYTES" -> {
                            val addedBytesSize = words.lastIndex - 2 + 1
                            (2..words.lastIndex).forEach {
                                if (words[it].matches(matchInteger) && words[it].resolveInt() in 0..255) { // byte literal
                                    debug("--> Byte literal payload: ${words[it].resolveInt()}")
                                    addBytes(words[it].resolveInt())
                                }
                                else if (words[it].startsWith(labelMarker)) {
                                    debug("--> Byte literal payload (label): ${words[it]}")
                                    getLabel(words[it]).toLittle().forEach {
                                        ret.add(it)
                                    }
                                }
                                else {
                                    throw IllegalArgumentException("Illegal byte literal ${words[it]}")
                                }
                            }


                            // zero filler
                            // 1 -> 3; 3 -> 1; else -> else
                            if (addedBytesSize and 1 == 1) {
                                repeat(addedBytesSize.and(3) xor 2) { ret.add(0.toByte()) }
                            }
                        }
                        else -> throw IllegalArgumentException("Unsupported data type: '$type' (or you missed a semicolon?)")
                    }

                }
                else if (currentSection == ".CODE" || currentSection == ".FUNC") { // interpret codes
                    if (flagSpecifyJMP && currentSection == ".CODE") {
                        flagSpecifyJMP = false
                        // write dest (this PC) at flagSpecifyJMPLocation
                        val newASM = composeJMP(flagSpecifyJMPLocation.ushr(2)).toLittle()
                        newASM.forEachIndexed { index, byte ->
                            ret[flagSpecifyJMPLocation + index] = byte
                        }
                    }
                    else if (!flagSpecifyJMP && currentSection == ".FUNC") {
                        // insert JMP instruction that jumps to .code section
                        flagSpecifyJMP = true
                        addBytes(composeJMP(flagSpecifyJMPLocation.ushr(2)))
                        // set new jmp location
                        flagSpecifyJMPLocation = getPC()
                    }


                    if (cmd.startsWith(labelDefinitionMarker)) {
                        putLabel(cmd.drop(1).toLowerCase(), getPC())
                        // will continue to next statements
                    }
                    else {
                        addBytes(assemblyToOpcode(line))
                    }
                }
            }
            else {
                throw IllegalArgumentException("Invalid line: $line")
            }

        }


        debug("======== Assembler: Done! ========")

        return ret.toByteArray()
    }

    private fun String.toRegInt() =
            if (this.matches(registerLiteral))
                this[1].toInt() - 49 // "r1" -> 0
            else
                throw IllegalArgumentException()

    private fun String.resolveInt() =
        if (this.startsWith(labelMarker)) // label?
            getLabel(this).ushr(2)
        else if (this.matches(hexLiteral)) // hex?
            this.dropLast(1).toLong(16).toInt() // because what the fuck Kotlin?
        else if (this.matches(matchInteger)) // number?
            this.toLong().toInt() // because what the fuck Kotlin?
        else
            throw IllegalArgumentException("Couldn't convert this to integer: '$this'")

}