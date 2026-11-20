package ir.aai.hushyar

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import ir.aai.hushyar.data.Db
import ir.aai.hushyar.data.Repos
import ir.aai.hushyar.domain.Answer
import ir.aai.hushyar.domain.Citation
import ir.aai.hushyar.domain.ConversationContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real SQLiteOpenHelper/Repos on Robolectric's SQLite; not a mock or a JSON-only test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatDatabaseTest {
    private lateinit var app: Application
    private val opened = mutableListOf<Db>()
    private fun newDb(): Db = Db(app).also { opened.add(it) }

    @Before fun before() {
        app = RuntimeEnvironment.getApplication()
        app.deleteDatabase("hushyar.db")
    }

    @After fun after() {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
        app.deleteDatabase("hushyar.db")
    }

    @Test
    fun upgradingAnExistingVersionFiveKeepsItsRecordsAndCreatesConversationTables() {
        val path = app.getDatabasePath("hushyar.db")
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.execSQL("CREATE TABLE memory(id TEXT PRIMARY KEY, body TEXT NOT NULL)")
            old.insertOrThrow("memory", null, ContentValues().apply { put("id", "old"); put("body", "دادهٔ قبلی") })
            old.execSQL("CREATE TABLE preference(k TEXT PRIMARY KEY, v TEXT NOT NULL)")
            old.version = 5
        }
        val db = newDb()
        assertEquals(7, db.writableDatabase.version)
        db.readableDatabase.rawQuery("SELECT body FROM memory WHERE id='old'", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("دادهٔ قبلی", c.getString(0))
        }
        val repo = Repos(db)
        val chat = repo.chatCreate()
        val turn = repo.chatAppend(chat.id, "قیمت نبشی ۴ چنده؟")
        repo.chatComplete(turn.id, "قیمت نبشی ۴ چنده؟", Answer("price", "پاسخ قبلی",
            citations = listOf(Citation("P1", "جدول", "https://example.org/p"))))
        db.close()
        val reopened = Repos(newDb())
        assertEquals(chat.id, reopened.chatSelectedId())
        assertEquals("قیمت نبشی ۴ چنده؟", reopened.chatTurns(chat.id).single().question)
        assertEquals("https://example.org/p", reopened.chatTurns(chat.id).single().answer!!.citations.single().url)
    }

    @Test
    fun threadsAreIndependentDeletionCascadesAndSelectionSurvivesReopening() {
        val db = newDb()
        val repo = Repos(db)
        val a = repo.chatCreate()
        val aTurn = repo.chatAppend(a.id, "قیمت نبشی ۴ چنده؟")
        repo.chatComplete(aTurn.id, aTurn.question, Answer("price", "از جدول"))
        val b = repo.chatCreate()
        val bTurn = repo.chatAppend(b.id, "دربارهٔ ورق توضیح بده")
        repo.chatComplete(bTurn.id, bTurn.question, Answer("fact", "از منبع"))
        assertEquals(1, repo.chatTurns(a.id).size)
        assertEquals(1, repo.chatTurns(b.id).size)
        assertEquals(b.id, repo.chatSelectedId())
        assertEquals(a.id, repo.chatDelete(b.id))
        assertEquals(a.id, repo.chatSelectedId())
        assertEquals(listOf(aTurn.id), repo.chatTurnsAll().map { it.id })
        db.close()
        val reopened = Repos(newDb())
        assertEquals(a.id, reopened.chatSelectedId())
        assertEquals(1, reopened.chatThreads().size)
        assertEquals("از جدول", reopened.chatTurns(a.id).single().answer!!.text)
        assertNull(reopened.chatDelete(a.id))
        assertTrue(reopened.chatThreads().isEmpty())
        assertNull(reopened.chatSelectedId())
    }

    @Test
    fun userTitleSurvivesFirstMessageAndHistoryHasNoTwentyTurnLimit() {
        val db = newDb()
        val repo = Repos(db)
        val thread = repo.chatCreate()
        repo.chatRename(thread.id, "بررسی شخصی")
        repeat(25) { n ->
            val turn = repo.chatAppend(thread.id, "سؤال $n")
            repo.chatComplete(turn.id, "سؤال $n", Answer("fact", "پاسخ $n"))
        }
        db.close()
        val reopened = Repos(newDb())
        assertEquals("بررسی شخصی", reopened.chatThreads().single().title)
        assertEquals(25, reopened.chatTurns(thread.id).size)
        assertEquals(25, reopened.snapshot().turns.size)
    }

    @Test
    fun pendingAndFailedUserMessagesPersistButAreNotFinishedAnswers() {
        val db = newDb()
        val repo = Repos(db)
        val chat = repo.chatCreate()
        val pending = repo.chatAppend(chat.id, "سؤال بدون پاسخ")
        assertNull(repo.chatTurns(chat.id).single().answer)
        repo.chatFail(pending.id, "ارتباط ناموفق بود")
        db.close()
        val turn = Repos(newDb()).chatTurns(chat.id).single()
        assertNull(turn.answer)
        assertEquals("ارتباط ناموفق بود", turn.failure)
        assertEquals("سؤال بدون پاسخ", turn.question)
    }

    @Test
    fun resetAndRestorationIncludeAllThreadsTurnsAndActiveSelection() {
        val db = newDb()
        val repo = Repos(db)
        val a = repo.chatCreate()
        val turn = repo.chatAppend(a.id, "سلام")
        repo.chatComplete(turn.id, "سلام", Answer("chat/local", "درود"))
        val b = repo.chatCreate()
        val snapshot = repo.snapshot()
        assertEquals(2, snapshot.threads.size)
        assertEquals(1, snapshot.turns.size)
        repo.resetEverything()
        assertTrue(repo.chatThreads().isEmpty())
        assertTrue(repo.chatTurnsAll().isEmpty())
        assertNull(repo.chatSelectedId())
        repo.restore(snapshot)
        assertEquals(b.id, repo.chatSelectedId())
        assertEquals(2, repo.chatThreads().size)
        assertEquals("درود", repo.chatTurns(a.id).single().answer!!.text)
        db.close()
        val reopened = Repos(newDb())
        assertEquals(b.id, reopened.chatSelectedId())
        assertEquals(1, reopened.chatTurnsAll().size)
    }

    @Test
    fun reopenAndRecallPreviousQuestionStaysWithinTheSelectedThread() {
        val db = newDb()
        val repo = Repos(db)
        val a = repo.chatCreate()
        val previous = repo.chatAppend(a.id, "قیمت نبشی ۴ چنده؟")
        repo.chatComplete(previous.id, previous.question, Answer("material.price", "پاسخ دارای تاریخ"))
        val b = repo.chatCreate()
        val secret = repo.chatAppend(b.id, "قیمت ورق ۲ چنده؟")
        repo.chatComplete(secret.id, secret.question, Answer("material.price", "پاسخ دیگر"))
        repo.chatSelect(a.id)
        db.close()
        val reopened = Repos(newDb())
        val result = ConversationContext.resolve("سؤال قبلی‌ام چی بود؟", a.id,
            reopened.chatTurns(reopened.chatSelectedId()!!))
        assertEquals("conversation.recall", result.directAnswer?.intent)
        assertTrue(result.directAnswer!!.text.contains("نبشی ۴"))
        assertFalse(result.directAnswer!!.text.contains("ورق"))
        assertNull(result.directAnswer!!.citations.firstOrNull())
    }

    @Test
    fun invalidRestoreRollsBackEntireDatabaseIncludingOldConversations() {
        val db = newDb()
        val repo = Repos(db)
        val a = repo.chatCreate()
        val turn = repo.chatAppend(a.id, "سلام")
        repo.chatComplete(turn.id, "سلام", Answer("chat/local", "درود"))
        val backup = repo.snapshot()
        val badTurn = backup.turns.single().copy(threadId = "does-not-exist")
        var failed = false
        try {
            repo.restore(backup.copy(turns = listOf(badTurn)))
        } catch (_: Exception) {
            failed = true
        }
        assertTrue("کل تراکنشِ بازیابیِ خراب باید برگردد", failed)
        assertEquals("درود", repo.chatTurns(a.id).single().answer!!.text)
        assertNotNull(repo.chatSelectedId())
        assertFalse(repo.chatThreads().isEmpty())
        db.close()
    }
}
