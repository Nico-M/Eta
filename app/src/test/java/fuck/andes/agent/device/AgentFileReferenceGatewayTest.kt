package fuck.andes.agent.device

import android.net.Uri
import fuck.andes.agent.model.AgentFileReference
import fuck.andes.agent.model.AgentFileReferenceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AgentFileReferenceGatewayTest {
    @Test
    fun mapPrimaryStorageDocument_acceptsPrimaryVolumeOnly() {
        assertEquals(
            "/storage/emulated/0/Download/report.txt",
            AgentFileReferenceGateway.mapPrimaryStorageDocument(
                AgentFileReferenceGateway.EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY,
                "primary:Download/report.txt",
            ),
        )
        assertEquals(
            "/storage/emulated/0",
            AgentFileReferenceGateway.mapPrimaryStorageDocument(
                AgentFileReferenceGateway.EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY,
                "primary:",
            ),
        )
        assertNull(
            AgentFileReferenceGateway.mapPrimaryStorageDocument(
                "com.android.providers.downloads.documents",
                "primary:Download/report.txt",
            )
        )
        assertNull(
            AgentFileReferenceGateway.mapPrimaryStorageDocument(
                AgentFileReferenceGateway.EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY,
                "1234-5678:report.txt",
            )
        )
    }

    @Test
    fun mapPrimaryStorageDocument_rejectsTraversalAndControlCharacters() {
        assertNull(
            AgentFileReferenceGateway.mapPrimaryStorageDocument(
                AgentFileReferenceGateway.EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY,
                "primary:Download/../secret",
            )
        )
        assertNull(
            AgentFileReferenceGateway.mapPrimaryStorageDocument(
                AgentFileReferenceGateway.EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY,
                "primary:Download/bad\nname",
            )
        )
    }

    @Test
    fun resolveAbsolutePath_returnsCanonicalFileAndDirectory() {
        val fileGateway = AgentFileReferenceGateway {
            success("file\n/storage/emulated/0/Download/report.txt")
        }
        val directoryGateway = AgentFileReferenceGateway {
            success("directory\n/data/local/tmp/project")
        }

        assertEquals(
            AgentFileReferenceGateway.Resolution.Success(
                AgentFileReference(
                    displayName = "report.txt",
                    absolutePath = "/storage/emulated/0/Download/report.txt",
                    kind = AgentFileReferenceKind.File,
                )
            ),
            fileGateway.resolveAbsolutePath(
                "/storage/emulated/0/Download/report.txt",
                AgentFileReferenceKind.File,
            ),
        )
        assertEquals(
            AgentFileReferenceGateway.Resolution.Success(
                AgentFileReference(
                    displayName = "project",
                    absolutePath = "/data/local/tmp/project",
                    kind = AgentFileReferenceKind.Directory,
                )
            ),
            directoryGateway.resolveAbsolutePath("/data/local/tmp/project"),
        )
    }

    @Test
    fun resolveAbsolutePath_mapsRootAndFilesystemFailures() {
        assertFailure(
            expected = AgentFileReferenceGateway.Error.RootUnavailable,
            result = AgentFileReferenceGateway {
                BoundedRootCommandExecutor.Result.failed("ROOT_UNAVAILABLE")
            }.resolveAbsolutePath("/data/local/tmp/file"),
        )
        assertFailure(
            expected = AgentFileReferenceGateway.Error.RootUnavailable,
            result = AgentFileReferenceGateway { failed(exitCode = 20) }
                .resolveAbsolutePath("/data/local/tmp/file"),
        )
        assertFailure(
            expected = AgentFileReferenceGateway.Error.UnsupportedFileType,
            result = AgentFileReferenceGateway { failed(exitCode = 23) }
                .resolveAbsolutePath("/data/local/tmp/socket"),
        )
        assertFailure(
            expected = AgentFileReferenceGateway.Error.PathNotFound,
            result = AgentFileReferenceGateway { failed(exitCode = 21) }
                .resolveAbsolutePath("/data/local/tmp/missing"),
        )
        assertFailure(
            expected = AgentFileReferenceGateway.Error.ValidationTimedOut,
            result = AgentFileReferenceGateway {
                failed(exitCode = -2, timedOut = true)
            }.resolveAbsolutePath("/data/local/tmp/file"),
        )
        assertEquals(
            AgentFileReferenceGateway.Resolution.Success(
                AgentFileReference(
                    displayName = "secret",
                    absolutePath = "/data/adb/secret",
                    kind = AgentFileReferenceKind.File,
                )
            ),
            AgentFileReferenceGateway {
                success("file\n/data/adb/secret")
            }.resolveAbsolutePath("/data/adb/secret"),
        )
    }

    @Test
    fun resolveAbsolutePath_rejectsInvalidInputAndTypeMismatch() {
        var executed = false
        val invalidGateway = AgentFileReferenceGateway {
            executed = true
            success("file\n/data/local/tmp/file")
        }
        assertFailure(
            expected = AgentFileReferenceGateway.Error.InvalidPath,
            result = invalidGateway.resolveAbsolutePath("relative/path"),
        )
        assertFailure(
            expected = AgentFileReferenceGateway.Error.InvalidPath,
            result = invalidGateway.resolveAbsolutePath("/data/local/tmp/bad\npath"),
        )
        assertFalse(executed)

        assertFailure(
            expected = AgentFileReferenceGateway.Error.TypeMismatch,
            result = AgentFileReferenceGateway {
                success("directory\n/data/local/tmp/project")
            }.resolveAbsolutePath("/data/local/tmp/project", AgentFileReferenceKind.File),
        )
    }

    @Test
    fun resolveAbsolutePath_shellQuotesUntrustedPath() {
        var command = ""
        val path = "/data/local/tmp/a'\$(touch pwned)"
        val gateway = AgentFileReferenceGateway { captured ->
            command = captured
            success("file\n$path")
        }

        val result = gateway.resolveAbsolutePath(path)

        assertTrue(result is AgentFileReferenceGateway.Resolution.Success)
        assertTrue(command.contains("'/data/local/tmp/a'\\''\$(touch pwned)'"))
    }

    @Test
    fun resolveDocumentUri_usesLocalPathResolvedFromMediaDocument() {
        val uri = Uri.parse(
            "content://com.android.providers.media.documents/document/image%3A24708"
        )
        val gateway = AgentFileReferenceGateway(
            resolveDocumentPath = { selectedUri ->
                assertEquals(uri, selectedUri)
                "/storage/emulated/0/Pictures/Screenshots/example.jpg"
            },
            executeRootCommand = {
                success("file\n/storage/emulated/0/Pictures/Screenshots/example.jpg")
            },
        )

        assertEquals(
            AgentFileReferenceGateway.Resolution.Success(
                AgentFileReference(
                    displayName = "example.jpg",
                    absolutePath = "/storage/emulated/0/Pictures/Screenshots/example.jpg",
                    kind = AgentFileReferenceKind.File,
                )
            ),
            gateway.resolveDocumentUri(uri, AgentFileReferenceKind.File),
        )
    }

    @Test
    fun resolveDocumentUri_rejectsProviderWithoutLocalPath() {
        val gateway = AgentFileReferenceGateway(
            resolveDocumentPath = { null },
            executeRootCommand = { error("不应执行 Root 校验") },
        )

        assertFailure(
            expected = AgentFileReferenceGateway.Error.UnsupportedDocumentProvider,
            result = gateway.resolveDocumentUri(
                Uri.parse("content://cloud.example/document/remote%3A1"),
                AgentFileReferenceKind.File,
            ),
        )
    }

    private fun assertFailure(
        expected: AgentFileReferenceGateway.Error,
        result: AgentFileReferenceGateway.Resolution,
    ) {
        assertEquals(expected, (result as AgentFileReferenceGateway.Resolution.Failure).error)
    }

    private fun success(stdout: String) = BoundedRootCommandExecutor.Result(
        exitCode = 0,
        stdout = stdout,
        stderr = "",
        timedOut = false,
        truncated = false,
    )

    private fun failed(
        exitCode: Int,
        timedOut: Boolean = false,
    ) = BoundedRootCommandExecutor.Result(
        exitCode = exitCode,
        stdout = "",
        stderr = "",
        timedOut = timedOut,
        truncated = false,
    )
}
