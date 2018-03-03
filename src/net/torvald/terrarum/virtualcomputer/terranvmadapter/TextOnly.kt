package net.torvald.terrarum.virtualcomputer.terranvmadapter

import com.badlogic.gdx.Game
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputProcessor
import com.badlogic.gdx.backends.lwjgl.LwjglApplication
import com.badlogic.gdx.backends.lwjgl.LwjglApplicationConfiguration
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import net.torvald.terranvm.runtime.Assembler
import net.torvald.terranvm.runtime.GdxPeripheralWrapper
import net.torvald.terranvm.runtime.TerranVM
import net.torvald.terranvm.runtime.compiler.simplec.SimpleC

/**
 * Created by minjaesong on 2017-11-17.
 */
class TextOnly : Game() {

    lateinit var background: Texture
    lateinit var execLed: Texture

    lateinit var batch: SpriteBatch

    lateinit var vm: TerranVM

    lateinit var sevensegFont: BitmapFont

    lateinit var peripheral: PeriMDA

    lateinit var vmThread: Thread

    lateinit var memvwr: Memvwr

    override fun create() {
        val vmDelay = 200

        background = Texture(Gdx.files.internal("assets/8025_textonly.png"))
        execLed = Texture(Gdx.files.internal("assets/led_green.tga"))

        batch = SpriteBatch()

        sevensegFont = SevensegFont()

        peripheral = PeriMDA(vmExecDelay = vmDelay)

        vm = TerranVM(512, stdout = peripheral.printStream)

        vm.peripherals[TerranVM.IRQ_KEYBOARD] = KeyboardAbstraction()
        vm.peripherals[3] = peripheral


        val assembler = Assembler(vm)


        val testProgram = """
            void fo1() <%
                fo2();

                asm("loadwordi r6, 42195;");
            %>

            void fo2() <%

                asm("loadwordi r7, 421f;");
            %>

            int i;
            int j;
            int k;

            i = 42f;
            j = 59f;
            k = 0xbebafeca;

            fo1();

            asm("loadwordi r2, 69696969;");
            asm("smov r3, r1;");
        """.trimIndent()
        val program = SimpleC.buildTree(SimpleC.tokenise(SimpleC.preprocess(testProgram)))

        println(program)

        val notatedProgram = SimpleC.treeToProperNotation(program)

        val programInIR = SimpleC.notationToIR(notatedProgram)
        val programInNewIR = SimpleC.preprocessIR(programInIR)
        val programASM = SimpleC.IRtoASM(programInNewIR)
        val code = assembler(programASM.joinToString("\n"))


        //val mdaFiller = assembler("loadbytei r1, 0;loadbytei r2, 3;:loope;inc r1;storebyte r1, r1, r2;jmp @loope;")


        /*val intFloatTest = assembler("""
            #loadbytei r1, 42;
            #loadbytei r2, 42f;
            loadwordi r1, 42;
            loadwordi r2, 42f;
        """.trimIndent())*/


        vm.loadProgram(code)
        vm.delayInMills = vmDelay



        memvwr = Memvwr(vm)


        Gdx.input.inputProcessor = TVMInputProcessor(vm)


        vmThread = Thread(vm)
        vmThread.start()
    }

    private val height: Int; get() = Gdx.graphics.height

    private val lcdOffX = 74f
    private val lcdOffY = 56f

    override fun render() {
        Gdx.graphics.setTitle("TerranVM Debugging Console - Text Only Mode — F: ${Gdx.graphics.framesPerSecond}")


        vm.pauseExec()


        memvwr.update()


        batch.inUse {
            batch.color = Color.WHITE
            batch.draw(background, 0f, 0f)


            // exec lamp
            if (vm.isRunning) {
                batch.draw(execLed, 51f, 39f)
            }

            // draw whatever on the peripheral's memory
            peripheral.render(batch, Gdx.graphics.rawDeltaTime, lcdOffX, lcdOffY)


            // seven seg displays
            // -> memsize
            batch.color = Color(0x25e000ff)
            sevensegFont.draw(batch, vm.memory.size.toString().padStart(8, ' '), 307f, height - 18 - 500f)
            // -> program counter
            batch.color = Color(0xff5a66ff.toInt())
            sevensegFont.draw(batch, vm.pc.toString(16).padStart(6, ' '), 451f, height - 18 - 500f)
            // -> link register
            sevensegFont.draw(batch, vm.lr.toString(16).padStart(6, ' '), 585f, height - 18 - 500f)
            // -> stack pointer
            sevensegFont.draw(batch, vm.sp.toString(16).padStart(4, ' '), 719f, height - 18 - 500f)
        }


        vm.resumeExec()
    }

    override fun dispose() {
        background.dispose()
        execLed.dispose()
    }

    private inline fun SpriteBatch.inUse(action: () -> Unit) {
        this.begin()
        action.invoke()
        this.end()
    }


    class TVMInputProcessor(val vm: TerranVM) : InputProcessor {
        override fun touchUp(p0: Int, p1: Int, p2: Int, p3: Int): Boolean {
            return false
        }

        override fun mouseMoved(p0: Int, p1: Int): Boolean {
            return false
        }

        override fun keyTyped(p0: Char): Boolean {
            (vm.peripherals[TerranVM.IRQ_KEYBOARD] as GdxPeripheralWrapper).keyTyped(p0)
            vm.interruptKeyPress()
            return true
        }

        override fun scrolled(p0: Int): Boolean {
            return false
        }

        override fun keyUp(p0: Int): Boolean {
            return false
        }

        override fun touchDragged(p0: Int, p1: Int, p2: Int): Boolean {
            return false
        }

        override fun keyDown(p0: Int): Boolean {
            return false
        }

        override fun touchDown(p0: Int, p1: Int, p2: Int, p3: Int): Boolean {
            return false
        }
    }

}

fun main(args: Array<String>) {
    val config = LwjglApplicationConfiguration()
    config.width = 1106
    config.height = 556
    config.foregroundFPS = 0
    config.resizable = false

    LwjglApplication(TextOnly(), config)
}