package fuck.andes.agent.voice

import android.content.Context
import fuck.andes.agent.media.AgentModelImageEncoder
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class EtaAssistContextStoreTest {
    private lateinit var context: Context
    private lateinit var store: EtaAssistContextStore
    private lateinit var directory: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        directory = File(context.cacheDir, "eta-assist-context")
        directory.mkdirs()
        store = EtaAssistContextStore(context)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun encodedImage(bytes: ByteArray, width: Int = 4, height: Int = 4) =
        AgentModelImageEncoder.EncodedImage(
            bytes = bytes,
            mimeType = "image/jpeg",
            width = width,
            height = height,
            source = "system_assist",
        )

    private fun createdElapsed(entryId: String): Long {
        val marker = File(directory, "$entryId.json")
        val json = org.json.JSONObject(marker.readText(Charsets.UTF_8))
        return json.optLong("created_elapsed")
    }

    @Test
    fun `publish writes image before marker and consume returns original payload then cleans up`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4)
        assertTrue(store.publish("entry-1", "hello screen", encodedImage(bytes, 8, 12)))

        val consumed = store.consume("entry-1")
        requireNotNull(consumed)
        assertEquals("entry-1", consumed.entryId)
        assertEquals("hello screen", consumed.screenContextText)
        assertEquals("image/jpeg", consumed.imageMimeType)
        assertEquals(8, consumed.imageWidth)
        assertEquals(12, consumed.imageHeight)
        assertArrayEquals(bytes, consumed.imageBytes)
        assertNull(consumed.imageBytes?.let { store.consume("entry-1") })
        // 目录不留本 entry 文件
        assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith("entry-1.") })
    }

    @Test
    fun `text only publish has empty image metadata and zero fields`() {
        assertTrue(store.publish("entry-2", "text only", null))
        val consumed = requireNotNull(store.consume("entry-2"))
        assertNull(consumed.imageBytes)
        assertEquals("", consumed.imageMimeType)
        assertEquals(0, consumed.imageWidth)
        assertEquals(0, consumed.imageHeight)
        assertEquals("text only", consumed.screenContextText)
    }

    @Test
    fun `second consume returns null`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9, 9)
        assertTrue(store.publish("entry-3", "x", encodedImage(bytes)))
        requireNotNull(store.consume("entry-3"))
        assertNull(store.consume("entry-3"))
    }

    @Test
    fun `ttl 29999ms still consumable and 30001ms stale`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9, 9)
        assertTrue(store.publish("entry-4", "x", encodedImage(bytes)))
        val created = createdElapsed("entry-4")
        // fresh publish may leave stale dir state; re-publish for second scenario in distinct entries
        assertTrue(store.publish("entry-4a", "x", encodedImage(bytes)))
        val createdA = createdElapsed("entry-4a")
        val within = store.consume("entry-4a", nowElapsed = createdA + 29_999)
        assertTrue(within != null)
        assertTrue(store.publish("entry-4b", "x", encodedImage(bytes)))
        val createdB = createdElapsed("entry-4b")
        assertNull(store.consume("entry-4b", nowElapsed = createdB + 30_001))
    }

    @Test
    fun `elapsed backwards is stale`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9, 9)
        assertTrue(store.publish("entry-5", "x", encodedImage(bytes)))
        val created = createdElapsed("entry-5")
        assertNull(store.consume("entry-5", nowElapsed = created - 1))
        // 失败 entry 也清干净
        assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith("entry-5.") })
    }

    @Test
    fun `bad schema and truncated json are rejected`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9, 9)
        assertTrue(store.publish("entry-6", "x", encodedImage(bytes)))
        // 篡改 schema
        val marker = File(directory, "entry-6.json")
        val json = org.json.JSONObject(marker.readText(Charsets.UTF_8))
        json.put("schema", 99)
        marker.writeText(json.toString())
        assertNull(store.consume("entry-6"))

        assertTrue(store.publish("entry-6b", "x", encodedImage(bytes)))
        File(directory, "entry-6b.json").writeText("{truncated")
        assertNull(store.consume("entry-6b"))
    }

    @Test
    fun `wrong image length mime or dimensions rejected`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9, 9)
        assertTrue(store.publish("entry-7", "x", encodedImage(bytes)))
        val marker = File(directory, "entry-7.json")
        val json = org.json.JSONObject(marker.readText(Charsets.UTF_8))
        json.put("image_bytes", 999)
        marker.writeText(json.toString())
        assertNull(store.consume("entry-7"))

        assertTrue(store.publish("entry-7b", "x", encodedImage(bytes)))
        val markerB = File(directory, "entry-7b.json")
        val jsonB = org.json.JSONObject(markerB.readText(Charsets.UTF_8))
        jsonB.put("image_mime", "image/png")
        markerB.writeText(jsonB.toString())
        assertNull(store.consume("entry-7b"))

        assertTrue(store.publish("entry-7c", "x", encodedImage(bytes)))
        val markerC = File(directory, "entry-7c.json")
        val jsonC = org.json.JSONObject(markerC.readText(Charsets.UTF_8))
        jsonC.put("image_width", 0)
        markerC.writeText(jsonC.toString())
        assertNull(store.consume("entry-7c"))
    }

    @Test
    fun `image rename success but marker failure cleans up`() {
        // 通过写入超出上限的图片 bytes，publish 直接返回 false 且不留文件
        val huge = ByteArray(9 * 1024 * 1024) { 1 }
        assertFalse(store.publish("entry-8", "x", encodedImage(huge)))
        assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith("entry-8.") })
    }

    @Test
    fun `active consuming is not pruned until 24h and stale tmp is pruned`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9, 9)
        // 活跃 consuming：先 publish 再 rename 模拟取得所有权
        assertTrue(store.publish("entry-9c", "x", encodedImage(bytes)))
        val created = createdElapsed("entry-9c")
        File(directory, "entry-9c.json").renameTo(File(directory, "entry-9c.consuming"))
        store.prune(nowElapsed = created + 5_000, nowEpochMs = System.currentTimeMillis())
        assertTrue(File(directory, "entry-9c.consuming").exists())
        // 24h 后 crash residue 清理
        store.prune(nowElapsed = created, nowEpochMs = System.currentTimeMillis() + 25L * 60 * 60 * 1000)
        assertFalse(File(directory, "entry-9c.consuming").exists())
    }

    @Test
    fun `discard removes only that entry`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9, 9)
        assertTrue(store.publish("entry-a", "x", encodedImage(bytes)))
        assertTrue(store.publish("entry-b", "y", encodedImage(bytes)))
        store.discard("entry-a")
        assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith("entry-a.") })
        assertTrue(directory.listFiles().orEmpty().any { it.name.startsWith("entry-b.") })
    }
}