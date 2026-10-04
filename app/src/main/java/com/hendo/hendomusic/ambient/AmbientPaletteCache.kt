package com.hendo.hendomusic.ambient

/** Bounded access-order cache. It deliberately stores palettes, never Bitmap references. */
class AmbientPaletteCache(private val maxEntries: Int = AmbientPaletteConfig.MAX_CACHE_ENTRIES) {
    private val values = object : LinkedHashMap<String, AmbientPalette>(maxEntries, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AmbientPalette>?): Boolean = size > maxEntries
    }

    @Synchronized fun get(key: String): AmbientPalette? = values[key]
    @Synchronized fun put(key: String, palette: AmbientPalette) { values[key] = palette }
    @Synchronized fun size(): Int = values.size
}
