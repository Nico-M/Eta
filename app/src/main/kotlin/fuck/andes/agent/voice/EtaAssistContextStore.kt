package fuck.andes.agent.voice

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import fuck.andes.agent.media.AgentModelImageEncoder
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.json.JSONObject

/**
 * Assist 回调的跨组件文件提交协议；metadata commit marker 提供单消费者语义。
 *
 * 图片先同目录写 `.tmp`、`flush()`、`fd.sync()` 再 rename 为随机名 `$entryId.$uuid.image`；
 * 最后用 [AtomicFile] 提交 schema 1 metadata `$entryId.json` 作为 commit marker，异常调用
 * `failWrite()` 并删除该 entry 已写图片和 temp。`consume` 先把 marker 原子改名为
 * `$entryId.consuming` 取得单消费者所有权，只读一次 metadata；成功或失败都删除该 entry
 * 的 image → consuming marker → temp，绝不留下本 entry 文件。
 */
internal class EtaAssistContextStore(context: Context) {
    private val directory = File(context.applicationContext.cacheDir, DIRECTORY_NAME)

    fun publish(
        entryId: String,
        screenContextText: String,
        image: AgentModelImageEncoder.EncodedImage?,
    ): Boolean {
        if (!entryId.isSafeEntryId()) return false
        directory.mkdirs()
        val boundedText = screenContextText.take(EtaAssistContextProjector.MAX_TOTAL_CHARS)
        val imageName = image?.let { encoded ->
            if (encoded.bytes.size > MAX_IMAGE_BYTES) return false
            val name = "$entryId.${UUID.randomUUID()}.image"
            writeImageAtomically(File(directory, name), encoded.bytes)
            name
        }.orEmpty()
        val metadata = JSONObject()
            .put("schema", SCHEMA)
            .put("entry_id", entryId)
            .put("created_elapsed", SystemClock.elapsedRealtime())
            .put("screen_context_text", boundedText)
            .put("image_name", imageName)
            .put("image_mime", image?.mimeType ?: "")
            .put("image_bytes", image?.bytes?.size ?: 0)
            .put("image_width", image?.width ?: 0)
            .put("image_height", image?.height ?: 0)
        val marker = AtomicFile(File(directory, "$entryId.json"))
        var output: FileOutputStream? = null
        return try {
            output = marker.startWrite()
            output.write(metadata.toString().toByteArray(StandardCharsets.UTF_8))
            output.flush()
            output.fd.sync()
            marker.finishWrite(output)
            output = null
            true
        } catch (_: Throwable) {
            output?.let { runCatching { marker.failWrite(it) } }
            output = null
            runCatching { File(directory, imageName).delete() }
            false
        } finally {
            output?.let { runCatching { it.close() } }
        }
    }

    fun consume(entryId: String, nowElapsed: Long = SystemClock.elapsedRealtime()): Consumed? {
        if (!entryId.isSafeEntryId() || !directory.isDirectory) return null
        val marker = file("$entryId.json")
        val consuming = file("$entryId.consuming")
        if (!marker.renameTo(consuming)) return null
        var ownedImageName: String? = null
        try {
            val json = runCatching {
                JSONObject(consuming.readText(StandardCharsets.UTF_8))
            }.getOrNull() ?: return null
            // 无论后续校验是否通过，都在 finally 清理 image。
            val name = json.optString("image_name").takeIf(String::isNotBlank)
            ownedImageName = name
            if (json.optInt("schema") != SCHEMA) return null
            if (json.optString("entry_id") != entryId) return null
            val age = nowElapsed - json.optLong("created_elapsed")
            if (age < 0L || age > TTL_ELAPSED_MS) return null
            val mime = json.optString("image_mime")
            val bytes = json.optInt("image_bytes")
            val width = json.optInt("image_width")
            val height = json.optInt("image_height")
            if (name == null) {
                if (mime.isNotEmpty() || bytes != 0 || width != 0 || height != 0) return null
                return Consumed(
                    entryId = entryId,
                    screenContextText = json.optString("screen_context_text")
                        .take(EtaAssistContextProjector.MAX_TOTAL_CHARS),
                    imageBytes = null,
                    imageMimeType = "",
                    imageWidth = 0,
                    imageHeight = 0,
                )
            }
            if (!name.matches(IMAGE_NAME_PATTERN) || !name.startsWith("$entryId.")) return null
            val imageFile = file(name)
            val canonicalParent = imageFile.canonicalFile.parentFile?.canonicalPath
            if (canonicalParent != directory.canonicalPath) return null
            if (!imageFile.isFile || imageFile.length() != bytes.toLong()) return null
            if (bytes <= 0 || bytes > MAX_IMAGE_BYTES) return null
            if (mime != IMAGE_MIME || width <= 0 || height <= 0) return null
            val imageBytes = imageFile.readBytes()
            if (imageBytes.size != bytes) return null
            return Consumed(
                entryId = entryId,
                screenContextText = json.optString("screen_context_text")
                    .take(EtaAssistContextProjector.MAX_TOTAL_CHARS),
                imageBytes = imageBytes,
                imageMimeType = mime,
                imageWidth = width,
                imageHeight = height,
            )
        } catch (_: Throwable) {
            return null
        } finally {
            // image → consuming marker → temp 顺序删除，成功或失败都不留本 entry 文件。
            runCatching { consuming.delete() }
            runCatching { ownedImageName?.let(::file)?.delete() }
            directory.listFiles().orEmpty()
                .filter { it.name.startsWith("$entryId.") && it.name.endsWith(".tmp") }
                .forEach { runCatching { it.delete() } }
        }
    }

    /** 复用 [consume] 同一套安全删除逻辑；只清该 entry 文件，绝不触碰其它 entry。 */
    fun discard(entryId: String) {
        if (!entryId.isSafeEntryId() || !directory.isDirectory) return
        file("$entryId.json").delete()
        file("$entryId.consuming").delete()
        directory.listFiles().orEmpty()
            .filter { it.name.startsWith("$entryId.") }
            .forEach { runCatching { it.delete() } }
    }

    /**
     * 只清 crash residue；wall clock 只能辅助清垃圾，绝不决定有效记录能否消费。
     *
     * JSON marker 仅以 metadata 的 elapsed 判断 30 秒产品 TTL；elapsed 倒退视为重启后的
     * 不可消费记录并删除。`.tmp/.consuming` 及无 marker 引用的 orphan image 只有在
     * `nowEpochMs - lastModified >= 24h` 时删除，绝不删除刚取得所有权的 `.consuming`。
     */
    fun prune(
        nowElapsed: Long = SystemClock.elapsedRealtime(),
        nowEpochMs: Long = System.currentTimeMillis(),
    ) {
        if (!directory.isDirectory) return
        val referencedImages = directory.listFiles().orEmpty()
            .filter { it.name.endsWith(".json") }
            .mapNotNull { marker ->
                runCatching {
                    JSONObject(marker.readText(StandardCharsets.UTF_8)).optString("image_name")
                }.getOrNull()
            }
            .filter(String::isNotBlank)
            .toSet()
        directory.listFiles().orEmpty().forEach { candidate ->
            val name = candidate.name
            if (name.endsWith(".json")) {
                val createdElapsed = runCatching {
                    JSONObject(candidate.readText(StandardCharsets.UTF_8)).optLong("created_elapsed")
                }.getOrDefault(0L)
                if (createdElapsed <= 0L) {
                    candidate.delete()
                } else {
                    val age = nowElapsed - createdElapsed
                    if (age < 0L || age > TTL_ELAPSED_MS) candidate.delete()
                }
                return@forEach
            }
            // 只有 crash residue 走 wall clock；`.consuming` 可能是刚取得所有权的活跃 owner，
            // 24h 阈值确保绝不误删在途消费。
            if (name.endsWith(".tmp") || name.endsWith(".consuming")) {
                if (nowEpochMs - candidate.lastModified() >= PRUNE_WALL_MS) candidate.delete()
                return@forEach
            }
            if (name.endsWith(".image") && name !in referencedImages) {
                if (nowEpochMs - candidate.lastModified() >= PRUNE_WALL_MS) candidate.delete()
            }
        }
    }

    private fun file(name: String): File = File(directory, name)

    private fun writeImageAtomically(target: File, bytes: ByteArray) {
        val temp = File(directory, ".${target.name}.${UUID.randomUUID()}.tmp")
        FileOutputStream(temp).use { stream ->
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
        }
        check(temp.renameTo(target)) { "Assist context image commit failed" }
    }

    data class Consumed(
        val entryId: String,
        val screenContextText: String,
        val imageBytes: ByteArray?,
        val imageMimeType: String,
        val imageWidth: Int,
        val imageHeight: Int,
    )

    private companion object {
        const val DIRECTORY_NAME = "eta-assist-context"
        const val SCHEMA = 1
        const val TTL_ELAPSED_MS = 30_000L
        const val PRUNE_WALL_MS = 24L * 60L * 60L * 1000L
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
        const val IMAGE_MIME = "image/jpeg"
        val IMAGE_NAME_PATTERN = Regex("[A-Za-z0-9-]+\\.[0-9a-f-]+\\.image")
    }
}

private fun String.isSafeEntryId(): Boolean =
    length in 1..80 && matches(Regex("[A-Za-z0-9-]+"))
