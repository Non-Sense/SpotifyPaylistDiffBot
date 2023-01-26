package com.n0n5ense.spotifydiff

import com.n0n5ense.spotifydiff.database.PlaylistDiffDatabase
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.michaelthelin.spotify.SpotifyApi
import se.michaelthelin.spotify.model_objects.specification.Episode
import se.michaelthelin.spotify.model_objects.specification.PlaylistTrack
import se.michaelthelin.spotify.model_objects.specification.Track
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

data class PlaylistTrackData(
    val number: Int,
    val addedUserId: String?,
    val addedAt: String?,
    val trackUrl: String?,
    val title: String,
    val albumName: String,
    val albumUrl: String?,
    val trackId: String,
    val artists: String,
    val jacketImageUrl: String,
) {
    fun toDebugString(): String {
        return "title{$title} album{$albumName} artists{$artists} id{$trackId}"
    }
}


private fun PlaylistTrack.toPlaylistTrackData(number: Int): PlaylistTrackData {
    val track = this.track as? Track
    val episode = this.track as? Episode
    return PlaylistTrackData(
        number = number,
        addedUserId = this.addedBy.id,
        addedAt = this.addedAt.toInstant().toString(),
        trackUrl = track?.externalUrls?.externalUrls?.values?.firstOrNull()
            ?: episode?.externalUrls?.externalUrls?.values?.firstOrNull() ?: "",
        title = track?.name ?: episode?.name ?: "",
        albumName = track?.album?.name ?: "",
        albumUrl = track?.album?.externalUrls?.externalUrls?.values?.firstOrNull() ?: "",
        trackId = track?.id ?: episode?.id ?: "",
        artists = track?.artists?.joinToString { it.name } ?: "",
        jacketImageUrl = track?.album?.images?.maxByOrNull { it.width }?.url ?: ""
    )
}


private fun makeMessage(track: PlaylistTrackData): String {
    return """
        曲が追加されたよ！
        
        曲名： ${track.title}
        アルバム： ${track.albumName}
        アーティスト： ${track.artists}
        
        ${track.trackUrl}
    """.trimIndent()
}

suspend fun main(args: Array<String>) {
    val parser = ArgParser("SpotifyPlaylistDiffBot")
    val dbPath by parser.argument(ArgType.String, fullName = "dbPath")
    val playlistId by parser.argument(ArgType.String, fullName = "playlistId")
    val host by parser.argument(ArgType.String, fullName = "host")
    val accessToken by parser.argument(ArgType.String, fullName = "accessToken")
    val clientId by parser.option(ArgType.String, fullName = "spotifyClientId")
        .default(System.getProperty("spotifyClientId", ""))
    val clientSecret by parser.option(ArgType.String, fullName = "spotifyClientSecret")
        .default(System.getProperty("spotifyClientSecret", ""))
    val interval by parser.option(ArgType.Int, fullName = "interval", description = "minutes").default(5)
    parser.parse(args)

    val mastodonApi = MastodonApi(host, accessToken)

    PlaylistDiffDatabase.connect(dbPath)
    PlaylistDiffDatabase.init()

    val spotifyApi = SpotifyApi.builder().setClientId(clientId).setClientSecret(clientSecret).build()!!
    val credentialRequest = spotifyApi.clientCredentials().build()
    runCatching { credentialRequest.execute() }.getOrNull()?.let {
        spotifyApi.accessToken = it.accessToken
    }


    coroutineScope {
        launch {
            while(true) {
                runCatching { credentialRequest.execute() }.getOrNull()?.let {
                    spotifyApi.accessToken = it.accessToken
                }
                kotlin.runCatching {
                    println("start")
                    fetchLoop(spotifyApi, playlistId, mastodonApi)
                    println("Done")
                }.mapCatching {
                    PlaylistDiffDatabase.deleteDeletedTrack()
                }.onFailure {
                    println(it.stackTraceToString())
                }
                kotlin.runCatching {
                    delay(interval.minutes)
                }
            }
        }
    }

}

fun fetchLoop(api: SpotifyApi, playlistId: String, mastodonApi: MastodonApi) {
    val results = pullTracks(api, playlistId)

    val messages = results.mapNotNull {
        when(it) {
            is TrackUpdateResult.Existing -> null
            is TrackUpdateResult.Pass -> null
            is TrackUpdateResult.NewTrack -> makeMessage(it.track)
        }
    }

    println("send messages")
    messages.forEach {
        runCatching {
            mastodonApi.postMessage(it)
        }.onFailure {
            it.printStackTrace()
        }
    }
    println("sent ${messages.size} messages @ ${Instant.now()}")
}

fun pullTracks(
    api: SpotifyApi,
    playlistId: String
): List<TrackUpdateResult> {
    val latestTime = PlaylistDiffDatabase.getLatestTime()?.let {
        runCatching {
            Instant.parse(it)
        }.getOrNull()
    } ?: Instant.now()
    println("latestTime = $latestTime")

    PlaylistDiffDatabase.clearNumberUpdateFlag()
    val results = getPlaylistTracksFromSpotify(api, playlistId).also { println(it.size) }.map { track ->
        updateTrackData(track, latestTime)
    }
    return results
}

sealed class TrackUpdateResult {
    data class NewTrack(
        val track: PlaylistTrackData
    ): TrackUpdateResult()

    data class Existing(
        val track: PlaylistTrackData
    ): TrackUpdateResult()

    data class Pass(
        val track: PlaylistTrackData
    ): TrackUpdateResult()
}

fun updateTrackData(track: PlaylistTrackData, latestTime: Instant): TrackUpdateResult {
    if(!PlaylistDiffDatabase.addTrack(track)) {
        if(PlaylistDiffDatabase.updateNumberIfNeed(track)) {
            return TrackUpdateResult.Existing(track)
        }
    }
    val addedAt = runCatching { Instant.parse(track.addedAt) }.getOrNull()
    if(addedAt?.isBefore(latestTime) == true) {
        return TrackUpdateResult.Pass(track)
    }
    return TrackUpdateResult.NewTrack(track)
}

fun getPlaylistTracksFromSpotify(api: SpotifyApi, playlistId: String): List<PlaylistTrackData> {
    val limit = 100
    fun makeList(): List<PlaylistTrackData> {
        var offset = 0
        val list = mutableListOf<PlaylistTrackData>()
        while(true) {
            val result = runCatching {
                api.getPlaylistsItems(playlistId).limit(limit).offset(offset).build().execute()
            }.getOrNull()
            result ?: return emptyList()
            list.addAll(result.items.mapIndexed { index, playlistTrack ->
                playlistTrack.toPlaylistTrackData(index + result.offset + 1)
            })
            val next = result.next ?: return list
            offset += limit
            println(next)
        }
    }
    return makeList().also { println("get done") }
}

