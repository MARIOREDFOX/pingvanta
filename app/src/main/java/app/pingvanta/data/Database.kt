package app.pingvanta.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

enum class MonitorType { HTTP, TCP, PING }

enum class MonitorStatus { UNKNOWN, UP, DOWN }

@Entity(tableName = "monitors")
data class MonitorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** URL for HTTP monitors, host name or IP for TCP and ping monitors. */
    val target: String,
    val type: MonitorType = MonitorType.HTTP,
    val port: Int = 443,
    val intervalSeconds: Int = 300,
    val timeoutSeconds: Int = 15,
    /** Comma separated codes and ranges, e.g. "200-299,301,401". HTTP only. */
    val expectedCodes: String = "200-399",
    val notifyOnUp: Boolean = true,
    val notifyOnDown: Boolean = true,
    val alarmOnUp: Boolean = false,
    val alarmOnDown: Boolean = false,
    val repeatAlarm: Boolean = false,
    val paused: Boolean = false,
    // Runtime state, written only by the monitoring engine.
    val status: MonitorStatus = MonitorStatus.UNKNOWN,
    val lastCheckedAt: Long? = null,
    val lastResponseMs: Long? = null,
    val lastHttpCode: Int? = null,
    val lastError: String? = null,
    val lastSuccessAt: Long? = null,
    val lastStatusChangeAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "checks",
    foreignKeys = [
        ForeignKey(
            entity = MonitorEntity::class,
            parentColumns = ["id"],
            childColumns = ["monitorId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("monitorId", "timestamp")],
)
data class CheckEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val monitorId: Long,
    val timestamp: Long,
    val up: Boolean,
    val responseMs: Long?,
    val httpCode: Int?,
    val error: String?,
)

/** A period spent in one status. A DOWN event is an incident; endedAt is null while it is ongoing. */
@Entity(
    tableName = "events",
    foreignKeys = [
        ForeignKey(
            entity = MonitorEntity::class,
            parentColumns = ["id"],
            childColumns = ["monitorId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("monitorId", "startedAt")],
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val monitorId: Long,
    val status: MonitorStatus,
    val startedAt: Long,
    val endedAt: Long? = null,
    val reason: String? = null,
    val responseMs: Long? = null,
    /** True for the first result of a monitor (UNKNOWN -> UP/DOWN), which is a baseline, not a change. */
    @ColumnInfo(defaultValue = "0") val initial: Boolean = false,
)

data class UptimeCount(val up: Int, val total: Int) {
    val percent: Double? get() = if (total == 0) null else up * 100.0 / total
}

data class MonitorUptime(val monitorId: Long, val up: Int, val total: Int)

data class DayBucket(val day: Long, val up: Int, val total: Int, val avgMs: Double?)

data class EventWithMonitor(
    @Embedded val event: EventEntity,
    val monitorName: String,
    val monitorTarget: String,
)

data class CheckExportRow(
    val monitorName: String,
    val monitorTarget: String,
    val timestamp: Long,
    val up: Boolean,
    val responseMs: Long?,
    val httpCode: Int?,
    val error: String?,
)

@Dao
interface MonitorDao {
    @Query("SELECT * FROM monitors ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<MonitorEntity>>

    @Query("SELECT * FROM monitors WHERE id = :id")
    fun observe(id: Long): Flow<MonitorEntity?>

    @Query("SELECT * FROM monitors ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<MonitorEntity>

    @Query("SELECT * FROM monitors WHERE id = :id")
    suspend fun get(id: Long): MonitorEntity?

    @Insert
    suspend fun insert(monitor: MonitorEntity): Long

    @Update
    suspend fun update(monitor: MonitorEntity)

    @Query("UPDATE monitors SET paused = :paused WHERE id = :id")
    suspend fun setPaused(id: Long, paused: Boolean)

    @Query("DELETE FROM monitors WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        "UPDATE monitors SET status = :status, lastCheckedAt = :checkedAt, lastResponseMs = :responseMs, " +
            "lastHttpCode = :httpCode, lastError = :error, lastSuccessAt = :lastSuccessAt, " +
            "lastStatusChangeAt = :lastStatusChangeAt WHERE id = :id",
    )
    suspend fun updateRuntime(
        id: Long,
        status: MonitorStatus,
        checkedAt: Long,
        responseMs: Long?,
        httpCode: Int?,
        error: String?,
        lastSuccessAt: Long?,
        lastStatusChangeAt: Long?,
    )
}

@Dao
interface CheckDao {
    @Insert
    suspend fun insert(check: CheckEntity): Long

    @Query("SELECT * FROM checks WHERE monitorId = :monitorId AND timestamp >= :since ORDER BY timestamp ASC")
    fun observeChecks(monitorId: Long, since: Long): Flow<List<CheckEntity>>

    @Query(
        "SELECT COALESCE(SUM(up), 0) AS up, COUNT(*) AS total FROM checks " +
            "WHERE (:monitorId IS NULL OR monitorId = :monitorId) AND timestamp >= :since",
    )
    fun observeUptime(monitorId: Long?, since: Long): Flow<UptimeCount>

    @Query(
        "SELECT monitorId, COALESCE(SUM(up), 0) AS up, COUNT(*) AS total FROM checks " +
            "WHERE timestamp >= :since GROUP BY monitorId",
    )
    fun observeUptimeByMonitor(since: Long): Flow<List<MonitorUptime>>

    @Query(
        "SELECT ((timestamp + :offsetMs) / 86400000) AS day, COALESCE(SUM(up), 0) AS up, COUNT(*) AS total, " +
            "AVG(responseMs) AS avgMs FROM checks " +
            "WHERE (:monitorId IS NULL OR monitorId = :monitorId) AND timestamp >= :since " +
            "GROUP BY day ORDER BY day",
    )
    fun observeDaily(monitorId: Long?, since: Long, offsetMs: Long): Flow<List<DayBucket>>

    @Query(
        "SELECT m.name AS monitorName, m.target AS monitorTarget, c.timestamp AS timestamp, c.up AS up, " +
            "c.responseMs AS responseMs, c.httpCode AS httpCode, c.error AS error " +
            "FROM checks c INNER JOIN monitors m ON m.id = c.monitorId ORDER BY c.timestamp ASC",
    )
    suspend fun exportRows(): List<CheckExportRow>

    @Query("DELETE FROM checks WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM checks WHERE monitorId = :monitorId")
    suspend fun deleteForMonitor(monitorId: Long)
}

@Dao
interface EventDao {
    @Insert
    suspend fun insert(event: EventEntity): Long

    @Query("UPDATE events SET endedAt = :endedAt WHERE monitorId = :monitorId AND endedAt IS NULL")
    suspend fun closeOpen(monitorId: Long, endedAt: Long)

    @Query(
        "SELECT e.*, m.name AS monitorName, m.target AS monitorTarget FROM events e " +
            "INNER JOIN monitors m ON m.id = e.monitorId " +
            "WHERE (:monitorId IS NULL OR e.monitorId = :monitorId) " +
            "ORDER BY e.startedAt DESC LIMIT :limit",
    )
    fun observeEvents(monitorId: Long?, limit: Int): Flow<List<EventWithMonitor>>

    @Query("SELECT COUNT(DISTINCT monitorId) FROM events WHERE startedAt >= :since AND initial = 0")
    fun observeChangedMonitorCount(since: Long): Flow<Int>

    @Query(
        "SELECT e.*, m.name AS monitorName, m.target AS monitorTarget FROM events e " +
            "INNER JOIN monitors m ON m.id = e.monitorId ORDER BY e.startedAt ASC",
    )
    suspend fun exportRows(): List<EventWithMonitor>

    @Query("DELETE FROM events WHERE monitorId = :monitorId")
    suspend fun deleteForMonitor(monitorId: Long)
}

class Converters {
    @TypeConverter
    fun statusToString(value: MonitorStatus): String = value.name

    @TypeConverter
    fun stringToStatus(value: String): MonitorStatus =
        runCatching { MonitorStatus.valueOf(value) }.getOrDefault(MonitorStatus.UNKNOWN)

    @TypeConverter
    fun typeToString(value: MonitorType): String = value.name

    @TypeConverter
    fun stringToType(value: String): MonitorType =
        runCatching { MonitorType.valueOf(value) }.getOrDefault(MonitorType.HTTP)
}

@Database(
    entities = [MonitorEntity::class, CheckEntity::class, EventEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun monitorDao(): MonitorDao
    abstract fun checkDao(): CheckDao
    abstract fun eventDao(): EventDao
}
