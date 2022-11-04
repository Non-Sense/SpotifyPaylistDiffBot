package com.n0n5ense.spotifydiff

import com.adamratzman.spotify.SpotifyAppApi
import com.adamratzman.spotify.models.PlaylistTrack
import com.adamratzman.spotify.spotifyAppApi
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
import java.awt.Color
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

private fun PlaylistTrack.toPlaylistTrackData(number: Int): PlaylistTrackData? {
    val track = this.track?.asTrack ?: return null
    return PlaylistTrackData(
        number = number,
        addedUserId = this.addedBy?.id,
        addedAt = this.addedAt,
        trackUrl = track.externalUrls.spotify,
        title = track.name,
        albumName = track.album.name,
        albumUrl = track.album.externalUrls.spotify,
        trackId = track.id,
        artists = track.artists.joinToString { it.name },
        jacketImageUrl = track.album.images.firstOrNull()?.url ?: ""
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
    val playlistId by parser.option(ArgType.String, fullName = "playlistId").default("638w67Ivq7wArdQ402I7ew")
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

    val token = spotifyAppApi(clientId, clientSecret).build().token
    val api = spotifyAppApi(
        clientId = clientId,
        clientSecret = clientSecret,
        token
    ) {
        automaticRefresh = true
    }.build()


    val usersWithCache = UsersWithCache()

    coroutineScope {
        launch {
            while(true) {
                kotlin.runCatching {
                    println("DO!!!")
                    fetchLoop(api, playlistId, usersWithCache, jda)
                    println("Done!!!!")
                    delay(interval.minutes)
                }
            }
        }
    }

}

suspend fun fetchLoop(api: SpotifyAppApi, playlistId: String, usersWithCache: UsersWithCache, jda: JDA) {
    val results = fetchTracks(api, playlistId, usersWithCache)
    PlaylistDiffDatabase.deleteDeletedTrack()

    val messages = results.map {
        when(it) {
            is TrackUpdateResult.Conflict -> makeTrackAddedEmbedText(it, usersWithCache)
            is TrackUpdateResult.Existing -> null
            is TrackUpdateResult.NewTrack -> makeTrackAddedEmbedText(it, usersWithCache)
        }
    }

    PlaylistDiffDatabase.forEachDiscordChannel { channel ->
        messages.forEach {
            if(it != null)
                sendMessageEmbed(jda, channel, it)
        }
    }
}


suspend fun fetchTracks(
    api: SpotifyAppApi,
    playlistId: String,
    usersWithCache: UsersWithCache
): List<TrackUpdateResult> {
    PlaylistDiffDatabase.clearNumberUpdateFlag()
    val result = mutableListOf<TrackUpdateResult>()
    getTracks(api, playlistId) { trackData ->
        trackData.forEach { track ->
            track.addedUserId?.let { userId ->
                usersWithCache.get(userId) ?: run {
                    api.users.getProfile(userId)?.displayName?.let { displayName ->
                        PlaylistUser(userId, displayName)
                    }?.also {
                        usersWithCache.add(it)
                    }
                }
            }

            when(val r = updateTrackData(track)) {
                is TrackUpdateResult.Conflict -> result += r
                is TrackUpdateResult.Existing -> {}
                is TrackUpdateResult.NewTrack -> result += r
            }
        }
    }
    return result
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
}

fun updateTrackData(track: PlaylistTrackData): TrackUpdateResult {
    if(!PlaylistDiffDatabase.addTrack(track)) {
        if(PlaylistDiffDatabase.updateNumberIfNeed(track)) {
            return TrackUpdateResult.Existing(track)
        }
    }
    val conflicts = PlaylistDiffDatabase.searchTitleConflict(track)
    return if(conflicts.isEmpty()) {
        TrackUpdateResult.NewTrack(track)
    } else {
        TrackUpdateResult.Conflict(track, conflicts)
    }
}

suspend fun getTracks(api: SpotifyAppApi, playlistId: String, callback: suspend (List<PlaylistTrackData>) -> Unit) {
    val limit = 100
    var offset = 0
    while(true) {
        val rawList = api.playlists.getPlaylistTracks(playlistId, limit, offset)
        val list = rawList.mapIndexedNotNull { index, playlistTrack ->
            playlistTrack?.toPlaylistTrackData(index + offset + 1)
        }
        callback(list)
        if(rawList.size < limit) {
            return
        }
        offset += limit
    }
}

