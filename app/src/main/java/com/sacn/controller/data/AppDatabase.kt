package com.sacn.controller.data

import android.content.Context
import androidx.room.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sacn.controller.model.*
import kotlinx.coroutines.flow.Flow

// ──────────────────────────────────────────────────────────────────────────────
// Type Converters
// ──────────────────────────────────────────────────────────────────────────────

class Converters {
    private val gson = Gson()

    @TypeConverter fun modesFromJson(json: String): List<DMXMode> =
        gson.fromJson(json, object : TypeToken<List<DMXMode>>() {}.type)
    @TypeConverter fun modesToJson(modes: List<DMXMode>): String = gson.toJson(modes)

    @TypeConverter fun statesFromJson(json: String): Map<String, Map<Int, Int>> =
        gson.fromJson(json, object : TypeToken<Map<String, Map<Int, Int>>>() {}.type) ?: emptyMap()
    @TypeConverter fun statesToJson(states: Map<String, Map<Int, Int>>): String = gson.toJson(states)

    @TypeConverter fun stringListFromJson(json: String): List<String> =
        gson.fromJson(json, object : TypeToken<List<String>>() {}.type) ?: emptyList()
    @TypeConverter fun stringListToJson(list: List<String>): String = gson.toJson(list)

    @TypeConverter fun cueStepsFromJson(json: String): List<CueStep> =
        gson.fromJson(json, object : TypeToken<List<CueStep>>() {}.type) ?: emptyList()
    @TypeConverter fun cueStepsToJson(steps: List<CueStep>): String = gson.toJson(steps)

    @TypeConverter fun showFileFromJson(json: String): ShowFile =
        gson.fromJson(json, ShowFile::class.java)
    @TypeConverter fun showFileToJson(file: ShowFile): String = gson.toJson(file)
}

// ──────────────────────────────────────────────────────────────────────────────
// Entities
// ──────────────────────────────────────────────────────────────────────────────

@Entity(tableName = "fixture_profiles")
data class FixtureProfileEntity(
    @PrimaryKey val id: String,
    val manufacturer: String,
    val name: String,
    val modesJson: String,        // JSON of List<DMXMode>
    val gdtfFileName: String
)

@Entity(tableName = "fixture_instances")
data class FixtureInstanceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val profileId: String,
    val modeIndex: Int,
    val universe: Int,
    val startAddress: Int,
    val sortOrder: Int = 0
)

@Entity(tableName = "looks")
data class LookEntity(
    @PrimaryKey val id: String,
    val name: String,
    val timestamp: Long,
    val fixtureStatesJson: String,
    val tagsJson: String = "[]",       // JSON of List<String>
    val sortOrder: Int = 0
)

@Entity(tableName = "fixture_groups")
data class GroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val fixtureIdsJson: String,
    val color: Long,
    val sortOrder: Int = 0
)

@Entity(tableName = "cue_lists")
data class CueListEntity(
    @PrimaryKey val id: String,
    val name: String,
    val stepsJson: String,              // JSON of List<CueStep>
    val sortOrder: Int = 0
)

// ──────────────────────────────────────────────────────────────────────────────
// DAOs
// ──────────────────────────────────────────────────────────────────────────────

@Dao
interface FixtureProfileDao {
    @Query("SELECT * FROM fixture_profiles ORDER BY manufacturer, name")
    fun observeAll(): Flow<List<FixtureProfileEntity>>

    @Query("SELECT * FROM fixture_profiles ORDER BY manufacturer, name")
    suspend fun getAll(): List<FixtureProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FixtureProfileEntity)

    @Delete
    suspend fun delete(entity: FixtureProfileEntity)

    @Query("DELETE FROM fixture_profiles WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface FixtureInstanceDao {
    @Query("SELECT * FROM fixture_instances ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<FixtureInstanceEntity>>

    @Query("SELECT * FROM fixture_instances ORDER BY sortOrder, name")
    suspend fun getAll(): List<FixtureInstanceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FixtureInstanceEntity)

    @Delete
    suspend fun delete(entity: FixtureInstanceEntity)

    @Query("DELETE FROM fixture_instances WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface LookDao {
    @Query("SELECT * FROM looks ORDER BY sortOrder, timestamp")
    fun observeAll(): Flow<List<LookEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LookEntity)

    @Delete
    suspend fun delete(entity: LookEntity)

    @Query("DELETE FROM looks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT MAX(sortOrder) FROM looks")
    suspend fun maxSortOrder(): Int?
}

@Dao
interface GroupDao {
    @Query("SELECT * FROM fixture_groups ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<GroupEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: GroupEntity)

    @Delete
    suspend fun delete(entity: GroupEntity)

    @Query("DELETE FROM fixture_groups WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT MAX(sortOrder) FROM fixture_groups")
    suspend fun maxSortOrder(): Int?
}

@Dao
interface CueListDao {
    @Query("SELECT * FROM cue_lists ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<CueListEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CueListEntity)

    @Delete
    suspend fun delete(entity: CueListEntity)

    @Query("DELETE FROM cue_lists WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT MAX(sortOrder) FROM cue_lists")
    suspend fun maxSortOrder(): Int?
}

// ──────────────────────────────────────────────────────────────────────────────
// Database
// ──────────────────────────────────────────────────────────────────────────────

@Database(
    entities = [FixtureProfileEntity::class, FixtureInstanceEntity::class, LookEntity::class, GroupEntity::class, CueListEntity::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): FixtureProfileDao
    abstract fun instanceDao(): FixtureInstanceDao
    abstract fun lookDao(): LookDao
    abstract fun groupDao(): GroupDao
    abstract fun cueListDao(): CueListDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "sacn_controller.db"
            ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Mapping helpers
// ──────────────────────────────────────────────────────────────────────────────

private val gson = Gson()

fun FixtureProfile.toEntity() = FixtureProfileEntity(
    id = id, manufacturer = manufacturer, name = name,
    modesJson = gson.toJson(modes), gdtfFileName = gdtfFileName
)

fun FixtureProfileEntity.toModel(): FixtureProfile {
    val modes: List<DMXMode> = gson.fromJson(modesJson, object : TypeToken<List<DMXMode>>() {}.type)
    return FixtureProfile(id, manufacturer, name, modes, gdtfFileName)
}

fun FixtureInstance.toEntity(sortOrder: Int = 0) = FixtureInstanceEntity(
    id = id, name = name, profileId = profileId, modeIndex = modeIndex,
    universe = universe, startAddress = startAddress, sortOrder = sortOrder
)

fun FixtureInstanceEntity.toModel() = FixtureInstance(
    id = id, name = name, profileId = profileId, modeIndex = modeIndex,
    universe = universe, startAddress = startAddress
)

fun Look.toEntity(sortOrder: Int = 0) = LookEntity(
    id = id, name = name, timestamp = timestamp,
    fixtureStatesJson = gson.toJson(fixtureStates),
    tagsJson = gson.toJson(tags),
    sortOrder = sortOrder
)

fun LookEntity.toModel(): Look {
    val states: Map<String, Map<Int, Int>> =
        gson.fromJson(fixtureStatesJson, object : TypeToken<Map<String, Map<Int, Int>>>() {}.type)
            ?: emptyMap()
    val tags: List<String> =
        gson.fromJson(tagsJson, object : TypeToken<List<String>>() {}.type) ?: emptyList()
    return Look(id, name, timestamp, states, tags, sortOrder)
}

fun FixtureGroup.toEntity(sortOrder: Int = 0) = GroupEntity(
    id = id, name = name, fixtureIdsJson = gson.toJson(fixtureIds),
    color = color, sortOrder = sortOrder
)

fun GroupEntity.toModel(): FixtureGroup {
    val ids: List<String> = gson.fromJson(fixtureIdsJson, object : TypeToken<List<String>>() {}.type) ?: emptyList()
    return FixtureGroup(id, name, ids, color, sortOrder)
}

fun CueList.toEntity(sortOrder: Int = 0) = CueListEntity(
    id = id, name = name, stepsJson = gson.toJson(steps), sortOrder = sortOrder
)

fun CueListEntity.toModel(): CueList {
    val steps: List<CueStep> = gson.fromJson(stepsJson, object : TypeToken<List<CueStep>>() {}.type) ?: emptyList()
    return CueList(id, name, steps, sortOrder = sortOrder)
}

fun ShowFile.toJson(): String = gson.toJson(this)
fun showFileFromJson(json: String): ShowFile = gson.fromJson(json, ShowFile::class.java)
