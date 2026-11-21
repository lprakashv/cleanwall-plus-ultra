package dev.lprakashv.cleanwall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineWallpapersTest {
    @Test fun mapsEarlierCategoryPreferences() {
        assertEquals("Landscapes", OnlineWallpapers.normalizeCategory("places"))
        assertEquals("Nature", OnlineWallpapers.normalizeCategory("nature"))
        assertEquals("Mountains", OnlineWallpapers.normalizeCategory("Mountains"))
    }

    @Test fun filtersCommonsByLicenseFormatAndDimensions() {
        assertTrue(OnlineWallpapers.suitableCommons(false, "CC0", "image/jpeg", 1920, 1080, 1000))
        assertTrue(OnlineWallpapers.suitableCommons(true, "CC0", "video/webm", 1920, 1080, 1000))
        assertFalse(OnlineWallpapers.suitableCommons(false, "CC BY 4.0", "image/jpeg", 1920, 1080, 1000))
        assertFalse(OnlineWallpapers.suitableCommons(true, "CC0", "video/webm", 3840, 2160, 1000))
        assertFalse(OnlineWallpapers.suitableCommons(true, "CC0", "video/webm", 1920, 1080, 49L * 1024 * 1024))
        assertFalse(OnlineWallpapers.suitableCommons(true, "CC0", "image/jpeg", 1920, 1080, 1000))
        assertFalse(OnlineWallpapers.suitableCommons(false, "CC0", "image/jpeg", 720, 1280, 1000))
    }

    @Test fun allowsOnlySecureSourceUrls() {
        assertTrue(OnlineWallpapers.allowedUrl("https://upload.wikimedia.org/file.webm", "wikimedia.org"))
        assertTrue(OnlineWallpapers.allowedUrl("https://thumb.wikimedia.org/image.jpg", "wikimedia.org"))
        assertFalse(OnlineWallpapers.allowedUrl("http://upload.wikimedia.org/file", "wikimedia.org"))
        assertFalse(OnlineWallpapers.allowedUrl("https://wikimedia.org.evil.example/file", "wikimedia.org"))
        assertFalse(OnlineWallpapers.allowedUrl("https://wikimedia.org@evil.example/file", "wikimedia.org"))
        assertFalse(OnlineWallpapers.allowedUrl("https://wikimedia.org:8443/file", "wikimedia.org"))
    }
}
