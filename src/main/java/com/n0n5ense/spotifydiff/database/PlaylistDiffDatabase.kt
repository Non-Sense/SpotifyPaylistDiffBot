package com.n0n5ense.spotifydiff.database

import com.n0n5ense.spotifydiff.PlaylistTrackData
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.statements.DeleteStatement
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction

class PlaylistDiffDatabase {

    companion object {
        fun connect(dbFilePath: String) {
            Database.connect("jdbc:sqlite:$dbFilePath", "org.sqlite.JDBC")
        }

        fun init() {
            transaction {
                SchemaUtils.create(PlaylistUserTable, PlaylistTrackDataTable)
            }
        }

        fun addTrack(trackData: PlaylistTrackData): Boolean {
            try {
                transaction {
                    PlaylistTrackDataTable.insert {
                        it[id] = trackData.trackId
                        it[number] = trackData.number
                        it[addedUserId] = trackData.addedUserId
                        it[addedAt] = trackData.addedAt
                        it[trackUrl] = trackData.trackUrl
                        it[title] = trackData.title
                        it[albumName] = trackData.albumName
                        it[albumUrl] = trackData.albumUrl
                        it[artists] = trackData.artists
                        it[numberUpdated] = true
                        it[jacketUrl] = trackData.jacketImageUrl
                    }
                }
            } catch(e: ExposedSQLException) {
                return false
            }
            return true
        }

        fun getLatestTime(): String? {
            return transaction {
                PlaylistTrackDataTable.slice(PlaylistTrackDataTable.addedAt.max()).selectAll().firstOrNull()
                    ?.getOrNull<String?>(PlaylistTrackDataTable.addedAt.max())
            }
        }

        fun deleteDeletedTrack() {
            transaction {
                PlaylistTrackDataTable.deleteWhere {
                    PlaylistTrackDataTable.numberUpdated eq false
                }
            }
        }

        fun updateNumberIfNeed(track: PlaylistTrackData): Boolean {
            val n = transaction {
                PlaylistTrackDataTable.update(where = {
                    (PlaylistTrackDataTable.id eq track.trackId) and (PlaylistTrackDataTable.numberUpdated eq false)
                }) {
                    it[numberUpdated] = true
                    it[number] = track.number
                }
            }
            return n != 0
        }

        fun clearNumberUpdateFlag() {
            transaction {
                PlaylistTrackDataTable.update {
                    it[numberUpdated] = false
                }
            }
        }

        fun searchTitleConflict(track: PlaylistTrackData): List<PlaylistTrackData> {
            return transaction {
                PlaylistTrackDataTable.select {
                    (PlaylistTrackDataTable.title eq track.title) and (PlaylistTrackDataTable.addedAt neq track.addedAt)
                }.map {
                    it.toPlaylistTrackData()
                }
            }
        }

        private fun <T: Table> T.deleteWhere(
            limit: Int? = null,
            offset: Long? = null,
            op: SqlExpressionBuilder.() -> Op<Boolean>
        ) =
            DeleteStatement.where(
                TransactionManager.current(),
                this@deleteWhere,
                op(SqlExpressionBuilder),
                false,
                limit,
                offset
            )


        private object PlaylistUserTable: IdTable<String>("users") {
            override val id = text("id").uniqueIndex().entityId()
            override val primaryKey = PrimaryKey(id)
            val displayName = text("display_name")
        }

        private object PlaylistTrackDataTable: IdTable<String>("tracks") {
            override val id = text("id").uniqueIndex().entityId()
            override val primaryKey = PrimaryKey(id)
            val number = integer("number")
            val addedUserId = text("added_user_id").nullable()
            val addedAt = text("added_at").nullable()
            val trackUrl = text("track_url").nullable()
            val title = text("title").index()
            val albumName = text("album_name")
            val albumUrl = text("album_url").nullable()
            val artists = text("artists")
            val numberUpdated = bool("number_updated")
            val jacketUrl = text("jacket_url")
        }

        private fun ResultRow.toPlaylistTrackData(): PlaylistTrackData {
            return PlaylistTrackData(
                number = get(PlaylistTrackDataTable.number),
                addedUserId = get(PlaylistTrackDataTable.addedUserId),
                addedAt = get(PlaylistTrackDataTable.addedAt),
                trackUrl = get(PlaylistTrackDataTable.trackUrl),
                title = get(PlaylistTrackDataTable.title),
                albumName = get(PlaylistTrackDataTable.albumName),
                albumUrl = get(PlaylistTrackDataTable.albumUrl),
                trackId = get(PlaylistTrackDataTable.id).value,
                artists = get(PlaylistTrackDataTable.artists),
                jacketImageUrl = get(PlaylistTrackDataTable.jacketUrl)
            )
        }

    }
}

