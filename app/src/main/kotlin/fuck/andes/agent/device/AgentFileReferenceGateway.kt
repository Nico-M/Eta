package fuck.andes.agent.device

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import fuck.andes.agent.model.AgentFileReference
import fuck.andes.agent.model.AgentFileReferenceKind
import fuck.andes.agent.model.hasUnsupportedControlCharacter
import fuck.andes.core.AgentLogger

internal class AgentFileReferenceGateway(
    private val resolveDocumentPath: (Uri) -> String? = { null },
    private val executeRootCommand: (String) -> BoundedRootCommandExecutor.Result,
) {
    constructor(logger: AgentLogger) : this(
        executeRootCommand = { command ->
            BoundedRootCommandExecutor(logger).use { executor ->
                executor.execute(
                    command = command,
                    timeoutMillis = VALIDATION_TIMEOUT_MS,
                    maxOutputBytes = MAX_VALIDATION_OUTPUT_BYTES,
                )
            }
        }
    )

    constructor(
        context: Context,
        logger: AgentLogger,
    ) : this(
        resolveDocumentPath = { uri -> queryLocalDocumentPath(context.applicationContext, uri) },
        executeRootCommand = { command ->
            BoundedRootCommandExecutor(logger).use { executor ->
                executor.execute(
                    command = command,
                    timeoutMillis = VALIDATION_TIMEOUT_MS,
                    maxOutputBytes = MAX_VALIDATION_OUTPUT_BYTES,
                )
            }
        },
    )

    fun resolveDocumentUri(
        uri: Uri,
        expectedKind: AgentFileReferenceKind,
    ): Resolution {
        val documentId = runCatching {
            if (DocumentsContract.isTreeUri(uri)) {
                DocumentsContract.getTreeDocumentId(uri)
            } else {
                DocumentsContract.getDocumentId(uri)
            }
        }.getOrNull() ?: return Resolution.Failure(Error.UnsupportedDocumentProvider)
        val mappedPath = mapPrimaryStorageDocument(uri.authority, documentId)
            ?: resolveDocumentPath(uri)
            ?: return Resolution.Failure(Error.UnsupportedDocumentProvider)
        return resolveAbsolutePath(mappedPath, expectedKind)
    }

    fun resolveAbsolutePath(
        rawPath: String,
        expectedKind: AgentFileReferenceKind? = null,
    ): Resolution {
        val path = rawPath
        if (
            path.isEmpty() ||
            !path.startsWith('/') ||
            path.hasUnsupportedControlCharacter()
        ) {
            return Resolution.Failure(Error.InvalidPath)
        }
        val result = executeRootCommand(validationCommand(path))
        if (!result.ok) {
            return Resolution.Failure(
                when {
                    result.errorCode == "ROOT_UNAVAILABLE" -> Error.RootUnavailable
                    result.timedOut -> Error.ValidationTimedOut
                    result.exitCode == EXIT_ROOT_UNAVAILABLE -> Error.RootUnavailable
                    result.exitCode == EXIT_UNSUPPORTED_TYPE -> Error.UnsupportedFileType
                    result.exitCode == EXIT_PATH_NOT_FOUND -> Error.PathNotFound
                    else -> Error.RootUnavailable
                }
            )
        }
        val outputSeparator = result.stdout.indexOf('\n')
        if (outputSeparator <= 0) return Resolution.Failure(Error.InvalidPath)
        val kind = when (result.stdout.substring(0, outputSeparator)) {
            KIND_FILE -> AgentFileReferenceKind.File
            KIND_DIRECTORY -> AgentFileReferenceKind.Directory
            else -> return Resolution.Failure(Error.UnsupportedFileType)
        }
        val canonicalPath = result.stdout.substring(outputSeparator + 1).trimEnd('\n')
        if (
            canonicalPath.isEmpty() ||
            canonicalPath.hasUnsupportedControlCharacter()
        ) {
            return Resolution.Failure(Error.InvalidPath)
        }
        if (expectedKind != null && kind != expectedKind) {
            return Resolution.Failure(Error.TypeMismatch)
        }
        return Resolution.Success(
            AgentFileReference(
                displayName = canonicalPath.substringAfterLast('/').ifBlank { canonicalPath },
                absolutePath = canonicalPath,
                kind = kind,
            )
        )
    }

    internal enum class Error {
        UnsupportedDocumentProvider,
        InvalidPath,
        PathNotFound,
        UnsupportedFileType,
        TypeMismatch,
        RootUnavailable,
        ValidationTimedOut,
    }

    internal sealed interface Resolution {
        data class Success(val reference: AgentFileReference) : Resolution
        data class Failure(val error: Error) : Resolution
    }

    internal companion object {
        const val EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY = "com.android.externalstorage.documents"
        const val SHARED_STORAGE_ROOT = "/storage/emulated/0"

        private const val MEDIA_DOCUMENTS_AUTHORITY = "com.android.providers.media.documents"

        fun mapPrimaryStorageDocument(authority: String?, documentId: String): String? {
            if (authority != EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY) return null
            if (documentId.hasUnsupportedControlCharacter()) return null
            val separator = documentId.indexOf(':')
            if (separator < 0 || !documentId.substring(0, separator).equals("primary", ignoreCase = true)) {
                return null
            }
            val relativePath = documentId.substring(separator + 1)
            if (
                relativePath.startsWith('/') ||
                relativePath.split('/').any { it == "." || it == ".." }
            ) {
                return null
            }
            return if (relativePath.isEmpty()) {
                SHARED_STORAGE_ROOT
            } else {
                "$SHARED_STORAGE_ROOT/$relativePath"
            }
        }

        private fun queryLocalDocumentPath(context: Context, uri: Uri): String? {
            queryDataColumn(context, uri)?.let { return it }
            if (
                uri.authority != EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY &&
                uri.authority != MEDIA_DOCUMENTS_AUTHORITY
            ) {
                return null
            }
            val mediaUri = try {
                MediaStore.getMediaUri(context, uri)
            } catch (_: RuntimeException) {
                null
            } ?: return null
            return queryDataColumn(context, mediaUri)
        }

        @Suppress("DEPRECATION")
        private fun queryDataColumn(context: Context, uri: Uri): String? = try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null,
                null,
                null,
            )?.use { cursor ->
                val dataIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (dataIndex >= 0 && cursor.moveToFirst() && !cursor.isNull(dataIndex)) {
                    cursor.getString(dataIndex)
                } else {
                    null
                }
            }
        } catch (_: RuntimeException) {
            // 文档提供方可以拒绝非标准列；这表示它没有可引用的本地绝对路径。
            null
        }

        private fun validationCommand(path: String): String {
            val quotedPath = shellQuote(path)
            return buildString {
                append("[ \"\$(id -u)\" = 0 ] || exit ").append(EXIT_ROOT_UNAVAILABLE).append("; ")
                append("eta_path=\$(readlink -f ").append(quotedPath).append(" 2>/dev/null) || exit ")
                append(EXIT_PATH_NOT_FOUND).append("; ")
                append("[ -n \"\$eta_path\" ] || exit ").append(EXIT_PATH_NOT_FOUND).append("; ")
                append("if [ -f \"\$eta_path\" ]; then eta_kind=").append(KIND_FILE).append("; ")
                append("elif [ -d \"\$eta_path\" ]; then eta_kind=").append(KIND_DIRECTORY).append("; ")
                append("else exit ").append(EXIT_UNSUPPORTED_TYPE).append("; fi; ")
                append("printf '%s\\n%s' \"\$eta_kind\" \"\$eta_path\"")
            }
        }

        private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        private const val KIND_FILE = "file"
        private const val KIND_DIRECTORY = "directory"
        private const val EXIT_ROOT_UNAVAILABLE = 20
        private const val EXIT_PATH_NOT_FOUND = 21
        private const val EXIT_UNSUPPORTED_TYPE = 23
        private const val VALIDATION_TIMEOUT_MS = 5_000L
        private const val MAX_VALIDATION_OUTPUT_BYTES = 8 * 1024
    }
}
