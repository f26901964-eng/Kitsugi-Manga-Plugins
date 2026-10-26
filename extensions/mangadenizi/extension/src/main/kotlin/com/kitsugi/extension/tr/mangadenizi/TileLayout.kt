package com.kitsugi.extension.tr.mangadenizi

/** Public tiled-v1 layout used by MangaDenizi's reader, including unequal edge tiles. */
internal object TileLayout {
    private const val MASK = 0xFFFFFFFFL
    private const val FALLBACK = 2463534242L
    data class Tile(val sx: Int, val sy: Int, val dx: Int, val dy: Int, val width: Int, val height: Int)

    fun restore(width: Int, height: Int, grid: Int, seed: Long): List<Tile> {
        require(width > 0 && height > 0 && grid in 1..32 && seed in 0..MASK)
        val n = minOf(grid, width, height)
        val xs = IntArray(n + 1) { (it.toLong() * width / n).toInt() }
        val ys = IntArray(n + 1) { (it.toLong() * height / n).toInt() }
        val columns = shuffle(n, seed xor 2246822507L)
        val rows = shuffle(n, seed xor 2654435769L)
        val tiles = ArrayList<Tile>(n * n)
        var sy = 0
        for (row in rows) {
            val h = ys[row + 1] - ys[row]
            var sx = 0
            for (column in columns) {
                val w = xs[column + 1] - xs[column]
                tiles.add(Tile(sx, sy, xs[column], ys[row], w, h))
                sx += w
            }
            sy += h
        }
        return tiles
    }
    private fun shuffle(size: Int, seed: Long): IntArray {
        var state = (seed and MASK).takeIf { it != 0L } ?: FALLBACK
        val result = IntArray(size) { it }
        for (n in size - 1 downTo 1) {
            state = (state xor ((state shl 13) and MASK)) and MASK
            state = (state xor (state ushr 17)) and MASK
            state = (state xor ((state shl 5) and MASK)) and MASK
            val i = (state % (n + 1)).toInt()
            val temp = result[n]
            result[n] = result[i]
            result[i] = temp
        }
        return result
    }
}
