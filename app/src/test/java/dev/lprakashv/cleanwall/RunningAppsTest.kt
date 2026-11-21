package dev.lprakashv.cleanwall

import org.junit.Assert.assertEquals
import org.junit.Test

class RunningAppsTest {
    @Test fun onlyPackagesFromCurrentUserAreReportedOnce() {
        val processes = listOf(
            10121 to listOf("app.one", "app.shared"),
            10121 to listOf("app.one"),
            110121 to listOf("app.other"),
            10000 to emptyList()
        )
        assertEquals(setOf("app.one", "app.shared"), runningPackagesForUser(0, processes))
        assertEquals(setOf("app.other"), runningPackagesForUser(1, processes))
    }
}
