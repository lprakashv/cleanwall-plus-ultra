package dev.lprakashv.cleanwall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WallpaperCacheTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun prunesOnlyOrphanedDownloadsAndRetiredCatalogFiles() {
        val directory = temp.newFolder("public-wallpapers")
        val kept = setOf("nasa-0.jpg", "loops-1.mp4")
        val retired = setOf("nasa-6.jpg", "commons-8.jpg", "loops-2.webm", "wallpaper-482934.mp4")
        (kept + retired + "notes.txt").forEach { directory.resolve(it).writeText(it) }
        directory.resolve("wallpaper-53.jpg").mkdir()
        val outside = temp.newFile("wallpaper-17.jpg")

        PublicWallpapers.pruneCache(directory, kept)
        PublicWallpapers.pruneCache(directory, kept)

        assertEquals(kept + "notes.txt", directory.listFiles()!!.filter { it.isFile }.map { it.name }.toSet())
        assertEquals(true, directory.resolve("wallpaper-53.jpg").isDirectory)
        assertEquals(true, outside.isFile)
    }

    @Test fun cleanupDoesNotCreateAnUnusedCache() {
        val directory = temp.root.resolve("public-wallpapers")
        PublicWallpapers.pruneCache(directory, emptySet())
        assertFalse(directory.exists())
    }
}
