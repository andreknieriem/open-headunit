package com.andrerinas.openheadunit.main

/**
 * The pages the launcher home is made of, and the widgets on the pages the driver added.
 *
 * The first page is always the main button, so a connection is one tap away from wherever the
 * home was left. A screen too small for the main button and the favorite apps together gives the
 * favorites a page of their own instead of hiding the main button. Past that, every page is one the
 * driver added for widgets, up to [MAX_PAGES] in all.
 *
 * Stored as one string, each page a "p" followed by its comma separated App Widget ids, the pages
 * joined by "|": "p12,13|p|p40" is three pages, the second one empty. The marker is what tells an
 * empty page from no page at all.
 *
 * Pure, so the page rules are unit tests rather than swipes on a head unit.
 */
object HomePagesPolicy {

    /** Four pages: past that the dots stop telling the driver where they are at a glance. */
    const val MAX_PAGES = 4

    /** A two by two grid on a landscape page, and still readable on a 800x480 unit. */
    const val MAX_WIDGETS_PER_PAGE = 4

    private const val PAGE_MARKER = "p"
    private const val PAGE_SEPARATOR = "|"
    private const val ID_SEPARATOR = ","

    /**
     * HOME is the main button; APPS the favorite apps when they do not fit beside it; WIDGET_ROW the
     * widgets placed under the favorites when the screen has no room for that row; WIDGETS a page
     * the driver added.
     */
    enum class Kind { HOME, APPS, WIDGET_ROW, WIDGETS }

    /** One page, in the order the pager shows it. [widgetPage] indexes the stored widget pages. */
    data class Page(val kind: Kind, val widgetPage: Int = -1)

    /**
     * @param compact the screen is too small for the main button and the favorite apps together.
     * @param widgetPages how many widget pages are stored; the ones that do not fit are kept, just
     *   not shown, so turning the unit back to a larger density brings them back.
     * @param widgetRowOverflows the row under the favorites has widgets and no room on this screen,
     *   so they get a page instead of disappearing. Meaningless on the compact home, where that row
     *   already has the favorites' page to itself.
     * @param widgetsShown the widgets switch is on. Off, the home has no widget row and no widget
     *   page, whatever is stored; the stored widgets are kept for when it is turned back on.
     */
    fun pages(
        compact: Boolean,
        widgetPages: Int,
        widgetRowOverflows: Boolean = false,
        widgetsShown: Boolean = true
    ): List<Page> {
        val base = when {
            compact -> listOf(Page(Kind.HOME), Page(Kind.APPS))
            widgetRowOverflows && widgetsShown -> listOf(Page(Kind.HOME), Page(Kind.WIDGET_ROW))
            else -> listOf(Page(Kind.HOME))
        }
        val shown = if (widgetsShown) widgetPages.coerceIn(0, MAX_PAGES - base.size) else 0
        return base + (0 until shown).map { Page(Kind.WIDGETS, it) }
    }

    fun canAddPage(
        compact: Boolean,
        widgetPages: Int,
        widgetRowOverflows: Boolean = false,
        widgetsShown: Boolean = true
    ): Boolean =
        pages(compact, widgetPages + 1, widgetRowOverflows, widgetsShown).size >
            pages(compact, widgetPages, widgetRowOverflows, widgetsShown).size

    /** Whatever was stored, so a corrupt value never breaks the home: unknown parts are dropped. */
    fun decode(stored: String): List<List<Int>> = stored.split(PAGE_SEPARATOR)
        .filter { it.startsWith(PAGE_MARKER) }
        .map { page ->
            page.removePrefix(PAGE_MARKER).split(ID_SEPARATOR)
                .mapNotNull { it.trim().toIntOrNull() }
                .distinct()
                .take(MAX_WIDGETS_PER_PAGE)
        }
        .take(MAX_PAGES - 1)

    fun encode(pages: List<List<Int>>): String = pages.take(MAX_PAGES - 1).joinToString(PAGE_SEPARATOR) { page ->
        PAGE_MARKER + page.take(MAX_WIDGETS_PER_PAGE).joinToString(ID_SEPARATOR)
    }

    fun addPage(pages: List<List<Int>>): List<List<Int>> =
        if (pages.size >= MAX_PAGES - 1) pages else pages + listOf(emptyList())

    fun removePage(pages: List<List<Int>>, index: Int): List<List<Int>> =
        pages.filterIndexed { i, _ -> i != index }

    /** @return the pages with [id] at the end of page [index], or null when it is full or gone. */
    fun addWidget(pages: List<List<Int>>, index: Int, id: Int): List<List<Int>>? {
        val page = pages.getOrNull(index) ?: return null
        if (id in page) return pages
        if (page.size >= MAX_WIDGETS_PER_PAGE) return null
        return pages.mapIndexed { i, ids -> if (i == index) ids + id else ids }
    }

    fun removeWidget(pages: List<List<Int>>, id: Int): List<List<Int>> = pages.map { ids -> ids.filterNot { it == id } }

    fun allWidgets(pages: List<List<Int>>): List<Int> = pages.flatten()

    /** The home is at least this wide, in dp, before the favorites can move beside the card. */
    const val SIDE_BY_SIDE_MIN_WIDTH_DP = 1100

    /**
     * Width over height from which the favorites sit beside the card. A 1920x720 unit (8:3) has
     * width to spare; a 16:9 one does not, and there the column next to the card left the
     * favorites as a narrow strip with the card's empty space under it.
     */
    const val SIDE_BY_SIDE_MIN_ASPECT = 2.2f

    /** Whether the launcher home puts the card and the favorites side by side. */
    fun sideBySide(landscape: Boolean, widthDp: Int, heightDp: Int): Boolean =
        landscape && heightDp > 0 && widthDp >= SIDE_BY_SIDE_MIN_WIDTH_DP &&
            widthDp >= heightDp * SIDE_BY_SIDE_MIN_ASPECT

    /** Two columns where the page is wider than tall, one where it is not. */
    fun columns(landscape: Boolean): Int = if (landscape) 2 else 1

    /** How many cells each row of a page holds, for [cells] cells in rows of [columns]. */
    fun rows(cells: Int, columns: Int): List<Int> {
        if (cells <= 0 || columns <= 0) return emptyList()
        val full = cells / columns
        val rest = cells % columns
        return List(full) { columns } + if (rest > 0) listOf(rest) else emptyList()
    }

    /**
     * With more than one page the main button is not always in view, so the driver bar carries
     * every chosen connection rather than all but the one the main button offers.
     */
    fun barCarriesEveryConnection(pageCount: Int): Boolean = pageCount > 1

    fun clampPage(page: Int, pageCount: Int): Int = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
}
