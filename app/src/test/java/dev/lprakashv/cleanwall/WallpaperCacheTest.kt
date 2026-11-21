package dev.lprakashv.cleanwall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WallpaperCacheTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun prunesOnlyOrphanedDownloadsAndRetiredCatalogFiles() {
        val directory = temp.newFolder("public-wallpapers")
        val kept = setOf("commons-0.jpg", "loops-0.mp4")
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

    @Test fun prunesOnlyRetiredOnlineMediaAndInterruptedDownloads() {
        val directory = temp.newFolder("online-wallpaper")
        val kept = "123.jpg"
        listOf(kept, "456.webm", "88-99.mp4", "download-17.jpg", "notes.txt").forEach { directory.resolve(it).writeText(it) }
        directory.resolve("download-42.mp4").mkdir()
        OnlineWallpapers.pruneCache(directory, kept)
        assertEquals(setOf(kept, "notes.txt"), directory.listFiles()!!.filter { it.isFile }.map { it.name }.toSet())
        assertTrue(directory.resolve("download-42.mp4").isDirectory)
    }

    @Test fun retiredCatalogIndexStartsAtFirstItem() {
        assertEquals(0, PublicWallpapers.nextIndex(2, 1))
        assertEquals(0, PublicWallpapers.nextIndex(-1, 2))
        assertEquals(1, PublicWallpapers.nextIndex(0, 2))
    }

    @Test fun rejectsLongVideos() {
        assertTrue(PublicWallpapers.shortVideo(30_000))
        assertFalse(PublicWallpapers.shortVideo(30_001))
        assertFalse(PublicWallpapers.shortVideo(0))
    }

    @Test fun cleanupDoesNotCreateAnUnusedCache() {
        val directory = temp.root.resolve("public-wallpapers")
        PublicWallpapers.pruneCache(directory, emptySet())
        assertFalse(directory.exists())
    }
}
