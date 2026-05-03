package com.habittracker.data.local

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HabitTrackerDatabaseProtector(
    context: Context,
) : DefaultLifecycleObserver {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val backupMutex = Mutex()

    @Volatile
    private var activeDatabase: HabitTrackerDatabase? = null

    @Volatile
    private var lifecycleObserverRegistered = false

    private var pendingBackupJob: Job? = null

    fun openDatabase(): HabitTrackerDatabase {
        restoreLatestBackupIfDatabaseMissing()

        var lastFailure: Throwable? = null
        repeat(2) { attempt ->
            val database = HabitTrackerDatabase.builder(appContext).build()
            try {
                database.openHelper.writableDatabase
                attach(database)
                requestBackup(database, immediate = true)
                return database
            } catch (error: Throwable) {
                lastFailure = error
                runCatching { database.close() }
                if (attempt == 0 && restoreLatestBackup(error)) {
                    Log.w(TAG, "Restored the latest backup after a database open failure.", error)
                } else {
                    throw error
                }
            }
        }

        throw IllegalStateException("Database open failed without a recoverable backup.", lastFailure)
    }

    fun attach(database: HabitTrackerDatabase) {
        activeDatabase = database
        if (!lifecycleObserverRegistered) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
            lifecycleObserverRegistered = true
        }
    }

    fun requestBackup(database: HabitTrackerDatabase, immediate: Boolean = false) {
        activeDatabase = database
        synchronized(this) {
            pendingBackupJob?.cancel()
            pendingBackupJob = scope.launch {
                if (!immediate) {
                    delay(BACKUP_DEBOUNCE_MS)
                }
                runCatching { backupNow(database) }
                    .onFailure { error -> Log.e(TAG, "Automatic database backup failed.", error) }
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        activeDatabase?.let { requestBackup(it, immediate = true) }
    }

    fun shutdown() {
        synchronized(this) {
            pendingBackupJob?.cancel()
            pendingBackupJob = null
        }
        scope.cancel()
    }

    private fun restoreLatestBackupIfDatabaseMissing() {
        val databaseFile = appContext.getDatabasePath(HabitTrackerDatabase.DB_NAME)
        if (!databaseFile.exists()) {
            restoreLatestBackup(null)
        }
    }

    private fun restoreLatestBackup(reason: Throwable?): Boolean = runBlocking(Dispatchers.IO) {
        backupMutex.withLock {
            val latestDirectory = latestBackupDirectory()
            val latestDatabaseFile = File(latestDirectory, HabitTrackerDatabase.DB_NAME)
            if (!latestDatabaseFile.exists()) {
                return@withLock false
            }
            if (!isSnapshotHealthy(latestDirectory)) {
                Log.e(TAG, "Latest automatic backup is not healthy; skipping restore.", reason)
                return@withLock false
            }

            quarantineLiveDatabaseFiles()
            copySnapshotFiles(latestDirectory, liveDatabaseDirectory())
            copyTaskColorPreferences(latestDirectory, liveSharedPreferencesDirectory())
            true
        }
    }

    private suspend fun backupNow(database: HabitTrackerDatabase) {
        backupMutex.withLock {
            checkpoint(database)

            val liveDatabaseFile = appContext.getDatabasePath(HabitTrackerDatabase.DB_NAME)
            if (!liveDatabaseFile.exists()) return

            val temporaryDirectory = File(backupRootDirectory(), "tmp")
            temporaryDirectory.deleteRecursively()
            temporaryDirectory.mkdirs()

            copySnapshotFiles(liveDatabaseDirectory(), temporaryDirectory)
            copyTaskColorPreferences(liveSharedPreferencesDirectory(), temporaryDirectory)

            if (!isSnapshotHealthy(temporaryDirectory)) {
                temporaryDirectory.deleteRecursively()
                throw IOException("The copied database snapshot failed integrity_check.")
            }

            val latestDirectory = latestBackupDirectory()
            latestDirectory.deleteRecursively()
            temporaryDirectory.renameTo(latestDirectory)

            val archiveDirectory = File(historyDirectory(), timestampFormatter.format(Date()))
            archiveDirectory.parentFile?.mkdirs()
            copyDirectory(latestDirectory, archiveDirectory)
            pruneOldBackups()
        }
    }

    private fun checkpoint(database: HabitTrackerDatabase) {
        val writableDatabase = database.openHelper.writableDatabase
        writableDatabase.query("PRAGMA wal_checkpoint(FULL)").useCursor { }
    }

    private fun quarantineLiveDatabaseFiles() {
        val timestamp = timestampFormatter.format(Date())
        val quarantineDirectory = File(backupRootDirectory(), "quarantine/$timestamp")
        quarantineDirectory.mkdirs()

        DATABASE_FILE_NAMES.forEach { name ->
            val source = File(liveDatabaseDirectory(), name)
            if (source.exists()) {
                source.copyTo(File(quarantineDirectory, name), overwrite = true)
                source.delete()
            }
        }
    }

    private fun copySnapshotFiles(sourceDirectory: File, targetDirectory: File) {
        targetDirectory.mkdirs()
        DATABASE_FILE_NAMES.forEach { name ->
            val source = File(sourceDirectory, name)
            val target = File(targetDirectory, name)
            if (source.exists()) {
                source.copyTo(target, overwrite = true)
            } else {
                target.delete()
            }
        }
    }

    private fun copyTaskColorPreferences(sourceDirectory: File, targetDirectory: File) {
        targetDirectory.mkdirs()
        val source = File(sourceDirectory, TASK_COLOR_PREFS_FILE_NAME)
        val target = File(targetDirectory, TASK_COLOR_PREFS_FILE_NAME)
        if (source.exists()) {
            source.copyTo(target, overwrite = true)
        } else {
            target.delete()
        }
    }

    private fun isSnapshotHealthy(snapshotDirectory: File): Boolean {
        val snapshotDatabase = File(snapshotDirectory, HabitTrackerDatabase.DB_NAME)
        if (!snapshotDatabase.exists()) return false

        return runCatching {
            SQLiteDatabase.openDatabase(snapshotDatabase.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("PRAGMA integrity_check(1)", null).useCursor { cursor ->
                    cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)
                }
            }
        }.getOrDefault(false)
    }

    private fun pruneOldBackups() {
        val archives = historyDirectory().listFiles()
            ?.filter(File::isDirectory)
            ?.sortedByDescending { it.name }
            .orEmpty()

        archives.drop(BACKUP_HISTORY_LIMIT).forEach(File::deleteRecursively)
    }

    private fun copyDirectory(source: File, target: File) {
        target.deleteRecursively()
        target.mkdirs()
        source.listFiles().orEmpty().forEach { child ->
            child.copyTo(File(target, child.name), overwrite = true)
        }
    }

    private fun backupRootDirectory(): File =
        File(appContext.noBackupFilesDir, "database-protection").apply { mkdirs() }

    private fun latestBackupDirectory(): File =
        File(backupRootDirectory(), "latest").apply { mkdirs() }

    private fun historyDirectory(): File =
        File(backupRootDirectory(), "history").apply { mkdirs() }

    private fun liveDatabaseDirectory(): File =
        appContext.getDatabasePath(HabitTrackerDatabase.DB_NAME).parentFile
            ?: throw IllegalStateException("Database directory is unavailable.")

    private fun liveSharedPreferencesDirectory(): File =
        File(appContext.applicationInfo.dataDir, "shared_prefs").apply { mkdirs() }

    private companion object {
        private const val TAG = "HabitTrackerDbProtect"
        private const val BACKUP_HISTORY_LIMIT = 5
        private const val BACKUP_DEBOUNCE_MS = 1_500L
        private const val TASK_COLOR_PREFS_FILE_NAME = "task-color-prefs.xml"
        private val DATABASE_FILE_NAMES = listOf(
            HabitTrackerDatabase.DB_NAME,
            "${HabitTrackerDatabase.DB_NAME}-wal",
            "${HabitTrackerDatabase.DB_NAME}-shm",
        )
        private val timestampFormatter =
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }
}

private inline fun <T> Cursor.useCursor(block: (Cursor) -> T): T =
    use(block)
