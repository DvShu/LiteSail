package com.joel.litesail.browser

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

internal data class BrowserVisit(val url: String, val title: String, val visitedAt: Long)

internal object BrowserHistory {
    private const val TABLE = "visits"
    private const val TAG = "BrowserHistory"
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var database: HistoryDatabase? = null

    fun record(context: Context, visit: BrowserVisit) = submit(context) { db ->
        db.insertWithOnConflict(TABLE, null, ContentValues().apply {
            put("url", visit.url)
            put("title", visit.title)
            put("visited_at", visit.visitedAt)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun updateTitle(context: Context, visit: BrowserVisit) = submit(context) { db ->
        db.update(
            TABLE, ContentValues().apply { put("title", visit.title) },
            "url = ? AND visited_at = ?", arrayOf(visit.url, visit.visitedAt.toString())
        )
    }

    fun read(context: Context, onResult: (List<BrowserVisit>?) -> Unit) = submit(context, { onResult(null) }) { db ->
        val visits = db.query(TABLE, arrayOf("url", "title", "visited_at"), null, null, null, null, "visited_at DESC, rowid DESC")
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(BrowserVisit(cursor.getString(0), cursor.getString(1), cursor.getLong(2)))
                    }
                }
            }
        main.post { onResult(visits) }
    }

    fun clear(context: Context, onResult: (Boolean) -> Unit) = submit(context, { onResult(false) }) { db ->
        db.delete(TABLE, null, null)
        main.post { onResult(true) }
    }

    private fun submit(context: Context, onError: (() -> Unit)? = null, operation: (SQLiteDatabase) -> Unit) {
        val appContext = context.applicationContext
        worker.execute {
            try {
                val helper = database ?: HistoryDatabase(appContext).also { database = it }
                operation(helper.writableDatabase)
            } catch (error: SQLiteException) {
                Log.w(TAG, "Unable to access browsing history", error)
                if (onError != null) main.post { onError() }
            }
        }
    }

    private class HistoryDatabase(context: Context) : SQLiteOpenHelper(context, "browser_history.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE $TABLE (
                    url TEXT NOT NULL,
                    title TEXT NOT NULL,
                    visited_at INTEGER NOT NULL,
                    PRIMARY KEY (url, visited_at)
                )""".trimIndent()
            )
            db.execSQL("CREATE INDEX visits_by_time ON $TABLE (visited_at DESC)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
