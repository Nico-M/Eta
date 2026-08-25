package fuck.andes.agent.tool

import android.content.Context
import fuck.andes.agent.device.BoundedRootCommandExecutor
import fuck.andes.agent.media.AgentImageCodec
import fuck.andes.agent.media.MAX_AGENT_IMAGE_BYTES
import fuck.andes.agent.model.AgentModelClient
import java.io.File
import org.json.JSONObject

/** 读取用户已明确指定的单张图片，并以临时视觉附件交给当前模型回合。 */
internal class AgentImageTools(
    private val context: Context,
    private val root: BoundedRootCommandExecutor,
) {
    fun readImage(args: JSONObject): AgentModelClient.ToolResult {
        val source = args.getString("path").removePrefix("file://")
        val sourceKind = when {
            source.startsWith("content://media/") -> ImageSourceKind.MediaUri
            source.startsWith("/") && !source.contains('\u0000') -> ImageSourceKind.File
            else -> return sensitive(error("IMAGE_PATH_DENIED", "图片路径必须是绝对路径、file URI 或系统相册 URI"))
        }
        val temporaryFile = runCatching {
            File.createTempFile("eta-read-image-", ".img", imageCacheDirectory())
        }.getOrElse {
            return sensitive(error("IMAGE_TEMPORARY_FILE_FAILED", "无法创建图片临时文件"))
        }
        return try {
            val copyResult = root.execute(
                imageCopyCommand(source, sourceKind, temporaryFile),
                timeoutMillis = READ_TIMEOUT_MS,
                maxOutputBytes = 8 * 1024,
            )
            if (!copyResult.ok) {
                sensitive(copyFailure(copyResult))
            } else {
                val image = AgentImageCodec.fromToolFile(
                    file = temporaryFile,
                    source = "tool_read_image",
                ) ?: return sensitive(error("IMAGE_UNSUPPORTED", "文件不是可识别的图片"))
                sensitive(
                    content = JSONObject()
                        .put("ok", true)
                        .put("tool", "read_image")
                        .put("path", source)
                        .put("image_attached", true)
                        .toString(),
                    images = listOf(image),
                )
            }
        } finally {
            temporaryFile.delete()
        }
    }

    private fun imageCopyCommand(
        source: String,
        sourceKind: ImageSourceKind,
        destination: File,
    ): String = when (sourceKind) {
        ImageSourceKind.File -> "[ -f ${shellQuote(source)} ] || exit 21; " +
            "[ \"$(stat -c %s ${shellQuote(source)})\" -le $MAX_IMAGE_FILE_BYTES ] || exit 22; " +
            "cp ${shellQuote(source)} ${shellQuote(destination.absolutePath)} || exit 23"
        ImageSourceKind.MediaUri -> "content read --uri ${shellQuote(source)} 2>/dev/null | " +
            "head -c ${MAX_IMAGE_FILE_BYTES + 1L} > ${shellQuote(destination.absolutePath)} && " +
            "[ \"$(stat -c %s ${shellQuote(destination.absolutePath)})\" -le $MAX_IMAGE_FILE_BYTES ]"
    }

    private fun imageCacheDirectory(): File =
        context.externalCacheDir
            ?.takeIf { it.isDirectory || it.mkdirs() }
            ?: context.cacheDir

    private fun copyFailure(result: BoundedRootCommandExecutor.Result): String = when (result.exitCode) {
        21 -> error("IMAGE_SOURCE_UNAVAILABLE", "图片源文件不存在或当前不可读")
        22 -> error("IMAGE_TOO_LARGE", "图片超过大小限制")
        23 -> error("IMAGE_STAGE_FAILED", "Root 无法将图片复制到 Eta 临时缓存")
        else -> error("IMAGE_UNAVAILABLE", "图片读取失败")
    }

    private fun error(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()

    private fun sensitive(
        content: String,
        images: List<AgentModelClient.ModelImage> = emptyList(),
    ) = AgentModelClient.ToolResult(content = content, images = images, sensitive = true)

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private enum class ImageSourceKind {
        File,
        MediaUri,
    }

    private companion object {
        const val READ_TIMEOUT_MS = 15_000L
        const val MAX_IMAGE_FILE_BYTES = MAX_AGENT_IMAGE_BYTES.toLong()
    }
}
