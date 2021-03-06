package net.torvald.terranvm.runtime.compiler.cflat

import net.torvald.terranvm.VMOpcodesRISC
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

/**
 * A compiler for C-flat language that compiles into TerranVM Terra Instruction Set.
 *
 * # Disclaimer
 *
 * 0. This compiler, BY NO MEANS, guarantees to implement standard C language; c'mon, $100+ for a standard document?
 * 1. I suck at code and test. Please report bugs!
 * 2. Please move along with my terrible sense of humour.
 *
 * # About C-flat
 *
 * C-flat is a stupid version of C. Everything is global and a word (or byte if it's array).
 *
 * ## New Features
 *
 * - Typeless (everything is a word), non-zero is truthy and zero is falsy
 * - Infinite loop using ```forever``` block. You can still use ```for (;;)```, ```while (true)```
 * - Counted simple loop (without loop counter ref) using ```repeat``` block
 *
 *
 * ## Important Changes from C
 *
 * - All function definition must specify return type, even if the type is ```void```.
 * - ```float``` is IEEE 754 Binary32.
 * - Everything is global
 * - Everything is a word (32-bit)
 * - Everything is ```int```, ```float``` and ```pointer``` at the same time. You decide.
 * - Unary pre- and post- increments/decrements are considered _evil_ and thus prohibited.
 * - Unsigned types are also considered _evil_ and thus prohibited.
 * - Everything except function's local variable is ```extern```, any usage of the keyword will throw error.
 * - Function cannot have non-local variable defined inside, as ```static``` keyword is illegal.
 * - And thus, following keywords will throw error:
 *      - auto, register, volatile (not supported)
 *      - signed, unsigned (prohibited)
 *      - static (no global inside function)
 *      - extern (everything is global)
 * - Assignment does not return shit.
 *
 *
 * ## Issues
 * - FIXME  arithmetic ops will not work with integers, need to autodetect types and slap in ADDINT instead of just ADD
 *
 *
 * Created by minjaesong on 2017-06-04.
 */
object Cflat {

    private val structOpen = '{'
    private val structClose = '}'

    private val parenOpen = '('
    private val parenClose = ')'

    private val preprocessorTokenSep = Regex("""[ \t]+""")

    private val nullchar = 0.toChar()

    private val infiniteLoops = arrayListOf<Regex>(
            Regex("""while\(true\)"""), // whitespaces are filtered on preprocess
            Regex("""for\([\s]*;[\s]*;[\s]*\)""")
    ) // more types of infinite loops are must be dealt with (e.g. while (0xFFFFFFFF < 0x7FFFFFFF))

    private val regexRegisterLiteral = Regex("""^[Rr][0-9]+$""") // same as the assembler
    private val regexBooleanWhole = Regex("""^(true|false)$""")
    private val regexHexWhole = Regex("""^(0[Xx][0-9A-Fa-f_]+?)$""") // DIFFERENT FROM the assembler
    private val regexOctWhole = Regex("""^(0[0-7_]+)$""")
    private val regexBinWhole = Regex("""^(0[Bb][01_]+)$""") // DIFFERENT FROM the assembler
    private val regexFPWhole =  Regex("""^([-+]?[0-9]*[.][0-9]+[eE]*[-+0-9]*[fF]*|[-+]?[0-9]+[.eEfF][0-9+-]*[fF]?)$""") // same as the assembler
    private val regexIntWhole = Regex("""^([-+]?[0-9_]+[Ll]?)$""") // DIFFERENT FROM the assembler

    private fun String.matchesNumberLiteral() = this.matches(regexHexWhole) || this.matches(regexOctWhole) || this.matches(regexBinWhole) || this.matches(regexIntWhole) || this.matches(regexFPWhole)
    private fun String.matchesFloatLiteral() = this.matches(regexFPWhole)
    private fun String.matchesStringLiteral() = this.endsWith(0.toChar())
    private fun generateTemporaryVarName(inst: String, arg1: String, arg2: String) = "$$${inst}_${arg1}_$arg2"
    private fun generateSuperTemporaryVarName(lineNum: Int, inst: String, arg1: String, arg2: String) = "$$${inst}_${arg1}_${arg2}_\$l$lineNum"


    private val regexVarNameWhole = Regex("""^([A-Za-z_][A-Za-z0-9_]*)$""")

    private val regexWhitespaceNoSP = Regex("""[\t\r\n\v\f]""")
    private val regexIndents = Regex("""^ +|^\t+|(?<=\n) +|(?<=\n)\t+""")

    private val digraphs = hashMapOf(
            "<:" to '[',
            ":>" to ']',
            "<%" to '{',
            "%>" to '}',
            "%:" to '#'
    )
    private val trigraphs = hashMapOf(
            "??=" to "#",
            "??/" to "'",
            "??'" to "^",
            "??(" to "[",
            "??)" to "]",
            "??!" to "|",
            "??<" to "{",
            "??>" to "}",
            "??-" to "~"
    )
    private val keywords = hashSetOf(
            // classic C
            "auto","break","case","char","const","continue","default","do","double","else","enum","extern","float",
            "for","goto","if","int","long","register","return","short","signed","static","struct","switch","sizeof", // is an operator
            "typedef","union","unsigned","void","volatile","while",


            // C-flat code blocks
            "forever","repeat"

            // C-flat dropped keywords (keywords that won't do anything/behave differently than C95, etc.):
            //  - auto, register, signed, unsigned, volatile, static: not implemented; WILL THROW ERROR
            //  - float: will act same as double
            //  - extern: everthing is global, anyway; WILL THROW ERROR

            // C-flat exclusive keywords:
            //  - bool, true, false: bool algebra
    )
    private val unsupportedKeywords = hashSetOf(
            "auto","register","signed","unsigned","volatile","static",
            "extern",
            "long", "short", "double", "bool"
    )
    private val operatorsHierarchyInternal = arrayOf(
            // opirator precedence in internal format (#_nameinlowercase)  PUT NO PARENS HERE!   TODO [ ] are allowed? pls chk
            // most important
            hashSetOf("++","--","[", "]",".","->"),
            hashSetOf("#_preinc","#_predec","#_unaryplus","#_unaryminus","!","~","#_ptrderef","#_addressof","sizeof","(char *)","(short *)","(int *)","(long *)","(float *)","(double *)","(bool *)","(void *)", "(char)","(short)","(int)","(long)","(float)","(double)","(bool)"),
            hashSetOf("*","/","%"),
            hashSetOf("+","-"),
            hashSetOf("<<",">>",">>>"),
            hashSetOf("<","<=",">",">="),
            hashSetOf("==","!="),
            hashSetOf("&"),
            hashSetOf("^"),
            hashSetOf("|"),
            hashSetOf("&&"),
            hashSetOf("||"),
            hashSetOf("?",":"),
            hashSetOf("=","+=","-=","*=","/=","%=","<<=",">>=","&=","^=","|="),
            hashSetOf(",")
            // least important
    ).reversedArray() // this makes op with highest precedence have bigger number
    // operators must return value when TREE is evaluated -- with NO EXCEPTION; '=' must return value too! (not just because of C standard, but design of #_assignvar)
    private val unaryOps = hashSetOf(
            "++","--",
            "#_preinc","#_predec","#_unaryplus","#_unaryminus","!","~","#_ptrderef","#_addressof","sizeof","(char *)","(short *)","(int *)","(long *)","(float *)","(double *)","(bool *)","(void *)", "(char)","(short)","(int)","(long)","(float)","(double)","(bool)"
    )
    private val operatorsHierarchyRTL = arrayOf(
            false,
            true,
            false,false,false,false,false,false,false,false,false,false,
            true,true,
            false
    )
    private val operatorsNoOrder = HashSet<String>()
    init {
        operatorsHierarchyInternal.forEach { array ->
            array.forEach { word -> operatorsNoOrder.add(word) }
        }
    }
    private val splittableTokens = arrayOf( // order is important!
            "<<=",">>=","...",
            "++","--","&&","||","<<",">>","->","<=",">=","==","!=","+=","-=","*=","/=","%=","&=","^=","|=",
            "<",">","^","|","?",":","=",",",".","+","-","!","~","*","&","/","%","(",")",
            " "
    )
    private val argumentDefBadTokens = splittableTokens.toMutableList().minus(",").minus("*").minus("...").toHashSet()
    private val evilOperators = hashSetOf(
            "++","--"
    )
    private val funcAnnotations = hashSetOf(
            "auto", // does nothing; useless even in C (it's derived from B language, actually)
            "extern" // not used in C-flat
    )
    private val funcTypes = hashSetOf(
            "char", "short", "int", "long", "float", "double", "bool", "void"
    )
    private val varAnnotations = hashSetOf(
            "auto", // does nothing; useless even in C (it's derived from B language, actually)
            "extern", // not used in C-flat
            "const",
            "register" // not used in C-flat
    )
    private val varTypes = hashSetOf(
            "struct", "char", "short", "int", "long", "float", "double", "bool", "var", "val"
    )
    private val validFuncPreword = (funcAnnotations + funcTypes).toHashSet()
    private val validVariablePreword = (varAnnotations + varTypes).toHashSet()
    private val codeBlockKeywords = hashSetOf(
            "do", "else", "enum", "for", "if", "struct", "switch", "union", "while", "forever", "repeat"
    )
    private val functionalKeywordsWithOneArg = hashSetOf(
            "goto", "return"
    )
    private val functionalKeywordsNoArg = hashSetOf(
            "break", "continue",
            "return" // return nothing
    )
    private val preprocessorKeywords = hashSetOf(
            "#include","#ifndef","#ifdef","#define","#if","#else","#elif","#endif","#undef","#pragma"
    )
    private val escapeSequences = hashMapOf<String, Char>(
            """\a""" to 0x07.toChar(), // Alert (Beep, Bell)
            """\b""" to 0x08.toChar(), // Backspace
            """\f""" to 0x0C.toChar(), // Formfeed
            """\n""" to 0x0A.toChar(), // Newline (Line Feed)
            """\r""" to 0x0D.toChar(), // Carriage Return
            """\t""" to 0x09.toChar(), // Horizontal Tab
            """\v""" to 0x0B.toChar(), // Vertical Tab
            """\\""" to 0x5C.toChar(), // Backslash
            """\'""" to 0x27.toChar(), // Single quotation mark
            """\"""" to 0x22.toChar(), // Double quotation mark
            """\?""" to 0x3F.toChar()  // uestion mark (used to avoid trigraphs)
    )
    private val builtinFunctions = hashSetOf(
            "#_declarevar" // #_declarevar(SyntaxTreeNode<RawString> varname, SyntaxTreeNode<RawString> vartype)
    )
    private val functionWithSingleArgNoParen = hashSetOf(
            "return", "goto", "comefrom"
    )
    private val compilerInternalFuncArgsCount = hashMapOf(
            "#_declarevar" to 2,
            "endfuncdef" to 1,
            "=" to 2,

            "endif" to 0,
            "endelse" to 0
    )


    /* Error messages */

    val errorUndeclaredVariable = "Undeclared variable"
    val errorIncompatibleType = "Incompatible type(s)"
    val errorRedeclaration = "Redeclaration"


    fun sizeofPrimitive(type: String) = when (type) {
        "char" -> 1
        "short" -> 2
        "int" -> 4
        "long" -> 8
        "float" -> 4
        "double" -> 8
        "bool" -> 1
        "void" -> 1 // GCC feature
        else -> throw IllegalArgumentException("Unknown primitive type: $type")
    }

    private val functionsImplicitEnd = hashSetOf(
            "if", "else", "for", "while", "switch"
    )

    private val exprToIR = hashMapOf(
            "#_declarevar" to "DECLARE",
            "return" to "RETURN",

            "+" to "ADD",
            "-" to "SUB",
            "*" to "MUL",
            "/" to "DIV",
            "^" to "POW",
            "%" to "MOD",

            "<<" to "SHL",
            ">>" to "SHR",
            ">>>" to "USHR",
            "and" to "AND",
            "or" to "OR",
            "xor" to "XOR",
            "not" to "NOT",

            "=" to "ASSIGN",

            "==" to "ISEQ",
            "!=" to "ISNEQ",
            ">" to "ISGT",
            "<" to "ISLS",
            ">=" to "ISGTEQ",
            "<=" to "ISLSEQ",

            "if" to "IF",
            "endif" to "ENDIF",
            "else" to "ELSE",
            "endelse" to "ENDELSE",

            "goto" to "GOTOLABEL",
            "comefrom" to "DEFLABEL",

            "asm" to "INLINEASM",

            "funcdef" to "FUNCDEF",
            "endfuncdef" to "ENDFUNCDEF",

            "stackpush" to "STACKPUSH"
    )

    private val irCmpInst = hashSetOf(
            "ISEQ_II", "ISEQ_IF", "ISEQ_FI", "ISEQ_FF",
            "ISNEQ_II", "ISNEQ_IF", "ISNEQ_FI", "ISNEQ_FF",
            "ISGT_II", "ISGT_IF", "ISGT_FI", "ISGT_FF",
            "ISLS_II", "ISLS_IF", "ISLS_FI", "ISLS_FF",
            "ISGTEQ_II", "ISGTEQ_IF", "ISGTEQ_FI", "ISGTEQ_FF",
            "ISLSEQ_II", "ISLSEQ_IF", "ISLSEQ_FI", "ISLSEQ_FF"
    )

    private val jmpCommands = hashSetOf(
            "JMP", "JZ", "JNZ", "JGT", "JLS"
    )






    // compiler options
    var useDigraph = true
    var useTrigraph = false
    var errorIncompatibles = true

    operator fun invoke(
            program: String,
            // options
            useDigraph: Boolean = false,
            useTrigraph: Boolean = false,
            errorIncompatible: Boolean = true
    ) {
        this.useDigraph = useDigraph
        this.useTrigraph = useTrigraph
        this.errorIncompatibles = errorIncompatible


        //val tree = tokenise(preprocess(program))
        TODO()
    }


    private val structDict = ArrayList<CStruct>()
    private val structNameDict = ArrayList<String>()
    private val funcDict = ArrayList<CFunction>()
    private val funcNameDict = ArrayList<String>()
    private val varDict = HashSet<CData>()
    private val varNameDict = HashSet<String>()


    private val includesUser = HashSet<String>()
    private val includesLib = HashSet<String>()


    private fun getFuncByName(name: String): CFunction? {
        funcDict.forEach {
            if (it.name == name) return it
        }
        return null
    }
    private fun structSearchByName(name: String): CStruct? {
        structDict.forEach {
            if (it.name == name) return it
        }
        return null
    }


    fun preprocess(program: String): String {
        var program = program
                //.replace(regexIndents, "") // must come before regexWhitespaceNoSP
                //.replace(regexWhitespaceNoSP, "")

        var out = StringBuilder()

        if (useTrigraph) {
            trigraphs.forEach { from, to ->
                program = program.replace(from, to)
            }
        }

        val rules = PreprocessorRules()


        // Scan thru line by line (assuming single command per line...?)
        program.lines().forEach {
            if (it.startsWith('#')) {
                val tokens = it.split(preprocessorTokenSep)
                val cmd = tokens[0].drop(1).toLowerCase()


                when (cmd) {
                    "include" -> TODO("Preprocessor keyword 'include'")
                    "define" -> rules.addDefinition(tokens[1], tokens.subList(2, tokens.size).joinToString(" "))
                    "undef" -> rules.removeDefinition(tokens[1])
                    else -> throw UndefinedStatement("Preprocessor macro '$cmd' is not supported.")
                }
            }
            else {
                // process each line according to rules
                var line = it
                rules.forEachKeywordForTokens { replaceRegex, replaceWord ->
                    line = line.replace(replaceRegex, " $replaceWord ")
                }

                out.append("$line\n")
            }
        }


        println(out.toString())

        return out.toString()
    }

    /** No preprocessor should exist at this stage! */
    fun tokenise(program: String): ArrayList<LineStructure> {
        fun debug1(any: Any) { if (true) println(any) }

        ///////////////////////////////////
        // STEP 0. Divide things cleanly //
        ///////////////////////////////////
        // a.k.a. tokenise properly e.g. {extern int foo ( int initSize , SomeStruct strut , )} or {int foo = getch ( ) * ( ( num1 + num3 % 16 ) - 1 )}

        val lineStructures = ArrayList<LineStructure>()
        var currentProgramLineNumber = 1
        var currentLine = LineStructure(currentProgramLineNumber, 0, ArrayList<String>())


        // put things to lineStructure, kill any whitespace
        val sb = StringBuilder()
        var charCtr = 0
        var structureDepth = 0
        fun splitAndMoveAlong() {
            if (sb.isNotEmpty()) {
                if (errorIncompatibles && unsupportedKeywords.contains(sb.toString())) {
                    throw IllegalTokenException("at line $currentProgramLineNumber with token '$sb'")
                }

                debug1("!! split: depth $structureDepth, word '$sb'")

                currentLine.depth = structureDepth // !important
                currentLine.tokens.add(sb.toString())
                sb.setLength(0)
            }
        }
        fun gotoNewline() {
            if (currentLine.tokens.isNotEmpty()) {
                lineStructures.add(currentLine)
                sb.setLength(0)
                currentLine = LineStructure(currentProgramLineNumber, -1337, ArrayList<String>())
            }
        }
        var forStatementEngaged = false // to filter FOR range semicolon from statement-end semicolon
        var isLiteralMode = false // ""  ''
        var isCharLiteral = false
        var isLineComment = false
        var isBlockComment = false
        while (charCtr < program.length) {
            var char = program[charCtr]

            var lookahead4 = program.substring(charCtr, minOf(charCtr + 4, program.length)) // charOfIndex {0, 1, 2, 3}
            var lookahead3 = program.substring(charCtr, minOf(charCtr + 3, program.length)) // charOfIndex {0, 1, 2}
            var lookahead2 = program.substring(charCtr, minOf(charCtr + 2, program.length)) // charOfIndex {0, 1}
            var lookbehind2 = program.substring(maxOf(charCtr - 1, 0), charCtr + 1) // charOfIndex {-1, 0}


            // count up line num
            if (char == '\n' && !isCharLiteral && !isLiteralMode) {
                currentProgramLineNumber += 1
                currentLine.lineNum = currentProgramLineNumber

                if (isLineComment) isLineComment = false
            }
            else if (char == '\n' && isLiteralMode) {
                //throw SyntaxError("at line $currentProgramLineNumber -- line break used inside of string literal")

                // ignore \n by doing nothing
            }
            else if (lookahead2 == "//" && !isLineComment) {
                isLineComment = true
                charCtr += 1
            }
            else if (!isBlockComment && lookahead2 == "/*") {
                isBlockComment = true
                charCtr += 1
            }
            else if (!isBlockComment && lookahead2 == "*/") {
                isBlockComment = false
                charCtr += 1
            }
            else if (!isLiteralMode && !isCharLiteral && !isBlockComment && !isLineComment && char.toString().matches(regexWhitespaceNoSP)) {
                // do nothing
            }
            else if (!isLiteralMode && !isCharLiteral && !isBlockComment && !isLineComment) {
                // replace digraphs
                if (useDigraph && digraphs.containsKey(lookahead2)) { // replace digraphs
                    char = digraphs[lookahead2]!!
                    lookahead4 = char + lookahead4.substring(0..lookahead4.lastIndex)
                    lookahead3 = char + lookahead3.substring(0..lookahead3.lastIndex)
                    lookahead2 = char + lookahead2.substring(0..lookahead2.lastIndex)
                    lookbehind2 = lookbehind2.substring(0..lookahead2.lastIndex - 1) + char
                    charCtr += 1
                }


                // filter shits
                if (lookahead2 == "//" || lookahead2 == "/*" || lookahead2 == "*/") {
                    throw SyntaxError("at line $currentProgramLineNumber -- illegal token '$lookahead2'")
                }


                // do the real jobs
                if (char == structOpen) {
                    debug1("!! met structOpen at line $currentProgramLineNumber")

                    splitAndMoveAlong()
                    gotoNewline()
                    structureDepth += 1 // must go last, because of quirks with 'codeblock{' and 'codeblock  {'
                }
                else if (char == structClose) {
                    debug1("!! met structClose at line $currentProgramLineNumber")

                    structureDepth -= 1 // must go first
                    splitAndMoveAlong()
                    gotoNewline()
                }
                // double quotes
                else if (char == '"' && lookbehind2[0] != '\\') {
                    isLiteralMode = !isLiteralMode
                    sb.append(char)
                }
                // char literal
                else if (!isCharLiteral && char == '\'' && lookbehind2[0] != '\'') {
                    if ((lookahead4[1] == '\\' && lookahead4[3] != '\'') || (lookahead4[1] != '\\' && lookahead4[2] != '\''))
                        throw SyntaxError("Illegal usage of char literal")
                    isCharLiteral = !isCharLiteral
                }
                // -- TODO -- FOR statement is now a special case
                else if (!forStatementEngaged && char == ';') {
                    splitAndMoveAlong()
                    gotoNewline()
                }
                else {
                    if (splittableTokens.contains(lookahead3)) { // three-char operator
                        splitAndMoveAlong() // split previously accumulated word

                        sb.append(lookahead3)
                        splitAndMoveAlong()
                        charCtr += 2
                    }
                    else if (splittableTokens.contains(lookahead2)) { // two-char operator
                        splitAndMoveAlong() // split previously accumulated word

                        if (evilOperators.contains(lookahead2)) {
                            throw IllegalTokenException("at line $currentProgramLineNumber -- evil operator '$lookahead2'")
                        }
                        sb.append(lookahead2)
                        splitAndMoveAlong()
                        charCtr += 1
                    }
                    else if (splittableTokens.contains(char.toString())) { // operator and ' '
                        if (char == '.') { // struct reference or decimal point, depending on the context
                            // it's decimal if:
                            // .[number]
                            // \.e[+-]?[0-9]+   (exponent)
                            // [number].[ fF]?
                            // spaces around decimal points are NOT ALLOWED
                            if (lookahead2.matches(Regex("""\.[0-9]""")) or
                                    lookahead4.matches(Regex("""\.e[+-]?[0-9]+""")) or
                                    (lookbehind2.matches(Regex("""[0-9]+\.""")) and lookahead2.matches(Regex("""\.[ Ff,)]""")))
                            ) {
                                // get match length
                                var charHolder: Char
                                // we don't need travel back because 'else' clause on the far bottom have been already putting numbers into the stringBuilder

                                var travelForth = 0
                                do {
                                    travelForth += 1
                                    charHolder = program[charCtr + travelForth]
                                } while (charHolder in '0'..'9' || charHolder.toString().matches(Regex("""[-+eEfF]""")))


                                val numberWord = program.substring(charCtr..charCtr + travelForth - 1)


                                debug1("[C-flat.tokenise] decimal number token: $sb$numberWord, on line $currentProgramLineNumber")
                                sb.append(numberWord)
                                splitAndMoveAlong()


                                charCtr += travelForth - 1
                            }
                            else { // reference call
                                splitAndMoveAlong() // split previously accumulated word

                                debug1("[C-flat.tokenise] splittable token: $char, on line $currentProgramLineNumber")
                                sb.append(char)
                                splitAndMoveAlong()
                            }
                        }
                        else if (char != ' ') {
                            splitAndMoveAlong() // split previously accumulated word

                            debug1("[C-flat.tokenise] splittable token: $char, on line $currentProgramLineNumber")
                            sb.append(char)
                            splitAndMoveAlong()
                        }
                        else { // space detected, split only
                            splitAndMoveAlong()
                        }
                    }
                    else {
                        sb.append(char)
                    }
                }
            }
            else if (isCharLiteral && !isLiteralMode) {
                if (char == '\\') { // escape sequence of char literal
                    sb.append(escapeSequences[lookahead2]!!.toInt())
                    charCtr += 1
                }
                else {
                    sb.append(char.toInt())
                }
            }
            else if (isLiteralMode && !isCharLiteral) {
                if (char == '"' && lookbehind2[0] != '\\') {
                    isLiteralMode = !isLiteralMode
                }

                sb.append(char)
            }
            else {
                // do nothing
            }


            charCtr += 1
        }


        return lineStructures
    }

    val rootNodeName = "cflat_node_root"

    fun buildTree(lineStructures: List<LineStructure>): SyntaxTreeNode {
        fun debug1(any: Any) { if (true) println(any) }


        ///////////////////////////
        // STEP 1. Create a tree //
        ///////////////////////////
        // In this step, we build tree from the line structures parsed by the parser. //

        val ASTroot = SyntaxTreeNode(ExpressionType.FUNCTION_DEF, ReturnType.NOTHING, name = rootNodeName, isRoot = true, lineNumber = 1)

        val workingNodes = Stack<SyntaxTreeNode>()
        workingNodes.push(ASTroot)

        fun getWorkingNode() = workingNodes.peek()



        fun printStackDebug(): String {
            val sb = StringBuilder()

            sb.append("Node stack: [")
            workingNodes.forEachIndexed { index, it ->
                if (index > 0) { sb.append(", ") }
                sb.append("l"); sb.append(it.lineNumber)
            }
            sb.append("]")

            return sb.toString()
        }

        lineStructures.forEachIndexed { index, it -> val (lineNum, depth, tokens) = it
            val nextLineDepth = if (index != lineStructures.lastIndex) lineStructures[index + 1].depth else null

            debug1("buildtree!!  tokens: $tokens")
            debug1("call #$index from buildTree()")
            val nodeBuilt = asTreeNode(lineNum, tokens)
            getWorkingNode().addStatement(nodeBuilt)
            debug1("end call #$index from buildTree()")

            if (nextLineDepth != null) {
                // has code block
                if (nextLineDepth > depth) {
                    workingNodes.push(nodeBuilt)
                }
                // code block escape
                else if (nextLineDepth < depth) {
                    repeat(depth - nextLineDepth) { workingNodes.pop() }
                }
            }


        }

        /////////////////////////////////
        // STEP 1-1. Simplify the tree //
        /////////////////////////////////
        // In this step, we modify the tree so translating to IR2 be easier.        //
        // More specifically, we do:                                                //
        //  1. "If" (cond) {stmt1} "Else" {stmt2} -> "IfElse" (cond) {stmt1, stmt2} //

        ASTroot.traversePreorderStatements { node, _ ->
            // JOB #1
            val ifNodeIndex = node.statements.findAllToIndices { it.name == "if" }
            var deletionCount = 0

            ifNodeIndex.forEach {
                // as we do online-delete, precalculated numbers will go off
                // deletionCount will compensate it.
                val it = it - deletionCount

                val ifNode   = node.statements[it]
                val elseNode = node.statements.getOrNull(it + 1)
                // if filtered IF node has ELSE node appended...
                if (elseNode != null && elseNode.name == "else") {
                    val newNode = SyntaxTreeNode(
                            ifNode.expressionType, ifNode.returnType,
                            "ifelse",
                            ifNode.lineNumber,
                            ifNode.isRoot, ifNode.isPartOfArgumentsNode
                    )

                    if (ifNode.statements.size > 1) throw InternalError("If node contains 2 or more statements!\n[NODE START]\n$node\n[NODE END]")
                    if (elseNode.statements.size > 1) throw InternalError("Else node contains 2 or more statements!\n[NODE START]\n$node\n[NODE END]")

                    ifNode.arguments.forEach { newNode.addArgument(it) } // should contain only 1 arg but oh well
                    newNode.addStatement(ifNode.statements[0])
                    newNode.addStatement(elseNode.statements[0])

                    node.statements[it] = newNode
                    node.statements.removeAt(it + 1)
                    deletionCount += 1
                }
            }
        }


        return ASTroot
    }

    /**
     * @param type "variable", "literal_i", "literal_f"
     * @param value string for variable name, int for integer and float literals
     */
    private data class VirtualStackItem(val type: String, val value: String)

    fun String.isVariable() = this.startsWith('$')
    fun String.isRegister() = this.matches(regexRegisterLiteral)

    fun ArrayList<String>.append(str: String) = if (str.endsWith(';'))
        this.add(str)
    else
        throw IllegalArgumentException("You missed a semicolon for: $str")


    ///////////////////////////////////////////////////
    // publicising things so that they can be tested //
    ///////////////////////////////////////////////////

    fun resolveTypeString(type: String, isPointer: Boolean = false): ReturnType {
        /*val isPointer = type.endsWith('*') or type.endsWith("_ptr") or isPointer

        return when (type) {
            "void" -> if (isPointer) ReturnType.NOTHING_PTR else ReturnType.NOTHING
            "char" -> if (isPointer) ReturnType.CHAR_PTR else ReturnType.CHAR
            "short" -> if (isPointer) ReturnType.SHORT_PTR else ReturnType.SHORT
            "int" -> if (isPointer) ReturnType.INT_PTR else ReturnType.INT
            "long" -> if (isPointer) ReturnType.LONG_PTR else ReturnType.LONG
            "float" -> if (isPointer) ReturnType.FLOAT_PTR else ReturnType.FLOAT
            "double" -> if (isPointer) ReturnType.DOUBLE_PTR else ReturnType.DOUBLE
            "bool" -> if (isPointer) ReturnType.BOOL_PTR else ReturnType.BOOL
            else -> if (isPointer) ReturnType.STRUCT_PTR else ReturnType.STRUCT
        }*/
        return when (type.toLowerCase()) {
            "void" -> ReturnType.NOTHING
            "int" -> ReturnType.INT
            "float" -> ReturnType.FLOAT
            else -> throw SyntaxError("Unknown type: $type")
        }
    }



    fun asTreeNode(lineNumber: Int, tokens: List<String>): SyntaxTreeNode {
        fun splitContainsValidVariablePreword(split: List<String>): Int {
            var ret = -1
            for (stage in 0..minOf(3, split.lastIndex)) {
                if (validVariablePreword.contains(split[stage])) ret += 1
            }
            return ret
        }
        fun debug1(any: Any?) { if (true) println(any) }




        // contradiction: auto AND extern

        debug1("[asTreeNode] tokens: $tokens")

        val firstAssignIndex = tokens.indexOf("=")
        val firstLeftParenIndex = tokens.indexOf("(")
        val lastRightParenIndex = tokens.lastIndexOf(")")

        // special case for FOR :
        //        int i = 0 // i must be declared beforehand !!; think of C < 99
        //        for (i = 2 + (3 * 4), i <= 10, i = i + 2) { // separated by comma, parens are mandatory
        //            dosometing();
        //        }
        if (tokens[0] == "for") {
            // for tokens inside of firstLeftParen..lastRightParen,
            // split tokens by ',' (will result in 3 tokens, may or may not empty)
            // recurse call those three
            //  e.g. forNode.arguments[0] = asTreeNode( ... )
            //       forNode.arguments[1] = asTreeNode( ... )
            //       forNode.arguments[2] = asTreeNode( ... )

            val forNode = SyntaxTreeNode(ExpressionType.FUNCTION_CALL, null, "for", lineNumber)

            val subTokens = tokens.subList(firstLeftParenIndex, lastRightParenIndex)
            val commas = listOf(0, subTokens.indexOf(","), subTokens.lastIndexOf(","), subTokens.size)
            val forArgs = (0..2).map { subTokens.subList(1 + commas[it], commas[it + 1]) }.map { asTreeNode(lineNumber, it) }
            forArgs.forEach {
                forNode.addArgument(it)
            }

            debug1("[asTreeNode] for tree: \n$forNode")

            return forNode
        }

        val functionCallTokens: List<String>? =
                if (firstLeftParenIndex == -1)
                    null
                else if (firstAssignIndex == -1)
                    tokens.subList(0, firstLeftParenIndex)
                else
                    tokens.subList(firstAssignIndex + 1, firstLeftParenIndex)
        val functionCallTokensContainsTokens = if (functionCallTokens == null) false else
            (functionCallTokens.map { if (splittableTokens.contains(it)) 1 else 0 }.sum() > 0)
        // if TRUE, it's not a function call/def (e.g. foobar = funccall ( arg arg arg )


        debug1("!!##[asTreeNode] line $lineNumber; functionCallTokens: $functionCallTokens; contains tokens?: $functionCallTokensContainsTokens")

        /////////////////////////////
        // unwrap (((((parens))))) //
        /////////////////////////////
        // FIXME ( asrtra ) + ( feinov ) forms are errenously stripped its paren away
        /*if (tokens.first() == "(" && tokens.last() == ")") {
            var wrapSize = 1
            while (tokens[wrapSize] == "(" && tokens[tokens.lastIndex - wrapSize] == ")") {
                wrapSize++
            }
            return asTreeNode(lineNumber, tokens.subList(wrapSize, tokens.lastIndex - wrapSize + 1))
        }*/


        debug1("!!##[asTreeNode] input token: $tokens")


        ////////////////////////////
        // as Function Definition //
        ////////////////////////////
        if (!functionCallTokensContainsTokens && functionCallTokens != null && functionCallTokens.size >= 2 && functionCallTokens.size <= 4) { // e.g. int main , StructName fooo , extern void doSomething , extern unsigned StructName uwwse
            val actualFuncType = functionCallTokens[functionCallTokens.lastIndex - 1]
            val returnType = resolveTypeString(actualFuncType)
            val funcName = functionCallTokens.last()

            // get arguments
            // int  *  index  ,  bool  *  *  isSomething  ,  double  someNumber  , ...
            val argumentsDef = tokens.subList(firstLeftParenIndex + 1, lastRightParenIndex)
            val argTypeNamePair = ArrayList<Pair<ReturnType, String?>>()


            debug1("!! func def args")
            debug1("!! <- $argumentsDef")


            // chew it down to more understandable format
            var typeHolder: ReturnType? = null
            var nameHolder: String? = null
            argumentsDef.forEachIndexed { index, token ->
                if (argumentDefBadTokens.contains(token)) {
                    throw IllegalTokenException("at line $lineNumber -- illegal token '$token' used on function argument definition")
                }


                if (token == ",") {
                    if (typeHolder == null) throw SyntaxError("at line $lineNumber -- type not specified")
                    argTypeNamePair.add(typeHolder!! to nameHolder)
                    typeHolder = null
                    nameHolder = null
                }
                else if (token == "*") {
                    if (typeHolder == null) throw SyntaxError("at line $lineNumber -- type not specified")
                    typeHolder = resolveTypeString(typeHolder.toString().toLowerCase(), true)
                }
                else if (typeHolder == null) {
                    typeHolder = resolveTypeString(token)
                }
                else if (typeHolder != null) {
                    nameHolder = token


                    if (index == argumentsDef.lastIndex) {
                        argTypeNamePair.add(typeHolder!! to nameHolder)
                    }
                }
                else {
                    throw InternalError("uncaught shit right there")
                }
            }


            debug1("!! -> $argTypeNamePair")
            debug1("================================")


            val funcDefNode = SyntaxTreeNode(ExpressionType.FUNCTION_DEF, returnType, funcName, lineNumber)
            //if (returnType == ReturnType.STRUCT || returnType == ReturnType.STRUCT_PTR) {
            //    funcDefNode.structName = actualFuncType
            //}

            argTypeNamePair.forEach { val (type, name) = it
                // TODO struct and structName
                val funcDefArgNode = SyntaxTreeNode(ExpressionType.FUNC_ARGUMENT_DEF, type, name, lineNumber, isPartOfArgumentsNode = true)
                funcDefNode.addArgument(funcDefArgNode)
            }


            return funcDefNode
        }
        //////////////////////
        // as Function Call // (also works as keyworded code block (e.g. if, for, while))
        //////////////////////
        else if (tokens.size >= 3 /* foo, (, ); guaranteed to be at least three */ && tokens[1] == "(" &&
                !functionCallTokensContainsTokens && functionCallTokens != null && functionCallTokens.size == 1) { // e.g. if ( , while ( ,
            val funcName = functionCallTokens.last()


            // get arguments
            // complex_statements , ( value = funccall ( arg ) ) , "string,arg" , 42f
            val argumentsDef = tokens.subList(firstLeftParenIndex + 1, lastRightParenIndex)


            debug1("!! func call args:")
            debug1("!! <- $argumentsDef")


            // split into tokens list, splitted by ','
            val functionCallArguments = ArrayList<ArrayList<String>>() // double array is intended (e.g. [["tsrasrat"], ["42"], [callff, (, "wut", )]] for input ("tsrasrat", "42", callff("wut"))
            var tokensHolder = ArrayList<String>()
            argumentsDef.forEachIndexed { index, token ->
                if (index == argumentsDef.lastIndex) {
                    tokensHolder.add(token)
                    functionCallArguments.add(tokensHolder)
                    tokensHolder = ArrayList<String>() // can't reuse; must make new one
                }
                else if (token == ",") {
                    if (tokensHolder.isEmpty()) {
                        throw SyntaxError("at line $lineNumber -- misplaced comma")
                    }
                    else {
                        functionCallArguments.add(tokensHolder)
                        tokensHolder = ArrayList<String>() // can't reuse; must make new one
                    }
                }
                else {
                    tokensHolder.add(token)
                }
            }


            debug1("!! -> $functionCallArguments")


            val funcCallNode = SyntaxTreeNode(ExpressionType.FUNCTION_CALL, null, funcName, lineNumber)

            functionCallArguments.forEach {
                debug1("!! forEach $it")

                debug1("call from asTreeNode().asFunctionCall")
                val argNodeLeaf = asTreeNode(lineNumber, it); argNodeLeaf.isPartOfArgumentsNode = true
                funcCallNode.addArgument(argNodeLeaf)
            }


            debug1("================================")


            return funcCallNode
        }
        ////////////////////////
        // as Var Call / etc. //
        ////////////////////////
        else {
            // filter illegal lines (absurd keyword usage)
            tokens.forEach {
                if (codeBlockKeywords.contains(it)) {
                    // code block without argumenets; give it proper parens and redirect
                    val newTokens = tokens.toMutableList()
                    if (newTokens.size != 1) {
                        throw SyntaxError("Number of tokens is not 1 (got size of ${newTokens.size}): ${newTokens}")
                    }

                    newTokens.add("("); newTokens.add(")")
                    debug1("call from asTreeNode().filterIllegalLines")
                    return asTreeNode(lineNumber, newTokens)
                }
            }

            ///////////////////////
            // Bunch of literals //
            ///////////////////////
            if (tokens.size == 1) {
                val word = tokens[0]


                debug1("!! literal, token: '$word'")
                //debug1("================================")


                // filtered String literals
                if (word.startsWith('"') && word.endsWith('"')) {
                    val leafNode = SyntaxTreeNode(ExpressionType.LITERAL_LEAF, ReturnType.DATABASE, null, lineNumber)
                    leafNode.literalValue = tokens[0].substring(1, tokens[0].lastIndex) + nullchar
                    return leafNode
                }
                // bool literals
                else if (word.matches(regexBooleanWhole)) {
                    val leafNode = SyntaxTreeNode(ExpressionType.LITERAL_LEAF, ReturnType.INT, null, lineNumber)
                    leafNode.literalValue = word == "true"
                    return leafNode
                }
                // hexadecimal literals
                else if (word.matches(regexHexWhole)) {
                    val leafNode = SyntaxTreeNode(
                            ExpressionType.LITERAL_LEAF,
                            ReturnType.INT,
                            null, lineNumber
                    )
                    try {
                        leafNode.literalValue =
                            word.replace(Regex("""[^0-9A-Fa-f]"""), "").toLong(16).and(0xFFFFFFFFL).toInt()
                    }
                    catch (e: NumberFormatException) {
                        throw IllegalTokenException("at line $lineNumber -- $word is too large to be represented as ${leafNode.returnType?.toString()?.toLowerCase()}")
                    }

                    return leafNode
                }
                // octal literals
                else if (word.matches(regexOctWhole)) {
                    val leafNode = SyntaxTreeNode(
                            ExpressionType.LITERAL_LEAF,
                            ReturnType.INT,
                            null, lineNumber
                    )
                    try {
                        leafNode.literalValue =
                            word.replace(Regex("""[^0-7]"""), "").toLong(8).and(0xFFFFFFFFL).toInt()
                    }
                    catch (e: NumberFormatException) {
                        throw IllegalTokenException("at line $lineNumber -- $word is too large to be represented as ${leafNode.returnType?.toString()?.toLowerCase()}")
                    }

                    return leafNode
                }
                // binary literals
                else if (word.matches(regexBinWhole)) {
                    val leafNode = SyntaxTreeNode(
                            ExpressionType.LITERAL_LEAF,
                            ReturnType.INT,
                            null, lineNumber
                    )
                    try {
                        leafNode.literalValue =
                            word.replace(Regex("""[^01]"""), "").toLong(2).and(0xFFFFFFFFL).toInt()
                    }
                    catch (e: NumberFormatException) {
                        throw IllegalTokenException("at line $lineNumber -- $word is too large to be represented as ${leafNode.returnType?.toString()?.toLowerCase()}")
                    }

                    return leafNode
                }
                // int literals
                else if (word.matches(regexIntWhole)) {
                    val leafNode = SyntaxTreeNode(
                            ExpressionType.LITERAL_LEAF,
                            ReturnType.INT,
                            null, lineNumber
                    )
                    try {
                        leafNode.literalValue =
                            word.replace(Regex("""[^0-9]"""), "").toLong().and(0xFFFFFFFFL).toInt()
                    }
                    catch (e: NumberFormatException) {
                        throw IllegalTokenException("at line $lineNumber -- $word is too large to be represented as ${leafNode.returnType?.toString()?.toLowerCase()}")
                    }

                    return leafNode
                }
                // floating point literals
                else if (word.matches(regexFPWhole)) {
                    val leafNode = SyntaxTreeNode(
                            ExpressionType.LITERAL_LEAF,
                            ReturnType.FLOAT,
                            null, lineNumber
                    )
                    try {
                        leafNode.literalValue = if (word.endsWith('F', true))
                            word.slice(0..word.lastIndex - 1).toDouble() // DOUBLE when C-flat; replace it with 'toFloat()' if you're standard C
                        else
                            word.toDouble()
                    }
                    catch (e: NumberFormatException) {
                        throw InternalError("at line $lineNumber, while parsing '$word' as Double")
                    }

                    return leafNode
                }
                //////////////////////////////////////
                // variable literal (VARIABLE_LEAF) // usually function call arguments
                //////////////////////////////////////
                else if (word.matches(regexVarNameWhole)) {
                    val leafNode = SyntaxTreeNode(ExpressionType.VARIABLE_READ, null, word, lineNumber)
                    return leafNode
                }
            }
            else {

                /////////////////////////////////////////////////
                // return something; goto somewhere (keywords) //
                /////////////////////////////////////////////////
                if (tokens[0] == "goto" || tokens[0] == "comefrom") {
                    val nnode = SyntaxTreeNode(
                            ExpressionType.FUNCTION_CALL,
                            null,
                            tokens[0],
                            lineNumber
                    )

                    val rawTreeNode = tokens[1].toRawTreeNode(lineNumber); rawTreeNode.isPartOfArgumentsNode = true
                    nnode.addArgument(rawTreeNode)
                    return nnode
                }
                else if (tokens[0] == "return") {
                    val returnNode = SyntaxTreeNode(
                            ExpressionType.FUNCTION_CALL,
                            null,
                            "return",
                            lineNumber
                    )
                    val node = turnInfixTokensIntoTree(lineNumber, tokens.subList(1, tokens.lastIndex + 1)); node.isPartOfArgumentsNode = true
                    returnNode.addArgument(node)
                    return returnNode
                }

                //////////////////////////
                // variable declaration //
                //////////////////////////
                // extern auto struct STRUCTID foobarbaz
                // extern auto int foobarlulz
                else if (splitContainsValidVariablePreword(tokens) != -1) {
                    val prewordIndex = splitContainsValidVariablePreword(tokens)
                    val realType = tokens[prewordIndex]

                    try {
                        val hasAssignment: Boolean

                        if (realType == "struct")
                            hasAssignment = tokens.lastIndex > prewordIndex + 2
                        else
                            hasAssignment = tokens.lastIndex > prewordIndex + 1


                        // deal with assignment
                        if (hasAssignment) {
                            // TODO support type_ptr_ptr_ptr...

                            // use turnInfixTokensIntoTree and inject it to assignment node
                            val isPtrType = tokens[1] == "*"
                            val typeStr = tokens[0] + if (isPtrType) "_ptr" else ""

                            val tokensWithoutType = if (isPtrType)
                                tokens.subList(2, tokens.size)
                            else
                                tokens.subList(1, tokens.size)

                            val infixNode = turnInfixTokensIntoTree(lineNumber, tokensWithoutType)


                            //#_assignvar(SyntaxTreeNode<RawString> varname, SyntaxTreeNode<RawString> vartype, SyntaxTreeNode value)

                            val returnNode = SyntaxTreeNode(ExpressionType.FUNCTION_CALL, ReturnType.NOTHING, "#_assignvar", lineNumber)

                            val nameNode = tokensWithoutType.first().toRawTreeNode(lineNumber); nameNode.isPartOfArgumentsNode = true
                            returnNode.addArgument(nameNode)

                            val typeNode = typeStr.toRawTreeNode(lineNumber); typeNode.isPartOfArgumentsNode = true
                            returnNode.addArgument(typeNode)

                            infixNode.isPartOfArgumentsNode = true
                            returnNode.addArgument(infixNode)

                            return returnNode
                        }
                        else {
                            // #_declarevar(SyntaxTreeNode<RawString> varname, SyntaxTreeNode<RawString> vartype)

                            val leafNode = SyntaxTreeNode(ExpressionType.INTERNAL_FUNCTION_CALL, ReturnType.NOTHING, "#_declarevar", lineNumber)

                            val valueNode = tokens[1].toRawTreeNode(lineNumber); valueNode.isPartOfArgumentsNode = true
                            leafNode.addArgument(valueNode)

                            val typeNode = tokens[0].toRawTreeNode(lineNumber); typeNode.isPartOfArgumentsNode = true
                            leafNode.addArgument(typeNode)

                            return leafNode
                        }
                    }
                    catch (syntaxFuck: ArrayIndexOutOfBoundsException) {
                        throw SyntaxError("at line $lineNumber -- missing statement(s)")
                    }
                }
                else {
                    debug1("!! infix in: $tokens")

                    // infix notation
                    return turnInfixTokensIntoTree(lineNumber, tokens)
                }
                TODO()
            } // end if (tokens.size == 1)


            TODO()
        }
    }

    fun turnInfixTokensIntoTree(lineNumber: Int, tokens: List<String>): SyntaxTreeNode {
        // based on https://stackoverflow.com/questions/1946896/conversion-from-infix-to-prefix

        // FIXME: differentiate parens for function call from grouping

        fun debug(any: Any) { if (true) println(any) }


        fun precedenceOf(token: String): Int {
            if (token == "(" || token == ")") return -1

            operatorsHierarchyInternal.forEachIndexed { index, hashSet ->
                if (hashSet.contains(token)) return index
            }

            throw SyntaxError("[infix-to-tree] at $lineNumber -- unknown operator '$token'")
        }

        val tokens = tokens.reversed()


        val stack = Stack<String>()
        val treeArgsStack = Stack<Any>()

        fun addToTree(token: String) {
            debug("[infix-to-tree] adding '$token'")

            fun argsCountOf(operator: String) = if (unaryOps.contains(operator)) 1 else 2
            fun popAsTree(): SyntaxTreeNode {
                val rawElem = treeArgsStack.pop()

                if (rawElem is String) {
                    debug("[infix-to-tree] call from turnInfixTokensIntoTree().addToTree().popAsTree()")
                    return asTreeNode(lineNumber, listOf(rawElem))
                }
                else if (rawElem is SyntaxTreeNode)
                    return rawElem
                else
                    throw InternalError("I said you to put String or SyntaxTreeNode only; what's this? ${rawElem.javaClass.simpleName}?")
            }


            if (!operatorsNoOrder.contains(token)) {
                debug("-> not a operator; pushing to args stack")
                treeArgsStack.push(token)
            }
            else {
                debug("-> taking ${argsCountOf(token)} value(s) from stack")

                val treeNode = SyntaxTreeNode(ExpressionType.FUNCTION_CALL, null, token, lineNumber)
                repeat(argsCountOf(token)) {
                    val poppedTree = popAsTree(); poppedTree.isPartOfArgumentsNode = true
                    treeNode.addArgument(poppedTree)
                }
                treeArgsStack.push(treeNode)
            }
        }


        debug("[infix-to-tree] reversed tokens: $tokens")


        tokens.forEachIndexed { index, rawToken ->
            // contextually decide what is real token
            val token =
                    // if prev token is operator (used '+' as token list is reversed)
                    if (index == tokens.lastIndex || operatorsNoOrder.contains(tokens[index + 1])) {
                        if (rawToken == "+") "#_unaryplus"
                        else if (rawToken == "-") "#_unaryminus"
                        else if (rawToken == "&") "#_addressof"
                        else if (rawToken == "*") "#_ptrderef"
                        else if (rawToken == "++") "#_preinc"
                        else if (rawToken == "--") "#_predec"
                        else rawToken
                    }
                    else rawToken


            if (token == ")") {
                stack.push(token)
            }
            else if (token == "(") {
                while (stack.isNotEmpty()) {
                    val t = stack.pop()
                    if (t == ")") break

                    addToTree(t)
                }
            }
            else if (!operatorsNoOrder.contains(token)) {
                addToTree(token)
            }
            else {
                // XXX: associativity should be considered here
                // https://en.wikipedia.org/wiki/Operator_associativity
                while (stack.isNotEmpty() && precedenceOf(stack.peek()) > precedenceOf(token)) {
                    addToTree(stack.pop())
                }
                stack.add(token)
            }
        }

        while (stack.isNotEmpty()) {
            addToTree(stack.pop())
        }


        if (treeArgsStack.size != 1) {
            throw InternalError("Stack size is wrong -- supposed to be 1, but it's ${treeArgsStack.size}\nstack: $treeArgsStack")
        }
        debug("[infix-to-tree] finalised tree:\n${treeArgsStack.peek()}")


        return if (treeArgsStack.peek() is SyntaxTreeNode)
                treeArgsStack.peek() as SyntaxTreeNode
        else {
            debug("[infix-to-tree] call from turnInfixTokensIntoTree().if (treeArgsStack.peek() is SyntaxTreeNode).else")
            asTreeNode(lineNumber, listOf(treeArgsStack.peek() as String))
        }
    }



    data class LineStructure(var lineNum: Int, var depth: Int, val tokens: MutableList<String>)

    class SyntaxTreeNode(
            val expressionType: ExpressionType,
            val returnType: ReturnType?, // STATEMENT, LITERAL_LEAF: valid ReturnType; VAREABLE_LEAF: always null
            var name: String?,
            val lineNumber: Int, // used to generate error message
            val isRoot: Boolean = false,
            //val derefDepth: Int = 0 // how many ***s are there for pointer
            var isPartOfArgumentsNode: Boolean = false
    ) {

        var literalValue: Any? = null // for LITERALs only
        var structName: String? = null // for STRUCT return type

        val arguments = ArrayList<SyntaxTreeNode>() // for FUNCTION, CODE_BLOCK
        val statements = ArrayList<SyntaxTreeNode>()

        var depth: Int? = null

        fun addArgument(node: SyntaxTreeNode) {
            arguments.add(node)
        }
        fun addStatement(node: SyntaxTreeNode) {
            statements.add(node)
        }


        fun updateDepth() {
            if (!isRoot) throw Error("Updating depth only make sense when used as root")

            this.depth = 0

            arguments.forEach { it._updateDepth(1) }
            statements.forEach { it._updateDepth(1) }
        }

        private fun _updateDepth(recursiveDepth: Int) {
            this.depth = recursiveDepth

            arguments.forEach { it._updateDepth(recursiveDepth + 1) }
            statements.forEach { it._updateDepth(recursiveDepth + 1) }
        }

        fun expandImplicitEnds() {
            if (!isRoot) throw Error("Expanding implicit 'end's only make sense when used as root")

            // fixme no nested ifs
            statements.forEach { it.statements.forEach { it._expandImplicitEnds() } } // root level if OF FUNCDEF
            statements.forEach { it._expandImplicitEnds() } // root level if
        }

        private fun _expandImplicitEnds() {
            if (this.name in functionsImplicitEnd) {
                this.statements.add(SyntaxTreeNode(
                        ExpressionType.INTERNAL_FUNCTION_CALL, null, "end${this.name}", this.lineNumber, this.isRoot
                ))
            }
            else if (this.expressionType == ExpressionType.FUNCTION_DEF) {
                val endfuncdef = SyntaxTreeNode(
                        ExpressionType.INTERNAL_FUNCTION_CALL, null, "endfuncdef", this.lineNumber, this.isRoot
                )
                endfuncdef.addArgument(SyntaxTreeNode(ExpressionType.FUNC_ARGUMENT_DEF, null, this.name!!, this.lineNumber, isPartOfArgumentsNode = true))
                this.statements.add(endfuncdef)
            }
        }

        val isLeaf: Boolean
            get() = expressionType.toString().endsWith("_LEAF") ||
                    (arguments.isEmpty() && statements.isEmpty())

        override fun toString() = toStringRepresentation(0)

        private fun toStringRepresentation(depth: Int): String {
            val header = "│ ".repeat(depth) + if (isRoot) "⧫AST (name: $name)" else if (isLeaf) "◊AST$depth (name: $name)" else "☐AST$depth (name: $name)"
            val lines = arrayListOf(
                    header,
                    "│ ".repeat(depth+1) + "ExprType : $expressionType",
                    "│ ".repeat(depth+1) + "RetnType : $returnType",
                    "│ ".repeat(depth+1) + "LiteralV : '$literalValue'",
                    "│ ".repeat(depth+1) + "isArgNod : $isPartOfArgumentsNode"
            )

            if (!isLeaf) {
                lines.add("│ ".repeat(depth+1) + "# of arguments: ${arguments.size}")
                arguments.forEach { lines.add(it.toStringRepresentation(depth + 1)) }
                lines.add("│ ".repeat(depth+1) + "# of statements: ${statements.size}")
                statements.forEach { lines.add(it.toStringRepresentation(depth + 1)) }
            }

            lines.add("│ ".repeat(depth) + "╘" + "═".repeat(header.length - 1 - 2*depth))

            val sb = StringBuilder()
            lines.forEachIndexed { index, line ->
                sb.append(line)
                if (index < lines.lastIndex) { sb.append("\n") }
            }

            return sb.toString()
        }


        private fun traverseStmtOnly(node: SyntaxTreeNode, action: (SyntaxTreeNode, Int) -> Unit, depth: Int = 0) {
            //if (node == null) return
            action(node, depth)
            node.statements.forEach { traverseStmtOnly(it, action, depth + 1) }
        }

        fun traversePreorderStatements(action: (SyntaxTreeNode, Int) -> Unit) {
            this.traverseStmtOnly(this, action)
        }
    }

    private fun String.toRawTreeNode(lineNumber: Int): SyntaxTreeNode {
        val node = SyntaxTreeNode(ExpressionType.LITERAL_LEAF, ReturnType.DATABASE, null, lineNumber)
        node.literalValue = this
        return node
    }

    inline fun <T> Iterable<T>.findAllToIndices(predicate: (T) -> Boolean): IntArray {
        val indices = ArrayList<Int>()

        this.forEachIndexed { index, t ->
            if (predicate(t))
                indices.add(index)
        }

        return indices.toIntArray()
    }

    enum class ExpressionType {
        FUNCTION_DEF, FUNC_ARGUMENT_DEF, // expect Arguments and Statements

        INTERNAL_FUNCTION_CALL,
        FUNCTION_CALL, // expect Arguments and Statements
        // the case of OPERATOR CALL //
        // returnType: variable type; name: "="; TODO add description for STRUCT
        // arg0: name of the variable (String)
        // arg1: (optional) assigned value, either LITERAL or FUNCTION_CALL or another OPERATOR CALL
        // arg2: (if STRUCT) struct identifier (String)

        LITERAL_LEAF, // literals, also act as a leaf of the tree; has returnType of null
        VARIABLE_WRITE, // CodeL; loads memory address to be written onto
        VARIABLE_READ   // CodeR; the actual value stored in its memory address
    }
    enum class ReturnType {
        INT, FLOAT,
        NOTHING, // null
        DATABASE // array of bytes, also could be String
    }


    class PreprocessorRules {
        private val kwdRetPair = HashMap<String, String>()

        fun addDefinition(keyword: String, ret: String) {
            kwdRetPair[keyword] = ret
        }
        fun removeDefinition(keyword: String) {
            kwdRetPair.remove(keyword)
        }
        fun forEachKeywordForTokens(action: (String, String) -> Unit) {
            kwdRetPair.forEach { key, value ->
                action("""[ \t\n]+""" + key + """(?=[ \t\n;]+)""", value)
            }
        }
    }

    /**
     * Notation rules:
     * - Variable: prepend with '$' (e.g. $duplicantsCount) // totally not a Oxygen Not Included reference
     * - Register: prepend with 'r' (e.g. r3)
     */
    data class IntermediateRepresentation(
            var lineNum: Int,
            var instruction: String = "DUMMY",
            var arg1: String? = null,
            var arg2: String? = null,
            var arg3: String? = null,
            var arg4: String? = null,
            var arg5: String? = null
    ) {
        constructor(other: IntermediateRepresentation) : this(
                other.lineNum,
                other.instruction,
                other.arg1,
                other.arg2,
                other.arg3,
                other.arg4,
                other.arg5
        )

        override fun toString(): String {
            val sb = StringBuilder()
            sb.append(instruction)
            arg1?.let { sb.append(" $it") }
            arg2?.let { sb.append(", $it") }
            arg3?.let { sb.append(", $it") }
            arg4?.let { sb.append(", $it") }
            arg5?.let { sb.append(", $it") }
            sb.append("; (at line $lineNum)")
            return sb.toString()
        }
    }


}

open class SyntaxError(msg: String? = null) : Exception(msg)
class IllegalTokenException(msg: String? = null) : SyntaxError(msg)
class UnresolvedReference(msg: String? = null) : SyntaxError(msg)
class UndefinedStatement(msg: String? = null) : SyntaxError(msg)
class DuplicateDefinition(msg: String? = null) : SyntaxError(msg)
class PreprocessorErrorMessage(msg: String) : SyntaxError(msg)
