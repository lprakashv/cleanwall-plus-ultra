package dev.quirkdeck.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPlacementTest {
    @Test fun defaultsUseKnownVideoServicesAndAndroidCategories() {
        assertTrue(AppPlacement.belongsInWatch("com.netflix.ninja", false, emptySet(), emptySet()))
        assertTrue(AppPlacement.belongsInWatch("example.video", true, emptySet(), emptySet()))
        assertFalse(AppPlacement.belongsInWatch("example.utility", false, emptySet(), emptySet()))
    }

    @Test fun explicitPlacementOverridesInferenceButPlayStoreStaysInHeader() {
        assertTrue(AppPlacement.belongsInWatch("example.utility", false, setOf("example.utility"), emptySet()))
        assertFalse(AppPlacement.belongsInWatch("com.netflix.ninja", true, emptySet(), setOf("com.netflix.ninja")))
        assertFalse(AppPlacement.belongsInWatch(AppPlacement.PLAY_STORE, true, setOf(AppPlacement.PLAY_STORE), emptySet()))
    }
}
