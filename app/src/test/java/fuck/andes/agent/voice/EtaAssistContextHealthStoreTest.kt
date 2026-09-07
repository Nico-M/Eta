package fuck.andes.agent.voice

import android.content.Context
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EtaAssistContextHealthStoreTest {
    private lateinit var context: Context
    private lateinit var directory: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        directory = File(context.cacheDir, "eta-assist-health")
        directory.mkdirs()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `only allowed outcomes are accepted`() {
        EtaAssistContextHealthStore.write(context, "unknown_outcome", true, true)
        assertNull(EtaAssistContextHealthStore.read(context))
        EtaAssistContextHealthStore.write(
            context,
            EtaAssistContextHealthStore.OUTCOME_ASSIST_CONSUMED,
            hasImage = true,
            hasStructure = true,
        )
        assertEquals(
            EtaAssistContextHealthStore.OUTCOME_ASSIST_CONSUMED,
            EtaAssistContextHealthStore.read(context)?.outcome,
        )
    }

    @Test
    fun `round trip omits entry id text paths and image metadata`() {
        EtaAssistContextHealthStore.write(
            context,
            EtaAssistContextHealthStore.OUTCOME_PARTIAL,
            hasImage = false,
            hasStructure = true,
        )
        val raw = File(directory, "latest.json").readText(Charsets.UTF_8)
        listOf("entry_id", "screen_context", "image_name", "image_mime", "image_bytes", "path", "text").forEach { key ->
            assertTrue("$key leaked: $raw", !raw.contains(key))
        }
        val record = requireNotNull(EtaAssistContextHealthStore.read(context))
        assertEquals(false, record.hasImage)
        assertEquals(true, record.hasStructure)
    }

    @Test
    fun `older than 30s or elapsed backwards reads null`() {
        EtaAssistContextHealthStore.write(
            context,
            EtaAssistContextHealthStore.OUTCOME_CANCELLED,
            hasImage = false,
            hasStructure = false,
        )
        val checkedAtElapsed = requireNotNull(EtaAssistContextHealthStore.read(context)).checkedAtElapsedMs
        assertNull(EtaAssistContextHealthStore.read(context, nowElapsed = checkedAtElapsed + 30_001))
        assertNull(EtaAssistContextHealthStore.read(context, nowElapsed = checkedAtElapsed - 1))
    }

    @Test
    fun `bad schema reads null`() {
        EtaAssistContextHealthStore.write(
            context,
            EtaAssistContextHealthStore.OUTCOME_FALLBACK_STARTED,
            hasImage = false,
            hasStructure = false,
        )
        val marker = File(directory, "latest.json")
        val json = org.json.JSONObject(marker.readText(Charsets.UTF_8))
        json.put("schema", 99)
        marker.writeText(json.toString())
        assertNull(EtaAssistContextHealthStore.read(context))
    }
}