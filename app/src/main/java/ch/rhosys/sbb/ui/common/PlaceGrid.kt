package ch.rhosys.sbb.ui.common

import ch.rhosys.sbb.domain.model.Place

// Shared grid geometry for the resizable place-tile layout. HomeScreen (read-only
// display) and the Places edit screen (drag-to-move / drag-corner-to-resize) both
// position tiles using the same column count and cell-size formula (cell = width /
// PLACE_GRID_COLUMNS, square) so a tile's saved position lines up between the two
// screens.
const val PLACE_GRID_COLUMNS = 10
const val PLACE_GRID_DEFAULT_TILE_SIZE = 2

private fun rectsOverlap(
    ax: Int, ay: Int, aw: Int, ah: Int,
    bx: Int, by: Int, bw: Int, bh: Int,
): Boolean = ax < bx + bw && bx < ax + aw && ay < by + bh && by < ay + ah

/** Whether placing a widthxheight tile at (x, y) would overlap any place other than [excludingId]. */
fun rectOverlapsAnyPlace(
    x: Int, y: Int, width: Int, height: Int,
    places: List<Place>,
    excludingId: Long? = null,
): Boolean = places.any { other ->
    other.id != excludingId &&
        rectsOverlap(x, y, width, height, other.gridX, other.gridY, other.gridWidth, other.gridHeight)
}

/**
 * First open slot for a widthxheight tile, scanning left-to-right then top-to-bottom,
 * bounded to [columns] columns and [maxRows] rows. Falls back to (0, 0) if nothing
 * fits, which callers can only reach if [places] already covers every row up to
 * [maxRows] — not possible in practice given how few places a user saves.
 */
fun findFreeGridSlot(
    places: List<Place>,
    width: Int = PLACE_GRID_DEFAULT_TILE_SIZE,
    height: Int = PLACE_GRID_DEFAULT_TILE_SIZE,
    columns: Int = PLACE_GRID_COLUMNS,
    maxRows: Int = 1000,
): Pair<Int, Int> {
    var y = 0
    while (y <= maxRows - height) {
        var x = 0
        while (x <= columns - width) {
            if (!rectOverlapsAnyPlace(x, y, width, height, places)) return x to y
            x++
        }
        y++
    }
    return 0 to 0
}
