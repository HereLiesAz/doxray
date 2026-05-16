package com.hereliesaz.doxray.api

import org.jsoup.Jsoup

object GitHubProbeParser {

    data class Profile(
        val username: String,
        val bio: String,
        val followers: Int,
        val publicRepos: Int,
    )

    fun parse(html: String, username: String): Profile? {
        if (html.isBlank()) return null
        val document = Jsoup.parse(html)
        val nameElement = document.selectFirst(".p-name") ?: return null

        val bio = document.selectFirst(".user-profile-bio")?.text().orEmpty()
        val followers = document
            .selectFirst("a[href\$=tab=followers] span.text-bold, a[href\$=tab=followers] .Counter")
            ?.text()?.replace(",", "")?.toIntOrNull() ?: 0
        val publicRepos = document
            .selectFirst("a[href\$=tab=repositories] span.Counter, a[href\$=tab=repositories] .text-bold")
            ?.text()?.replace(",", "")?.toIntOrNull() ?: 0

        return Profile(
            username = username,
            bio = bio.trim(),
            followers = followers,
            publicRepos = publicRepos,
        )
    }
}
