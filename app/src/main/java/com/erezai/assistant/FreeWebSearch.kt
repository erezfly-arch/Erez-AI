package com.erezai.assistant

import org.jsoup.Jsoup
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String
)

/**
 * Keyless HTML search adapter. No API key, paid endpoint, or model quota is used.
 * The public search site may still apply normal anti-abuse throttling.
 */
object FreeWebSearch {
    fun search(query: String, limit: Int = 6): List<WebSearchResult> {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val page = Jsoup.connect("https://html.duckduckgo.com/html/?q=$encoded")
            .userAgent("Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36")
            .header("Accept-Language", "he-IL,he;q=0.9,en;q=0.7")
            .timeout(20000)
            .get()

        return page.select(".result").take(limit).mapNotNull { item ->
            val anchor = item.selectFirst(".result__a") ?: return@mapNotNull null
            val title = anchor.text().trim()
            val href = anchor.attr("href").trim()
            val snippet = item.selectFirst(".result__snippet")?.text()?.trim().orEmpty()
            if (title.isBlank() || href.isBlank()) null
            else WebSearchResult(title, href, snippet)
        }
    }
}
