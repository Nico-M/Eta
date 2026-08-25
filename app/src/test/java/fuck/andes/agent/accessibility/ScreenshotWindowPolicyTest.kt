package fuck.andes.agent.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenshotWindowPolicyTest {
    @Test
    fun `only confirmed own accessibility overlay is excluded`() {
        assertEquals(
            ScreenshotWindowPolicy.Decision.EXCLUDE,
            decide(overlay = true, resolvedPackage = "fuck.andes"),
        )
        assertEquals(
            ScreenshotWindowPolicy.Decision.CAPTURE,
            decide(overlay = true, resolvedPackage = "third.party.accessibility"),
        )
        assertEquals(
            ScreenshotWindowPolicy.Decision.CAPTURE,
            decide(overlay = true, resolvedPackage = null),
        )
    }

    @Test
    fun `unknown active or focused window blocks package exclusion capture`() {
        assertEquals(
            ScreenshotWindowPolicy.Decision.BLOCK_UNKNOWN,
            decide(active = true, resolvedPackage = null, excluded = setOf("entry.app")),
        )
        assertEquals(
            ScreenshotWindowPolicy.Decision.BLOCK_UNKNOWN,
            decide(focused = true, resolvedPackage = null, excluded = setOf("entry.app")),
        )
        assertEquals(
            ScreenshotWindowPolicy.Decision.BLOCK_UNKNOWN,
            decide(application = true, resolvedPackage = null, excluded = setOf("entry.app")),
        )
    }

    @Test
    fun `confirmed excluded package is omitted regardless of window type`() {
        assertEquals(
            ScreenshotWindowPolicy.Decision.EXCLUDE,
            decide(resolvedPackage = "entry.app", excluded = setOf("entry.app")),
        )
    }

    @Test
    fun `captureMode API 33 no exclusions no overlay returns ROOT_DISPLAY`() {
        assertEquals(
            ScreenshotWindowPolicy.CaptureMode.ROOT_DISPLAY,
            ScreenshotWindowPolicy.captureMode(33, false, false),
        )
    }

    @Test
    fun `captureMode API 33 with exclusions returns null`() {
        assertNull(
            ScreenshotWindowPolicy.captureMode(33, true, false),
        )
    }

    @Test
    fun `captureMode API 33 with unsafe overlay returns null`() {
        assertNull(
            ScreenshotWindowPolicy.captureMode(33, false, true),
        )
    }

    @Test
    fun `captureMode API 34 plus always returns WINDOWS`() {
        assertEquals(
            ScreenshotWindowPolicy.CaptureMode.WINDOWS,
            ScreenshotWindowPolicy.captureMode(34, false, false),
        )
        assertEquals(
            ScreenshotWindowPolicy.CaptureMode.WINDOWS,
            ScreenshotWindowPolicy.captureMode(34, true, true),
        )
        assertEquals(
            ScreenshotWindowPolicy.CaptureMode.WINDOWS,
            ScreenshotWindowPolicy.captureMode(35, false, true),
        )
    }

    private fun decide(
        overlay: Boolean = false,
        application: Boolean = false,
        active: Boolean = false,
        focused: Boolean = false,
        resolvedPackage: String? = "normal.app",
        excluded: Set<String> = emptySet(),
    ): ScreenshotWindowPolicy.Decision = ScreenshotWindowPolicy.decide(
        isAccessibilityOverlay = overlay,
        isApplicationWindow = application,
        active = active,
        focused = focused,
        resolvedPackage = resolvedPackage,
        ownPackage = "fuck.andes",
        excludedPackages = excluded,
    )
}