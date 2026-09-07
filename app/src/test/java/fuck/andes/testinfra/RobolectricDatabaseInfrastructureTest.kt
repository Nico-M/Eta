package fuck.andes.testinfra

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Typeface
import androidx.room.Room
import fuck.andes.data.db.ConversationEntity
import fuck.andes.data.db.ConversationStateEntity
import fuck.andes.data.db.FuckAndesDatabase
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P0 canary: pins the Robolectric native runtime against the official schema 18.
 *
 * Each database/helper is closed and deleted in `finally` so failures never leave
 * state behind. Random names avoid cross-test collisions under a single test JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RobolectricDatabaseInfrastructureTest {

    @Test
    fun roomSchema18BuildsAndConversationDaoRoundTrips() {
        val context = RuntimeEnvironment.getApplication() as Context
        val databaseName = "canary-room-${UUID.randomUUID()}.db"
        val database = Room.databaseBuilder(
            context,
            FuckAndesDatabase::class.java,
            databaseName,
        ).build()
        try {
            val conversationId = "conv-${UUID.randomUUID()}"
            val now = System.currentTimeMillis()
            runBlocking(Dispatchers.IO) {
                database.conversationDao().insertConversations(
                    listOf(
                        ConversationEntity(
                            id = conversationId,
                            title = "canary",
                            thinkingEnabled = false,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    ),
                )
                database.conversationDao().insertState(
                    ConversationStateEntity(
                        id = ConversationStateEntity.SINGLETON_ID,
                        selectedConversationId = conversationId,
                    ),
                )
            }
            val conversation = runBlocking(Dispatchers.IO) {
                database.conversationDao().conversations().single { it.id == conversationId }
            }
            val state = runBlocking(Dispatchers.IO) {
                database.conversationDao().state()
            }
            assertEquals("canary", conversation.title)
            assertEquals(conversationId, state?.selectedConversationId)
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun frameworkSqliteOpenHelperReadsAndWrites() {
        val context = RuntimeEnvironment.getApplication() as Context
        val databaseName = "canary-helper-${UUID.randomUUID()}.db"
        var helper: SQLiteOpenHelper? = null
        try {
            helper = object : SQLiteOpenHelper(context, databaseName, null, 1) {
                override fun onCreate(db: SQLiteDatabase) {
                    db.execSQL("CREATE TABLE kv (k TEXT PRIMARY KEY, v TEXT)")
                }

                override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }
            val writable = helper.writableDatabase
            writable.execSQL("INSERT INTO kv (k, v) VALUES (?, ?)", arrayOf("key", "value"))
            val cursor = writable.rawQuery("SELECT v FROM kv WHERE k = ?", arrayOf("key"))
            try {
                check(cursor.moveToFirst())
                assertEquals("value", cursor.getString(0))
            } finally {
                cursor.close()
            }
        } finally {
            helper?.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun typefaceNativeRuntimeInitializesInWorker() {
        val typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        assertNotNull(typeface)
        assertEquals(Typeface.NORMAL, typeface.style)
    }
}
