package dev.lprakashv.cleanwall

import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueArtworkTest {
    @Test
    fun samplesImagesForTheDisplayedSizeAndBoundsLargeDimensions() {
        assertEquals(1, ContinueArtwork.sampleSize(96, 54, 96, 54))
        assertEquals(4, ContinueArtwork.sampleSize(640, 360, 96, 54))
        assertEquals(4, ContinueArtwork.sampleSize(8000, 10, 96, 54))
    }
}
