package com.andrerinas.openheadunit.main

/**
 * Where the date, the time, the speed and the maintenance line go in the main button's top corner.
 *
 * The block sits beside the title, which keeps at least [Room.minTitle] of width. When the
 * maintenance line makes the block too wide for that, it moves to a row of its own above the
 * title, in the empty part of the card a portrait screen has, and the clock stays beside the
 * title. Only when there is no such room does the clock step aside: the maintenance line is the
 * one that asks the driver to do something, so it is the last to go.
 *
 * Every width and height is in pixels. Pure, so the choices are unit tests rather than a row of
 * phones.
 */
object HomeInfoFitPolicy {

    enum class Placement {
        /** The whole block beside the title. */
        BESIDE_TITLE,
        /** The clock beside the title, the maintenance line on a wider row above it. */
        LINE_ON_OWN_ROW,
        /** No room for the clock: the maintenance line alone. */
        LINE_ONLY,
        /** Nothing fits beside the title or above it. */
        NONE,
    }

    data class Room(
        /** The card's width inside its side padding. */
        val inner: Int,
        /** The space kept between the block and the title, and between the line and the icon. */
        val gap: Int,
        /** The narrowest the title may be made. */
        val minTitle: Int,
        val iconWidth: Int,
        val iconHeight: Int,
        /** The height from the top of the block to the first line of text, less a gap. */
        val freeHeight: Int,
    )

    data class Block(
        /** The clock, the speed and the date; zero when none of them is on. */
        val infoWidth: Int,
        val infoHeight: Int,
        /** Null when the maintenance feature is off. */
        val line: Line?,
    )

    data class Line(
        /** The whole line, with its padding. */
        val width: Int,
        /** The narrowest it gets with its name shortened, its padding included. */
        val minWidth: Int,
        /** With its gap above. */
        val height: Int,
        /** The widest it may be beside the title. */
        val cap: Int,
    )

    data class Fit(
        val placement: Placement,
        /** How wide the maintenance line may be; zero when it is not shown. */
        val lineWidth: Int = 0,
        /** The widest the title may be, or null when nothing sits beside it. */
        val titleWidth: Int? = null,
    ) {
        val showInfo: Boolean get() = placement == Placement.BESIDE_TITLE || placement == Placement.LINE_ON_OWN_ROW
        val showLine: Boolean get() = lineWidth > 0
    }

    fun fit(room: Room, block: Block): Fit {
        val besideMax = room.inner - room.gap - room.minTitle
        val hasInfo = block.infoWidth > 0
        val infoFits = !hasInfo || block.infoWidth <= besideMax
        val line = block.line
        if (line == null) {
            return if (hasInfo && infoFits) Fit(Placement.BESIDE_TITLE, titleWidth = titleBeside(room, block.infoWidth)) else Fit(Placement.NONE)
        }
        val besideLine = minOf(line.width, line.cap)
        if (infoFits && besideLine <= besideMax && besideLine >= line.minWidth) {
            return Fit(Placement.BESIDE_TITLE, besideLine, titleBeside(room, maxOf(block.infoWidth, besideLine)))
        }
        if (infoFits) {
            ownRow(room, line, if (hasInfo) block.infoHeight else 0)?.let { width ->
                return Fit(
                    if (hasInfo) Placement.LINE_ON_OWN_ROW else Placement.LINE_ONLY,
                    width,
                    if (hasInfo) titleBeside(room, block.infoWidth) else null
                )
            }
            // No row above the title: the line shortens its name beside it, down to its minimum.
            val squeezed = minOf(besideLine, besideMax)
            if (squeezed >= line.minWidth) {
                return Fit(
                    if (hasInfo) Placement.BESIDE_TITLE else Placement.LINE_ONLY,
                    squeezed,
                    titleBeside(room, maxOf(block.infoWidth, squeezed))
                )
            }
        }
        if (hasInfo) {
            // The clock is what costs the room: the line goes on without it.
            ownRow(room, line, 0)?.let { return Fit(Placement.LINE_ONLY, it) }
            val alone = minOf(besideLine, besideMax)
            if (alone >= line.minWidth) return Fit(Placement.LINE_ONLY, alone, titleBeside(room, alone))
        }
        return Fit(Placement.NONE)
    }

    /**
     * How wide the line may be on a row of its own under [infoHeight], or null when the card has
     * no height for it there. A row that starts beside the connection icon leaves it its width.
     */
    private fun ownRow(room: Room, line: Line, infoHeight: Int): Int? {
        if (infoHeight + line.height > room.freeHeight) return null
        val rowWidth = if (infoHeight < room.iconHeight) room.inner - room.iconWidth - room.gap else room.inner
        val width = minOf(line.width, rowWidth)
        return if (width >= line.minWidth) width else null
    }

    private fun titleBeside(room: Room, blockWidth: Int) = room.inner - blockWidth - room.gap
}
