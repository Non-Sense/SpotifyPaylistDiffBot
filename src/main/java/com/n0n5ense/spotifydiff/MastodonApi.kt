package com.n0n5ense.spotifydiff

import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request

class MastodonApi(
    private val host: String,
    private val accessToken: String
) {

    private fun createHeader(): Headers {
        return mapOf(
            "Authorization" to "Bearer $accessToken"
        ).toHeaders()
    }

    fun postMessage(message: String) {
        val form = FormBody.Builder()
            .add("status", message)
            .add("visibility", "unlisted")
            .build()

        val header = createHeader()
        val request = Request.Builder()
            .url("$host/api/v1/statuses")
            .post(form)
            .headers(header)
            .build()
        val client = OkHttpClient.Builder()
            .build()
        val response = kotlin.runCatching { client.newCall(request).execute() }.onFailure {
            println(it.stackTraceToString())
        }.getOrNull() ?: return
        println(response.code)
    }
}