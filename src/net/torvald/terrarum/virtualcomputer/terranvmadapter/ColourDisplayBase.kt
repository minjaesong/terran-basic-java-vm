package net.torvald.terrarum.virtualcomputer.terranvmadapter

import com.badlogic.gdx.graphics.Color

interface ColourDisplayBase {


    val screenWidth: Int
    val screenHeight: Int
    val maxColours: Int
    val displayModeCount: Int

    val maxSpriteCount: Int
    val maxSpriteColours: Int
    val maxSpriteScaleX: Int
    val maxSpriteScaleY: Int
    val spriteWidth: Int
    val spriteHeight: Int

    val scrollable: Boolean
    var scrollX: Int
    var scrollY: Int

}

object PrefferedPaletteGDX {
    val colourIndices64 = arrayOf(
            0x333.fourBitCol(),
            0x600.fourBitCol(),
            0xA36.fourBitCol(),
            0x636.fourBitCol(),
            0x73B.fourBitCol(),
            0x427.fourBitCol(),
            0x44C.fourBitCol(),
            0x038.fourBitCol(),
            0x47B.fourBitCol(),
            0x466.fourBitCol(),
            0x353.fourBitCol(),
            0x453.fourBitCol(),
            0x763.fourBitCol(),
            0xA63.fourBitCol(),
            0x742.fourBitCol(),
            0x000.fourBitCol(),
            0x666.fourBitCol(),
            0xA00.fourBitCol(),
            0xE2A.fourBitCol(),
            0xD2F.fourBitCol(),
            0x92F.fourBitCol(),
            0x548.fourBitCol(),
            0x32F.fourBitCol(),
            0x36F.fourBitCol(),
            0x588.fourBitCol(),
            0x390.fourBitCol(),
            0x5F0.fourBitCol(),
            0x684.fourBitCol(),
            0xBA2.fourBitCol(),
            0xE60.fourBitCol(),
            0x854.fourBitCol(),
            0x533.fourBitCol(),
            0x999.fourBitCol(),
            0xE00.fourBitCol(),
            0xE6A.fourBitCol(),
            0xE6F.fourBitCol(),
            0x848.fourBitCol(),
            0x62F.fourBitCol(),
            0x66F.fourBitCol(),
            0x4AF.fourBitCol(),
            0x5BA.fourBitCol(),
            0x8FE.fourBitCol(),
            0x7F8.fourBitCol(),
            0x9E0.fourBitCol(),
            0xFE0.fourBitCol(),
            0xEA0.fourBitCol(),
            0xC85.fourBitCol(),
            0xE55.fourBitCol(),
            0xCCC.fourBitCol(),
            0xFFF.fourBitCol(),
            0xFDE.fourBitCol(),
            0xEAF.fourBitCol(),
            0xA3B.fourBitCol(),
            0x96F.fourBitCol(),
            0xAAF.fourBitCol(),
            0x7AF.fourBitCol(),
            0x3DF.fourBitCol(),
            0xBFF.fourBitCol(),
            0xBFB.fourBitCol(),
            0xAF6.fourBitCol(),
            0xFEB.fourBitCol(),
            0xFD7.fourBitCol(),
            0xE96.fourBitCol(),
            0xEBA.fourBitCol()
    )

    val colourIndices16 = arrayOf(
            0x000.fourBitCol(),
            0xfff.fourBitCol(),
            0x666.fourBitCol(),
            0xccc.fourBitCol(),
            0xfe0.fourBitCol(),
            0xe60.fourBitCol(),
            0xe00.fourBitCol(),
            0xe2a.fourBitCol(),
            0x427.fourBitCol(),
            0x32f.fourBitCol(),
            0x4af.fourBitCol(),
            0x5f0.fourBitCol(),
            0x390.fourBitCol(),
            0x353.fourBitCol(),
            0x533.fourBitCol(),
            0xa63.fourBitCol()
    )

    private fun Int.fourBitCol() = Color(
            this.and(0xF00).shl(20) or this.and(0xF00).shl(16) or
                    this.and(0x0F0).shl(16) or this.and(0x0F0).shl(12) or
                    this.and(0x00F).shl(12) or this.and(0x00F).shl(8)  or
                    0xFF
    )
}