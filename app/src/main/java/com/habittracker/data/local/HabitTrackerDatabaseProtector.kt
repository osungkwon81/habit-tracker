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
import org.json.JSONObject
import java.security.MessageDigest

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
            val snapshotVersion = readDatabaseUserVersion(latestDatabaseFile)
            if (snapshotVersion != null && snapshotVersion > HabitTrackerDatabase.DB_VERSION) {
                Log.e(
                    TAG,
                    "Latest automatic backup uses schema $snapshotVersion, which is newer than app schema ${HabitTrackerDatabase.DB_VERSION}; skipping restore.",
                    reason,
                )
                return@withLock false
            }
            if (!isSnapshotHealthy(latestDirectory)) {
                Log.e(TAG, "Latest automatic backup is not healthy; skipping restore.", reason)
                return@withLock false
            }

            // 부가 기록 충돌은 DB를 교체하기 전에 확인한다.
            val recordFiles = snapshotRecords(latestDirectory)
            recordFiles.forEach { (source, target) ->
                require(!target.exists() || source.readBytes().contentEquals(target.readBytes())) { "기존 복권 기록과 백업 충돌: ${target.name}" }
            }
            val alerts = File(latestDirectory, "personal-alerts.json")
            if (alerts.exists()) JSONObject(alerts.readText())
            recordFiles.forEach { (source, target) ->
                if (!target.exists()) {
                    require(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                    source.copyTo(target, overwrite = false)
                }
            }
            com.habittracker.data.PersonalAlerts.get(appContext).restoreIfMissing(alerts)
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
            com.habittracker.data.PersonalAlerts.get(appContext).backupTo(File(temporaryDirectory, "personal-alerts.json"))
            com.habittracker.data.lotto.LotteryAutoImprovement.withRecordLock {
                backupRecordManifest(temporaryDirectory)
            }

            if (!isSnapshotHealthy(temporaryDirectory)) {
                temporaryDirectory.deleteRecursively()
                throw IOException("The copied database snapshot failed integrity_check.")
            }

            val latestDirectory = latestBackupDirectory()
            val previousDirectory = File(backupRootDirectory(), "previous")
            previousDirectory.deleteRecursively()
            require(latestDirectory.renameTo(previousDirectory)) { "기존 백업 이동 실패" }
            if (!temporaryDirectory.renameTo(latestDirectory)) {
                check(previousDirectory.renameTo(latestDirectory)) { "이전 백업 복원 실패" }
                throw IOException("새 백업 교체 실패")
            }

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

    private fun readDatabaseUserVersion(databaseFile: File): Int? =
        runCatching {
            SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("PRAGMA user_version", null).useCursor { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else null
                }
            }
        }.getOrNull()

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
            if (child.isDirectory) copyDirectory(child, File(target, child.name))
            else child.copyTo(File(target, child.name), overwrite = true)
        }
    }

    private fun recordHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    // 불변 파일은 한 번만 보관하고 각 DB 스냅샷은 파일 목록·해시만 가진다.
    private fun backupRecordManifest(snapshot: File) {
        val blobs = File(backupRootDirectory(), "record-blobs")
        require(blobs.isDirectory || blobs.mkdirs())
        val manifest = JSONObject()
        fun visit(source: File, relative: String) {
            require(source.canonicalFile == source.absoluteFile) { "복권 백업 경로 오류: $relative" }
            if (source.isDirectory) requireNotNull(source.listFiles()).forEach { child ->
                if (child.name != ".lock") visit(child, "$relative/${child.name}")
            } else {
                require(source.isFile)
                val bytes = source.readBytes()
                val hash = recordHash(bytes)
                val blob = File(blobs, hash)
                if (blob.exists()) require(recordHash(blob.readBytes()) == hash) { "복권 백업 해시 오류: $relative" }
                else {
                    val atomic = android.util.AtomicFile(blob)
                    val stream = atomic.startWrite()
                    try { stream.write(bytes); atomic.finishWrite(stream) }
                    catch (error: Exception) { atomic.failWrite(stream); throw error }
                }
                manifest.put(relative, hash)
            }
        }
        LOTTERY_RECORD_DIRECTORIES.forEach { name ->
            val source = File(appContext.filesDir, name)
            if (source.exists()) visit(source, name)
        }
        File(snapshot, "lottery-records.json").writeText(manifest.toString())
    }

    private fun snapshotRecords(snapshot: File): List<Pair<File, File>> {
        val manifestFile = File(snapshot, "lottery-records.json")
        if (!manifestFile.exists()) {
            val legacy = mutableListOf<Pair<File, File>>()
            fun visit(source: File, target: File) {
                require(source.canonicalFile == source.absoluteFile && target.canonicalFile == target.absoluteFile)
                if (source.isDirectory) requireNotNull(source.listFiles()).forEach { child ->
                    if (child.name != ".lock") visit(child, File(target, child.name))
                } else { require(source.isFile); legacy += source to target }
            }
            LOTTERY_RECORD_DIRECTORIES.forEach { name -> File(snapshot, name).takeIf { it.exists() }?.let { visit(it, File(appContext.filesDir, name)) } }
            return legacy
        }
        val manifest = JSONObject(manifestFile.readText())
        return manifest.keys().asSequence().map { path ->
            require(path.substringBefore('/') in LOTTERY_RECORD_DIRECTORIES && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." })
            val hash = manifest.getString(path)
            require(hash.matches(Regex("[0-9a-f]{64}")))
            val source = File(File(backupRootDirectory(), "record-blobs"), hash)
            val target = File(appContext.filesDir, path)
            require(target.canonicalFile == target.absoluteFile && source.canonicalFile == source.absoluteFile)
            require(recordHash(source.readBytes()) == hash) { "복권 백업 내용 오류: $path" }
            source to target
        }.toList()
    }

    private val LOTTERY_RECORD_DIRECTORIES = listOf("lottery-candidates", "lottery-adaptive-settings", "lottery-auto-improvement")

    private fun backupRootDirectory(): File =
        File(appContext.noBackupFilesDir, "database-protection").apply { mkdirs() }

    private fun latestBackupDirectory(): File =
        File(backupRootDirectory(), "latest").apply {
            val previous = File(backupRootDirectory(), "previous")
            if (!exists() && previous.exists()) check(previous.renameTo(this)) { "이전 백업 복구 실패" }
            if (!exists()) check(mkdirs()) { "백업 폴더 생성 실패" }
        }

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
