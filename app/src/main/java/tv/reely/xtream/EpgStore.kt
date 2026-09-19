package tv.reely.xtream

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement

data class EpgProgramme(
    val channelId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
) {
    val durationSeconds: Long get() = (stop - start).coerceAtLeast(0)

    fun isOnAt(epochSeconds: Long): Boolean = epochSeconds in start until stop
}

/**
 * The guide on disk. A large provider's XMLTV runs to hundreds of thousands of programmes
 * and a stick has about 1.5 GB of RAM, so the guide is never a document held in memory —
 * it is rows written straight through to SQLite and read back a window at a time.
 */
class EpgStore(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE programme (
                channel_id TEXT NOT NULL,
                start INTEGER NOT NULL,
                stop INTEGER NOT NULL,
                title TEXT NOT NULL,
                description TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_programme_lookup ON programme(channel_id, start)")
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // The guide is a cache: throwing it away and re-importing is always correct.
        db.execSQL("DROP TABLE IF EXISTS programme")
        db.execSQL("DROP TABLE IF EXISTS meta")
        onCreate(db)
    }

    /** Opens a bulk import. The caller streams rows in and calls finish exactly once. */
    fun beginImport(): EpgImport = EpgImport(writableDatabase)

    fun lastImportedAt(): Long = readableDatabase
        .rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(KEY_IMPORTED_AT))
        .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).toLongOrNull() ?: 0 else 0 }

    fun programmeCount(): Int = readableDatabase
        .rawQuery("SELECT COUNT(*) FROM programme", null)
        .use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    /**
     * Every programme overlapping the window, for the channels asked about, grouped by
     * channel. Only what the grid can actually draw is ever loaded.
     */
    fun programmes(
        channelIds: Collection<String>,
        from: Long,
        to: Long,
    ): Map<String, List<EpgProgramme>> {
        if (channelIds.isEmpty()) return emptyMap()

        val result = HashMap<String, MutableList<EpgProgramme>>()
        // SQLite caps host parameters, so ask in batches rather than one enormous IN list.
        channelIds.distinct().chunked(400).forEach { batch ->
            val placeholders = batch.joinToString(",") { "?" }
            val args = Array<String>(batch.size + 2) { index ->
                when (index) {
                    batch.size -> to.toString()
                    batch.size + 1 -> from.toString()
                    else -> batch[index]
                }
            }
            readableDatabase.rawQuery(
                """
                SELECT channel_id, start, stop, title, description
                FROM programme
                WHERE channel_id IN ($placeholders) AND start < ? AND stop > ?
                ORDER BY channel_id, start
                """.trimIndent(),
                args,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val channelId = cursor.getString(0)
                    result.getOrPut(channelId) { mutableListOf() }.add(
                        EpgProgramme(
                            channelId = channelId,
                            start = cursor.getLong(1),
                            stop = cursor.getLong(2),
                            title = cursor.getString(3),
                            description = cursor.getString(4),
                        )
                    )
                }
            }
        }
        return result
    }

    companion object {
        private const val NAME = "reely-epg.db"
        private const val VERSION = 1
        const val KEY_IMPORTED_AT = "imported_at"
    }
}

/**
 * A bulk insert. Rows land in batched transactions so the journal stays small and an
 * import of a few hundred thousand programmes never builds a list of them.
 */
class EpgImport internal constructor(private val db: SQLiteDatabase) {

    private val statement: SQLiteStatement
    private var pending = 0

    var written: Int = 0
        private set

    init {
        // This is a rebuildable cache, so durability is worth trading for import speed.
        db.execSQL("PRAGMA synchronous = OFF")
        db.execSQL("DELETE FROM programme")
        statement = db.compileStatement(
            "INSERT INTO programme (channel_id, start, stop, title, description) VALUES (?, ?, ?, ?, ?)"
        )
        db.beginTransaction()
    }

    fun add(channelId: String, start: Long, stop: Long, title: String, description: String?) {
        statement.clearBindings()
        statement.bindString(1, channelId)
        statement.bindLong(2, start)
        statement.bindLong(3, stop)
        statement.bindString(4, title)
        if (description == null) statement.bindNull(5) else statement.bindString(5, description)
        statement.executeInsert()

        written++
        pending++
        if (pending >= BATCH) {
            db.setTransactionSuccessful()
            db.endTransaction()
            db.beginTransaction()
            pending = 0
        }
    }

    fun finish(importedAt: Long) {
        db.setTransactionSuccessful()
        db.endTransaction()
        statement.close()
        db.execSQL(
            "INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)",
            arrayOf(EpgStore.KEY_IMPORTED_AT, importedAt.toString()),
        )
    }

    /** Abandons the import, leaving the table empty rather than half-written. */
    fun abort() {
        runCatching { db.endTransaction() }
        runCatching { statement.close() }
    }

    private companion object {
        const val BATCH = 4_000
    }
}
