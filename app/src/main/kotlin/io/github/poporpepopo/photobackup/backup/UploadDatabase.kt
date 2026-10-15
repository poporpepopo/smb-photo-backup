package io.github.poporpepopo.photobackup.backup

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert

/**
 * One row per MediaStore item that is safely on the share. [size] and
 * [dateModifiedSeconds] are MediaStore's values at upload time: if either changes later
 * (the photo was edited), the item is sent again.
 */
@Entity(tableName = "uploaded")
data class UploadedRecord(
    @PrimaryKey val mediaId: Long,
    val size: Long,
    val dateModifiedSeconds: Long,
    /** Share-relative "/"-separated path of the copy on the share. */
    val remotePath: String,
    val uploadedAtMillis: Long,
) {
    val version get() = MediaVersion(mediaId, size, dateModifiedSeconds)
}

@Dao
interface UploadedDao {
    @Query("SELECT * FROM uploaded")
    fun all(): List<UploadedRecord>

    @Upsert
    fun upsert(record: UploadedRecord)
}

@Database(entities = [UploadedRecord::class], version = 1)
abstract class UploadDatabase : RoomDatabase() {
    abstract fun uploaded(): UploadedDao

    companion object {
        fun open(context: Context): UploadDatabase =
            Room.databaseBuilder(context.applicationContext, UploadDatabase::class.java, "uploads.db")
                .build()
    }
}
