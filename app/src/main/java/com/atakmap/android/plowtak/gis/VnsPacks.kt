package com.atakmap.android.plowtak.gis

import java.io.File

/** Finds a GraphHopper 1.0 pack, preferring the region VNS has active. */
object VnsPacks {

    fun looksLikeGhPack(dir: File): Boolean =
        File(dir, "nodes").isFile &&
            File(dir, "edges").isFile &&
            File(dir, "geometry").isFile

    /**
     * [preferredDir] (the road-snap setting) wins when it is a pack.
     * Otherwise the VNS active region under [vnsGhRoot], else the first pack.
     */
    fun resolve(preferredDir: String, vnsGhRoot: File?, activeRegion: String?): File? {
        if (preferredDir.isNotBlank()) {
            val f = File(preferredDir)
            if (looksLikeGhPack(f)) return f
        }
        val root = vnsGhRoot ?: return null
        if (!root.isDirectory) return null
        val regions = root.listFiles()?.filter { it.isDirectory && looksLikeGhPack(it) }.orEmpty()
        if (!activeRegion.isNullOrBlank()) {
            regions.firstOrNull { it.name.equals(activeRegion, ignoreCase = true) }?.let { return it }
        }
        return regions.minByOrNull { it.name.lowercase() }
    }

    /** OSM data date from the pack `timestamp` file, or the folder name. */
    fun describe(dir: File): String {
        val stamp = File(dir, "timestamp").takeIf { it.isFile }?.readText()?.trim().orEmpty()
        val date = stamp.take(10).ifBlank { "undated" }
        return "${dir.name} ($date)"
    }
}
