package fuck.andes.agent.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EtaAssistInvocationCoordinatorTest {
    private var timerTask: (() -> Unit)? = null
    private var lastSnapshot: EtaAssistInvocationCoordinator.PublishSnapshot? = null
    private var dropCount = 0

    private fun createCoordinator() = EtaAssistInvocationCoordinator(
        schedulePartial = { _ms, task ->
            timerTask = task
            AutoCloseable { timerTask = null }
        },
        publish = { snapshot -> lastSnapshot = snapshot },
        onDrop = { dropCount++ },
    )

    @Test
    fun `both callbacks publish immediately`() {
        val coordinator = createCoordinator()
        coordinator.begin("entry-1")
        coordinator.onStructure("entry-1", "text", 100L)
        coordinator.onScreenshot("entry-1", android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888), 150L)
        assertEquals("entry-1", lastSnapshot?.entryId)
        assertEquals("text", lastSnapshot?.structureText)
        assertTrue(lastSnapshot?.screenshotCopy != null)
        lastSnapshot?.screenshotCopy?.recycle()
    }

    @Test
    fun `single callback starts timer and timeout publishes`() {
        val coordinator = createCoordinator()
        coordinator.begin("entry-1")
        coordinator.onStructure("entry-1", "text", 100L)
        assertTrue(timerTask != null)
        assertEquals(null, lastSnapshot)
        // 手动触发 partial timeout
        val task = timerTask
        timerTask = null
        task?.invoke()
        assertEquals("entry-1", lastSnapshot?.entryId)
        assertEquals("text", lastSnapshot?.structureText)
    }

    @Test
    fun `second callback cancels timer`() {
        val coordinator = createCoordinator()
        coordinator.begin("entry-1")
        coordinator.onStructure("entry-1", "text", 100L)
        assertTrue(timerTask != null)
        coordinator.onScreenshot("entry-1", android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888), 150L)
        assertEquals(null, timerTask) // timer was cancelled
        assertEquals("entry-1", lastSnapshot?.entryId)
        lastSnapshot?.screenshotCopy?.recycle()
    }

    @Test
    fun `publish snapshots new payload when new invocation begins`() {
        val coordinator = createCoordinator()
        coordinator.begin("entry-1")
        coordinator.onStructure("entry-1", "old-text", 100L)
        // 新 invocation 开始，旧未提交 payload 先回收；随后单回调走到 partial publish。
        coordinator.begin("entry-2")
        coordinator.onStructure("entry-2", "new-text", 200L)
        val task = timerTask
        timerTask = null
        task?.invoke()
        assertEquals("entry-2", lastSnapshot?.entryId)
        assertEquals("new-text", lastSnapshot?.structureText)
        assertFalse(lastSnapshot?.structureText == "old-text")
    }

    @Test
    fun `mismatched entry id drops`() {
        val coordinator = createCoordinator()
        coordinator.begin("entry-1")
        coordinator.onStructure("entry-other", "text", 100L)
        assertEquals(1, dropCount)
        assertEquals(null, lastSnapshot)
    }

    @Test
    fun `destroy cancels timer and recycles bitmap`() {
        val coordinator = createCoordinator()
        coordinator.begin("entry-1")
        coordinator.onScreenshot("entry-1", android.graphics.Bitmap.createBitmap(10, 10, android.graphics.Bitmap.Config.ARGB_8888), 100L)
        assertTrue(timerTask != null)
        coordinator.destroy()
        assertEquals(null, timerTask)
        // screenshot was recycled by destroy
        assertEquals(null, lastSnapshot)
    }

    @Test
    fun `events after destroy are dropped`() {
        val coordinator = createCoordinator()
        coordinator.begin("entry-1")
        coordinator.destroy()
        coordinator.onStructure("entry-1", "text", 100L)
        assertEquals(1, dropCount)
    }
}