package com.n0n5ense.spotifydiff

import com.n0n5ense.spotifydiff.database.PlaylistDiffDatabase
import com.n0n5ense.spotifydiff.util.UsersWithCache
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent
import se.michaelthelin.spotify.SpotifyApi
import se.michaelthelin.spotify.model_objects.specification.Episode
import se.michaelthelin.spotify.model_objects.specification.PlaylistTrack
import se.michaelthelin.spotify.model_objects.specification.Track
import java.awt.Color
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

data class PlaylistUser(
    val id: String,
    val displayName: String
)

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

data class DiscordChannel(
    val guildId: String,
    val channelId: String
)

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

private fun makeTrackAddedEmbedText(track: TrackUpdateResult.NewTrack, usersWithCache: UsersWithCache): MessageEmbed {
    return EmbedBuilder().apply {
        setTitle("曲が増えた！！")
        setColor(Color(46, 204, 113))
        setThumbnail(track.track.jacketImageUrl)
        addBlankField(false)
        addTrackInfo(track.track, usersWithCache)
    }.build()
}

private fun makeTrackAddedEmbedText(track: TrackUpdateResult.Conflict, usersWithCache: UsersWithCache): MessageEmbed {
    return EmbedBuilder().apply {
        setTitle("曲が増えた！！\nけど同じ曲が既に追加されているかも！！！！")
        setColor(Color(230, 126, 34))
        setThumbnail(track.track.jacketImageUrl)
        addBlankField(false)
        addField("追加された曲", "", false)
        addTrackInfo(track.track, usersWithCache)
        addBlankField(false)
        addField("被ってそうな曲", "", false)
        track.conflictTracks.forEach {
            addTrackInfo(it, usersWithCache, true)
        }
    }.build()
}

private fun EmbedBuilder.addTrackInfo(
    track: PlaylistTrackData,
    usersWithCache: UsersWithCache,
    addNumber: Boolean = false
) {
    addField("曲名", "`${track.title}`", true)
    addField("アルバム", "`${track.albumName}`", true)

    addField("アーティスト", track.artists, false)

    addField("追加した人", "`${usersWithCache.get(track.addedUserId)?.displayName ?: ""}`", true)
    if(addNumber)
        addField("番号", "`${track.number}`", true)

    addField("Link", track.trackUrl ?: "", false)
}

private fun sendMessageEmbed(jda: JDA, channel: DiscordChannel, messageEmbed: MessageEmbed) {
    jda.getGuildById(channel.guildId)?.getTextChannelById(channel.channelId)?.sendMessage(messageEmbed)?.queue()
}


suspend fun main(args: Array<String>) {
    val parser = ArgParser("SpotifyPlaylistDiffBot")
    val dbPath by parser.argument(ArgType.String, fullName = "dbPath")
    val playlistId by parser.argument(ArgType.String, fullName = "playlistId")
    val discordToken by parser.option(ArgType.String, fullName = "discordToken")
        .default(System.getProperty("spotifyDiscordBotToken", ""))
    val clientId by parser.option(ArgType.String, fullName = "spotifyClientId")
        .default(System.getProperty("spotifyClientId", ""))
    val clientSecret by parser.option(ArgType.String, fullName = "spotifyClientSecret")
        .default(System.getProperty("spotifyClientSecret", ""))
    val interval by parser.option(ArgType.Int, fullName = "interval", description = "minutes").default(10)
    parser.parse(args)

    PlaylistDiffDatabase.connect(dbPath)
    PlaylistDiffDatabase.init()

    val jda = JDABuilder
        .createDefault(discordToken)
        .addEventListeners()
        .enableIntents(GatewayIntent.GUILD_MESSAGES)
        .build()

    jda.addEventListener(object: ListenerAdapter() {
        override fun onMessageReceived(event: MessageReceivedEvent) {

            if(event.message.contentRaw.endsWith("!here") && !event.author.isBot) {
                if(PlaylistDiffDatabase.addDiscordChannel(event.guild.id, event.channel.id)) {
                    event.channel.sendMessage("ここに住みます").queue()
                } else {
                    val existChannel = PlaylistDiffDatabase.getDiscordChannel(event.guild.id)
                    if(existChannel == null) {
                        event.channel.sendMessage("よくわからんエラーが起きてるっぽいです").queue()
                    } else {
                        val c = event.guild.getTextChannelById(existChannel.channelId)
                        event.channel.sendMessage("既にチャンネル[${c?.name}]に住んでいるらしいです\n該当チャンネルでメンション付けて`!bye`を打つと出ていきます")
                            .queue()
                    }
                }
            }

            if(event.message.contentRaw.endsWith("!bye") && !event.author.isBot) {
                if(PlaylistDiffDatabase.deleteDiscordChannel(DiscordChannel(event.guild.id, event.channel.id))) {
                    event.channel.sendMessage("さようなら").queue()
                } else {
                    event.channel.sendMessage("そもそもここに住んでいないか、よくわからんエラーが起きてます").queue()
                }
            }
        }
    })

    val spotifyApi = SpotifyApi.builder().setClientId(clientId).setClientSecret(clientSecret).build()!!
    val credentialRequest = spotifyApi.clientCredentials().build()
    runCatching { credentialRequest.execute() }.getOrNull()?.let {
        spotifyApi.accessToken = it.accessToken
    }


    val usersWithCache = UsersWithCache()

    coroutineScope {
        launch {
            while(true) {
                runCatching { credentialRequest.execute() }.getOrNull()?.let {
                    spotifyApi.accessToken = it.accessToken
                }
                kotlin.runCatching {
                    println("start")
                    fetchLoop(spotifyApi, playlistId, usersWithCache, jda)
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

fun fetchLoop(api: SpotifyApi, playlistId: String, usersWithCache: UsersWithCache, jda: JDA) {
    val results = pullTracks(api, playlistId, usersWithCache)
    var c = 0
    var n = 0
    var p = 0
    var e = 0
    val messages = results.mapNotNull {
        when(it) {
            is TrackUpdateResult.Conflict -> makeTrackAddedEmbedText(it, usersWithCache).also { c++ }
            is TrackUpdateResult.Existing -> null.also{ e++ }
            is TrackUpdateResult.Pass -> null.also{ p++ }
            is TrackUpdateResult.NewTrack -> makeTrackAddedEmbedText(it, usersWithCache).also { n++ }
        }
    }

    println("send messages")
    PlaylistDiffDatabase.forEachDiscordChannel { channel ->
        runCatching {
            messages.forEach {
                sendMessageEmbed(jda, channel, it)
            }
        }.onFailure {
            it.printStackTrace()
        }
    }
    println("sent ${messages.size} messages @ ${Instant.now()}")
    println("new: $n\tconflict: $c\tpass: $p\texist: $e")
}

fun pullTracks(
    api: SpotifyApi,
    playlistId: String,
    usersWithCache: UsersWithCache
): List<TrackUpdateResult> {
    val latestTime = PlaylistDiffDatabase.getLatestTime()?.let {
        runCatching {
            Instant.parse(it)
        }.getOrNull()
    }?:Instant.now()
    println("latestTime = $latestTime")

    PlaylistDiffDatabase.clearNumberUpdateFlag()
    val results = getPlaylistTracksFromSpotify(api, playlistId).map { track ->
        track.addedUserId?.let { userId ->
            if(usersWithCache.get(userId) == null) {
                runCatching {
                    api.getUsersProfile(userId).build().execute()!!
                }.getOrNull()?.displayName?.let {
                    usersWithCache.add(PlaylistUser(userId, it))
                }
            }
        }
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

    data class Conflict(
        val track: PlaylistTrackData,
        val conflictTracks: List<PlaylistTrackData>
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
    val conflicts = PlaylistDiffDatabase.searchTitleConflict(track)
    return if(conflicts.isEmpty()) {
        TrackUpdateResult.NewTrack(track)
    } else {
        TrackUpdateResult.Conflict(track, conflicts)
    }
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

