package fuck.andes.agent.media

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentImageCodecTest {
    @Test
    fun screenCopyUsesFullResolutionLosslessWebp() {
        val bitmap = patternedBitmap(width = 1_200, height = 2_400)
        try {
            val image = AgentImageCodec.fromScreenBitmap(bitmap, source = "screen")
            val width = image.width ?: error("缺少图片宽度")
            val height = image.height ?: error("缺少图片高度")
            val decoded = BitmapFactory.decodeByteArray(
                image.reference.decodeDataUrl(),
                0,
                image.bytes,
            ) ?: error("无法解码模型截图")

            assertEquals("image/webp", image.mimeType)
            assertTrue(image.reference.startsWith("data:image/webp;base64,"))
            assertEquals(bitmap.width, width)
            assertEquals(bitmap.height, height)
            assertTrue(bitmap.sameAs(decoded))
            decoded.recycle()
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun assistantScreenContextUsesBoundedJpeg() {
        val bitmap = patternedBitmap(width = 1_440, height = 3_200)
        try {
            val image = AgentImageCodec.fromScreenContextBitmap(
                bitmap,
                source = "screen_context",
            )
            val width = image.width ?: error("缺少图片宽度")
            val height = image.height ?: error("缺少图片高度")

            assertEquals("image/jpeg", image.mimeType)
            assertTrue(image.reference.startsWith("data:image/jpeg;base64,"))
            assertTrue(maxOf(width, height) <= 1_600)
            assertTrue(width.toLong() * height <= 1_500_000L)
            assertTrue(image.bytes < 1_000_000)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun encodedScreenBytesNeverLosePixelsOrDimensions() {
        val bitmap = patternedBitmap(width = 900, height = 1_800)
        val png = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        try {
            val image = AgentImageCodec.fromScreenBytes(png, source = "screen")
            val encoded = image.reference.decodeDataUrl()
            val decoded = BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
                ?: error("无法解码模型截图")

            assertEquals(bitmap.width, image.width)
            assertEquals(bitmap.height, image.height)
            assertTrue(bitmap.sameAs(decoded))
            decoded.recycle()
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun largeAttachmentKeepsOriginalBytesAndDimensions() {
        val bitmap = patternedBitmap(width = 2_400, height = 1_600)
        val original = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)
            output.toByteArray()
        }
        bitmap.recycle()

        val image = AgentImageCodec.fromAttachmentBytes(
            bytes = original,
            source = "user_attach",
            mimeHint = "image/jpeg",
        )
        val width = image.width ?: error("缺少图片宽度")
        val height = image.height ?: error("缺少图片高度")

        assertEquals("image/jpeg", image.mimeType)
        assertEquals(2_400, width)
        assertEquals(1_600, height)
        assertArrayEquals(original, image.reference.decodeDataUrl())
    }

    @Test
    fun smallAttachmentKeepsItsOriginalEncoding() {
        val bitmap = patternedBitmap(width = 320, height = 160)
        val original = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        bitmap.recycle()

        val image = AgentImageCodec.fromAttachmentBytes(
            bytes = original,
            source = "user_attach",
            mimeHint = "image/png",
        )
        val width = image.width ?: error("缺少图片宽度")
        val height = image.height ?: error("缺少图片高度")

        assertEquals("image/png", image.mimeType)
        assertEquals(320, width)
        assertEquals(160, height)
        assertArrayEquals(original, image.reference.decodeDataUrl())
    }

    @Test
    fun fileToolImageIsDownscaledForMultiImageRequests() {
        val bitmap = patternedBitmap(width = 2_400, height = 1_600)
        val jpeg = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)
            output.toByteArray()
        }
        val file = File.createTempFile("tool-vision-", ".jpg")
        try {
            file.writeBytes(jpeg)
            val image = AgentImageCodec.fromToolFile(file, source = "tool_file")
                ?: error("无法从文件编码工具图片")
            val width = image.width ?: error("缺少图片宽度")
            val height = image.height ?: error("缺少图片高度")

            assertEquals("image/jpeg", image.mimeType)
            assertTrue(maxOf(width, height) <= 1_600)
            assertTrue(width * height <= 1_500_000)
        } finally {
            file.delete()
        }
    }

    @Test
    fun chatPreviewIsIndependentFromTheOriginalFile() {
        val context = RuntimeEnvironment.getApplication()
        val bitmap = patternedBitmap(width = 1_200, height = 800)
        val png = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        bitmap.recycle()

        val image = AgentImageCodec.fromAttachmentBytes(
            bytes = png,
            source = "user_attach",
            mimeHint = "image/png",
        )
        val width = image.width ?: error("缺少图片宽度")
        val height = image.height ?: error("缺少图片高度")

        val preview = AgentImageCodec.previewFromReference(context, image)
            ?: error("无法从附件生成预览")

        assertEquals("image/jpeg", preview.mimeType)
        assertTrue(maxOf(preview.width!!, preview.height!!) <= 512)
        assertTrue(width >= 1_200)
        assertTrue(height >= 800)
    }

    @Test
    fun pickedAttachmentPreviewDoesNotReopenThePickerUri() {
        val context = RuntimeEnvironment.getApplication()
        val sourceFile = File(context.cacheDir, "picked-image-${System.nanoTime()}.jpg")
        val bitmap = patternedBitmap(width = 1_200, height = 800)
        FileOutputStream(sourceFile).use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)
        }
        bitmap.recycle()

        val attachment = AgentImageCodec.fromReference(
            context = context,
            value = sourceFile.absolutePath,
            source = "user_attach",
        ) ?: error("无法读取测试图片")
        assertTrue(sourceFile.delete())

        val preview = AgentImageCodec.previewFromReference(context, attachment)
            ?: error("无法从已读取的附件生成预览")

        assertEquals("image/jpeg", preview.mimeType)
        assertTrue(maxOf(preview.width!!, preview.height!!) <= 512)
    }

    @Test
    fun pickerUriFallsBackToTypedAssetStream() {
        val context = RuntimeEnvironment.getApplication()
        val sourceFile = File(context.cacheDir, "typed-picker-${System.nanoTime()}.jpg")
        val bitmap = patternedBitmap(width = 873, height = 1_920)
        FileOutputStream(sourceFile).use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        }
        bitmap.recycle()
        val authority = "fuck.andes.test.picker.${System.nanoTime()}"
        val provider = TypedImageProvider(sourceFile)
        provider.attachInfo(
            context,
            ProviderInfo().apply { this.authority = authority },
        )
        ShadowContentResolver.registerProviderInternal(authority, provider)
        try {
            val uri = Uri.parse("content://$authority/image")
            val image = AgentImageCodec.fromReference(
                context = context,
                value = uri.toString(),
                source = "user_attach",
            ) ?: error("无法读取 TypedImageProvider 图片")
            assertEquals("image/jpeg", image.mimeType)
            assertEquals(873, image.width)
            assertEquals(1_920, image.height)
        } finally {
            sourceFile.delete()
        }
    }

    @Test
    fun screenContextFromRootBytesRespectsBoundedContract() {
        val bitmap = patternedBitmap(width = 1_440, height = 3_200)
        val png = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        bitmap.recycle()

        val image = AgentImageCodec.fromScreenContextBytes(png, source = "screen_context")
        val width = image.width ?: error("missing width")
        val height = image.height ?: error("missing height")

        assertEquals("image/jpeg", image.mimeType)
        assertTrue(image.reference.startsWith("data:image/jpeg;base64,"))
        assertTrue(maxOf(width, height) <= 1_600)
        assertTrue(width.toLong() * height <= 1_500_000L)
        assertTrue(image.bytes < 1_000_000)
    }

    private fun patternedBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(32, 92, 180)
                strokeWidth = 7f
            }
            val nx = (width / 60).coerceAtLeast(1)
            val ny = (height / 60).coerceAtLeast(1)
            for (x in 0 until nx) {
                for (y in 0 until ny) {
                    val cx = x * 60 + 30
                    val cy = y * 60 + 30
                    canvas.drawCircle(cx.toFloat(), cy.toFloat(), 15f, paint)
                }
            }
        }

    private fun String.decodeDataUrl(): ByteArray =
        Base64.decode(substringAfter("base64,"), Base64.DEFAULT)

    private class TypedImageProvider(
        private val sourceFile: File,
    ) : ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun getType(uri: Uri): String = "image/jpeg"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<String>?,
        ): Int = 0
        override fun query(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun openTypedAssetFile(
            uri: Uri,
            mimeTypeFilter: String,
            opts: Bundle?,
        ): AssetFileDescriptor? {
            if (mimeTypeFilter != "image/*") return null
            return AssetFileDescriptor(
                ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY),
                0,
                sourceFile.length(),
            )
        }
    }
}