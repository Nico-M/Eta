package fuck.andes.agent.voice

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

/**
 * Assist 生命周期健康记录；只保存固定 outcome、hasImage/hasStructure、epoch 与
 * elapsed 两个检查时间。无 entryId、包名、文本、路径、异常正文或图片 metadata，
 * 供 P3 能力诊断读取。禁止以 cache 目录是否非空推断健康。
 */
internal object EtaAssistContextHealthStore {
    const val OUTCOME_ASSIST_CONSUMED = "assist_consumed"
    const val OUTCOME_FALLBACK_STARTED = "fallback_started"
    const val OUTCOME_CANCELLED = "cancelled"
    const val OUTCOME_PARTIAL = "partial"

    const val SCHEMA = 1
    private const val DIRECTORY_NAME = "eta-assist-health"
    private const val FILE_NAME = "latest.json"
    private const val TTL_ELAPSED_MS = 30_000L

    private val ALLOWED_OUTCOMES = setOf(
        OUTCOME_ASSIST_CONSUMED,
        OUTCOME_FALLBACK_STARTED,
        OUTCOME_CANCELLED,
        OUTCOME_PARTIAL,
    )

    data class HealthRecord(
        val outcome: String,
        val hasImage: Boolean,
        val hasStructure: Boolean,
        val checkedAtEpochMs: Long,
        val checkedAtElapsedMs: Long,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("schema", SCHEMA)
            .put("outcome", outcome)
            .put("has_image", hasImage)
            .put("has_structure", hasStructure)
            .put("checked_at_epoch_ms", checkedAtEpochMs)
            .put("checked_at_elapsed_ms", checkedAtElapsedMs)
    }

    fun write(
        context: Context,
        outcome: String,
        hasImage: Boolean,
        hasStructure: Boolean,
    ) {
        if (outcome !in ALLOWED_OUTCOMES) return
        runCatching {
            val directory = File(context.applicationContext.cacheDir, DIRECTORY_NAME)
            directory.mkdirs()
            val record = HealthRecord(
                outcome = outcome,
                hasImage = hasImage,
                hasStructure = hasStructure,
                checkedAtEpochMs = System.currentTimeMillis(),
                checkedAtElapsedMs = SystemClock.elapsedRealtime(),
            )
            val marker = AtomicFile(File(directory, FILE_NAME))
            var output: FileOutputStream? = null
            try {
                output = marker.startWrite()
                output.write(record.toJson().toString().toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
                marker.finishWrite(output)
                output = null
            } catch (_: Throwable) {
                output?.let { runCatching { marker.failWrite(it) } }
            } finally {
                output?.let { runCatching { it.close() } }
            }
        }
    }

    /** 同 boot 超过 30 秒或 elapsed 倒退读取为 null，供后续诊断映射 Unknown。 */
    fun read(
        context: Context,
        nowElapsed: Long = SystemClock.elapsedRealtime(),
    ): HealthRecord? = runCatching {
        val marker = File(File(context.applicationContext.cacheDir, DIRECTORY_NAME), FILE_NAME)
        if (!marker.isFile) return null
        val json = JSONObject(marker.readText(Charsets.UTF_8))
        if (json.optInt("schema") != SCHEMA) return null
        val outcome = json.optString("outcome")
        if (outcome !in ALLOWED_OUTCOMES) return null
        val checkedAtElapsedMs = json.optLong("checked_at_elapsed_ms")
        val age = nowElapsed - checkedAtElapsedMs
        if (age < 0L || age > TTL_ELAPSED_MS) return null
        HealthRecord(
            outcome = outcome,
            hasImage = json.optBoolean("has_image"),
            hasStructure = json.optBoolean("has_structure"),
            checkedAtEpochMs = json.optLong("checked_at_epoch_ms"),
            checkedAtElapsedMs = checkedAtElapsedMs,
        )
    }.getOrNull()
}
