package com.joel.litesail.browser

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

internal data class BrowserVisit(val url: String, val title: String, val visitedAt: Long)
internal data class BrowserHistorySnapshot(val visits: List<BrowserVisit>, val favicons: Map<String, ByteArray>)

internal fun browserVisitDate(visitedAt: Long, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(visitedAt).atZone(zone).toLocalDate()

internal object BrowserHistory {
    private const val TABLE = "visits"
    private const val FAVICONS = "visit_favicons"
    private const val RECENT_FIRST = "visited_at DESC, rowid DESC"
    private const val VISITS_ON_DAY = "url = ? AND visited_at >= ? AND visited_at < ?"
    private const val TAG = "BrowserHistory"
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var database: HistoryDatabase? = null

    fun record(context: Context, visit: BrowserVisit) = submit(context) { db ->
        val zone = ZoneId.systemDefault()
        val day = browserVisitDate(visit.visitedAt, zone)
        val dayVisits = arrayOf(
            visit.url,
            day.atStartOfDay(zone).toInstant().toEpochMilli().toString(),
            day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli().toString()
        )
        db.beginTransaction()
        try {
            val latest = db.query(TABLE, arrayOf("visited_at"), VISITS_ON_DAY, dayVisits, null, null, RECENT_FIRST, "1")
                .use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
            if (latest == null || visit.visitedAt > latest) {
                val values = ContentValues().apply {
                    put("url", visit.url)
                    put("title", visit.title)
                    put("visited_at", visit.visitedAt)
                }
                if (latest == null) {
                    db.insertOrThrow(TABLE, null, values)
                } else {
                    db.update(TABLE, values, "url = ? AND visited_at = ?", arrayOf(visit.url, latest.toString()))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun updateTitle(context: Context, visit: BrowserVisit) = submit(context) { db ->
        db.update(
            TABLE, ContentValues().apply { put("title", visit.title) },
            "url = ? AND visited_at = ?", arrayOf(visit.url, visit.visitedAt.toString())
        )
    }

    fun updateFavicon(context: Context, visit: BrowserVisit, icon: Bitmap) = submit(context) { db ->
        val encoded = BrowserFavicon.encode(icon) ?: return@submit
        db.execSQL(
            """INSERT OR REPLACE INTO $FAVICONS (url, icon)
                SELECT url, ? FROM $TABLE WHERE url = ? AND visited_at = ?""",
            arrayOf(encoded, visit.url, visit.visitedAt)
        )
    }

    fun read(context: Context, onResult: (BrowserHistorySnapshot?) -> Unit) = submit(context, { onResult(null) }) { db ->
        val visits = db.query(TABLE, arrayOf("url", "title", "visited_at"), null, null, null, null, RECENT_FIRST)
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(BrowserVisit(cursor.getString(0), cursor.getString(1), cursor.getLong(2)))
                    }
                }
            }
        val favicons = db.query(FAVICONS, arrayOf("url", "icon"), null, null, null, null, null).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(0), cursor.getBlob(1))
            }
        }
        main.post { onResult(BrowserHistorySnapshot(visits, favicons)) }
    }

    fun clear(context: Context, onResult: (Boolean) -> Unit) = submit(context, { onResult(false) }) { db ->
        db.beginTransaction()
        try {
            db.delete(TABLE, null, null)
            db.delete(FAVICONS, null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
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

    // Keep version 3 compatible with databases created by earlier builds.
    private class HistoryDatabase(context: Context) : SQLiteOpenHelper(context, "browser_history.db", null, 3) {
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
            createFaviconTable(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) createFaviconTable(db)
        }

        private fun createFaviconTable(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE $FAVICONS (url TEXT NOT NULL PRIMARY KEY, icon BLOB NOT NULL)")
        }
    }
}
