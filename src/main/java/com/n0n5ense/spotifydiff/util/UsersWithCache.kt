package com.n0n5ense.spotifydiff.util

import com.n0n5ense.spotifydiff.PlaylistUser
import com.n0n5ense.spotifydiff.database.PlaylistDiffDatabase

class UsersWithCache {

    private val cache = mutableMapOf<String, PlaylistUser>()

    fun get(id: String?): PlaylistUser? {
        id ?: return null
        cache[id]?.let { return it }
        PlaylistDiffDatabase.getUser(id)?.let {
            cache[id] = it
            return it
        }
        return null
    }

    fun add(user: PlaylistUser) {
        PlaylistDiffDatabase.addUser(user)
        cache[user.id] = user
    }
}