package com.streamflixreborn.streamflix.providers

import MyCookieJar
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query
import retrofit2.http.Url
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.CertPathValidatorException
import java.util.concurrent.TimeUnit
import java.util.Locale
import java.util.regex.Pattern
import org.json.JSONArray

object ToonItaliaProvider : Provider {

    private const val SITE = "https://toonitalia.xyz"
    override val baseUrl = "https://toonitalia.xyz/"
    private const val USER_AGENT = "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64)"

    override val name = "ToonItalia"
    override val logo = "https://toonitalia.xyz/favicon.ico"
    override val language = "it"

    /** Le 4 sezioni del sito -> id categoria WordPress (verificati). */
    private val CATEGORY_IDS = linkedMapOf(
        "anime_ita" to 13,        // Anime > ITA
        "anime_sub_ita" to 14,    // Anime > Sub-Ita
        "film_animazione" to 7,   // Film Animazione
        "serie_tv" to 6,          // Serie Tv
    )

    private val CATEGORY_LABELS = linkedMapOf(
        "anime_ita" to "Anime ITA",
        "anime_sub_ita" to "Anime Sub-ITA",
        "film_animazione" to "Film",
        "serie_tv" to "Serie TV",
    )

    // Domini di streaming rilevati sul sito
    private val STREAMING_DOMAINS = setOf(
        "uqload.is", "uqload.co", "uqload.to", "chuckle-tube.com", "ryderjet.com",
        "streamtape.com", "streamtape.to", "doodstream.com", "dood.to", "dood.la",
        "mixdrop.co", "mixdrop.to", "supervideo.tv", "vidoza.net", "upstream.to",
        "streamz.ws", "vidmoly.to", "vidmoly.me", "filemoon.sx", "filemoon.to",
        "voe.sx", "vidhidepro.com", "vidhide.com", "maxstream.video",
        "dropload.io", "savefiles.co",
        "uprot.net", "dhtpre.com", "peytonepre.com", "smoothpre.com",
        "rpmplay.xyz", "rpmplay.com"
    )

    private val PLAYER_NAME = Pattern.compile(
        "^(PLAYER\\s?\\d*|VOE|VIDHIDE|MIXDROP|STREAMTAPE|DOOD(STREAM)?|SUPERVIDEO|VIDOZA|" +
                "UPSTREAM|STREAMZ|VIDMOLY|FILEMOON|UQLOAD|MAXSTREAM|HYDRAX|DROPLOAD|SAVEFILES|" +
                "ALPHA|DELTA|WOLFSTREAM|NINJASTREAM)$", Pattern.CASE_INSENSITIVE)

    private val EP_LINE_SXE = Pattern.compile(
        "^\\s*(\\d{1,2})\\s*[×xX]\\s*(\\d{1,3})\\s*[–—-]\\s*(.+)$")
    private val EP_LINE = Pattern.compile(
        "^\\s*(\\d{1,4})\\s*[–—]\\s*(.+)$")

    private val TITLE_CUT = Pattern.compile(
        "\\s*[–—-]\\s*(PLAYER\\s?\\d*|VOE|VIDHIDE|MIXDROP|STREAMTAPE|DOOD|SUPERVIDEO|VIDOZA|" +
                "UPSTREAM|STREAMZ|VIDMOLY|FILEMOON|UQLOAD|MAXSTREAM|DROPLOAD|SAVEFILES|HYDRAX|" +
                "ALPHA|DELTA)\\b.*$", Pattern.CASE_INSENSITIVE)

    private val ANCHOR = Pattern.compile(
        "<a\\s+name=\"(S[\\w]+)\"[^>]*>\\s*</a>", Pattern.CASE_INSENSITIVE)

    // ────────────────────────────── cache dettagli ──────────────────────────────
    private val detailCache = LinkedHashMap<String, ParsedDetail>()

    private fun cachedDetail(slug: String): ParsedDetail? = synchronized(detailCache) { detailCache[slug] }
    private fun putDetail(slug: String, d: ParsedDetail) = synchronized(detailCache) {
        detailCache[slug] = d
        if (detailCache.size > 60) {
            val it = detailCache.keys.iterator()
            if (it.hasNext()) { it.next(); it.remove() }
        }
    }

    private var service = ToonItaliaService.build()

    private fun rebuildServiceUnsafe() {
        service = ToonItaliaService.buildUnsafe()
    }

    private suspend fun <T> withSslFallback(block: suspend (ToonItaliaService) -> T): T {
        return try {
            block(service)
        } catch (e: Exception) {
            val isSsl = e is SSLHandshakeException || e is CertPathValidatorException
            if (!isSsl) throw e
            rebuildServiceUnsafe()
            block(service)
        }
    }

    private suspend fun getParsed(slug: String): ParsedDetail {
        cachedDetail(slug)?.let { return it }
        val doc = withSslFallback { it.getPage("$SITE/$slug/") }
        val d = parseDetail(doc, "$SITE/$slug/", slug)
        putDetail(slug, d)
        return d
    }

    // ────────────────────────────── home / ricerca ──────────────────────────────

    override suspend fun getHome(): List<Category> {
        val categories = mutableListOf<Category>()
        for (key in CATEGORY_IDS.keys) {
            try {
                val body = withSslFallback {
                    it.getCategoryPosts(
                        CATEGORY_IDS[key]!!, 100, 1,
                        "title", "asc", "link,title,yoast_head_json.og_image"
                    )
                }
                val list = parsePosts(body, key)
                if (list.isNotEmpty()) {
                    categories.add(Category(name = CATEGORY_LABELS[key]!!, list = list))
                }
            } catch (_: Exception) {
                // sezione saltata in caso di errore
            }
        }
        return categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isEmpty()) {
            return CATEGORY_LABELS.map { (key, label) -> Genre(id = key, name = label) }
        }
        return try {
            val body = withSslFallback {
                it.searchPosts(query.replace(" ", "+"), 20, "link,title")
            }
            parsePosts(body, "serie_tv")
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        return try {
            val body = withSslFallback {
                it.getCategoryPosts(
                    CATEGORY_IDS["film_animazione"]!!, 100, page,
                    "title", "asc", "link,title,yoast_head_json.og_image"
                )
            }
            parsePosts(body, "film_animazione").filterIsInstance<Movie>()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return try {
            val body = withSslFallback {
                it.getCategoryPosts(
                    CATEGORY_IDS["serie_tv"]!!, 100, page,
                    "title", "asc", "link,title,yoast_head_json.og_image"
                )
            }
            parsePosts(body, "serie_tv").filterIsInstance<TvShow>()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ────────────────────────────── dettaglio ──────────────────────────────

    override suspend fun getMovie(id: String): Movie {
        val d = getParsed(id)
        return Movie(
            id = id,
            title = d.title,
            overview = d.overview,
            released = d.released,
            poster = d.poster,
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val d = getParsed(id)
        return TvShow(
            id = id,
            title = d.title,
            overview = d.overview,
            released = d.released,
            poster = d.poster,
            seasons = d.seasons.mapIndexed { si, season ->
                Season(
                    id = "$id::$si",
                    number = si + 1,
                    title = season.title,
                    episodes = season.episodes.mapIndexed { ei, ep ->
                        Episode(
                            id = "$id::$si::$ei",
                            number = ep.number,
                            title = ep.title,
                        )
                    }
                )
            }
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val parts = seasonId.split("::")
        val slug = parts.getOrNull(0) ?: return emptyList()
        val si = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val d = getParsed(slug)
        val season = d.seasons.getOrNull(si) ?: return emptyList()
        return season.episodes.mapIndexed { ei, ep ->
            Episode(id = "$slug::$si::$ei", number = ep.number, title = ep.title)
        }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val key = if (CATEGORY_IDS.containsKey(id)) id else "serie_tv"
        return try {
            val body = withSslFallback {
                it.getCategoryPosts(
                    CATEGORY_IDS[key]!!, 100, page,
                    "title", "asc", "link,title,yoast_head_json.og_image"
                )
            }
            val shows = parsePosts(body, key).filterIsInstance<Show>()
            Genre(id = id, name = CATEGORY_LABELS[key] ?: id, shows = shows)
        } catch (_: Exception) {
            Genre(id = id, name = CATEGORY_LABELS[key] ?: id)
        }
    }

    override suspend fun getPeople(id: String, page: Int): People {
        return People(id = id, name = id)
    }

    // ────────────────────────────── server / video ──────────────────────────────

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val d = when (videoType) {
            is Video.Type.Movie -> getParsed(id)
            is Video.Type.Episode -> {
                val parts = id.split("::")
                val slug = parts.getOrNull(0) ?: return emptyList()
                val si = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val ei = parts.getOrNull(2)?.toIntOrNull() ?: 0
                val pd = getParsed(slug)
                val season = pd.seasons.getOrNull(si) ?: return emptyList()
                val ep = season.episodes.getOrNull(ei) ?: return emptyList()
                ParsedDetail(
                    title = pd.title, overview = pd.overview, poster = pd.poster,
                    released = pd.released, originalTitle = pd.originalTitle,
                    status = pd.status, country = pd.country,
                    totalEpisodes = pd.totalEpisodes,
                    seasons = listOf(ParsedSeason(season.title, listOf(ep)))
                )
            }
        }
        val servers = mutableListOf<Video.Server>()
        for (season in d.seasons) {
            for (ep in season.episodes) {
                for (p in ep.players) {
                    servers.add(Video.Server(id = p.url, name = p.label))
                }
            }
        }
        return servers
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return Extractor.extract(server.id)
    }

    // ────────────────────────────── parsing ──────────────────────────────

    private data class PlayerLink(val label: String, val url: String)
    private data class ParsedEpisode(
        val number: Int,
        val title: String,
        val label: String?,
        val players: List<PlayerLink>,
    )
    private data class ParsedSeason(
        val title: String,
        val episodes: List<ParsedEpisode>,
    )
    private data class ParsedDetail(
        val title: String,
        val overview: String?,
        val poster: String?,
        val released: String?,
        val originalTitle: String?,
        val status: String?,
        val country: String?,
        val totalEpisodes: String?,
        val seasons: List<ParsedSeason>,
    )

    private fun parseDetail(doc: Document, url: String, slug: String): ParsedDetail {
        val content = doc.selectFirst("div.entry-content") ?: doc.selectFirst("article")
        ?: return ParsedDetail(slug, null, null, null, null, null, null, null, emptyList())

        val text = content.text()
        val html = content.html()

        val originalTitle = meta(text, "Titolo originale")
        val country = meta(text, "Paese di origine")
        val status = meta(text, "Stato Opera")
        val totalEpisodes = meta(text, "N. Episodi")

        var year: String? = null
        val yearRaw = meta(text, "Data di pubblicazione")
        if (yearRaw != null) {
            val ym = Regex("(?:19|20)\\d{2}").find(yearRaw)
            year = ym?.value
        }

        var overview: String? = null
        val tm = Regex(
            "Trama:\\s*([\\s\\S]+?)(?:\\s*Scegli Stagione:|\\s*Fonte:|\\s*Sigla|\\s*Sigle|\\s*\\d{3}\\s*[–—]|$)",
            RegexOption.IGNORE_CASE
        ).find(text)
        if (tm != null) {
            overview = tm.groupValues[1].replace(Regex("\\s+"), " ").trim()
            if (overview.length > 2000) overview = overview.substring(0, 2000)
        }
        if (overview.isNullOrEmpty()) {
            doc.selectFirst("meta[property=og:description]")?.attr("content")?.let { overview = it }
        }

        var poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        if (poster.isNullOrEmpty()) poster = content.selectFirst("img")?.absUrl("src")
        poster = poster?.replace("http://", "https://")

        var seasons = splitByAnchors(content, html)
        if (seasons.isEmpty()) seasons = splitByHeaders(content)
        if (seasons.isEmpty()) {
            val eps = parseEpisodeLines(html)
            if (eps.isNotEmpty()) seasons = listOf(ParsedSeason("Stagione 1", eps))
        }
        if (seasons.isEmpty()) {
            val players = mutableListOf<PlayerLink>()
            val seen = mutableSetOf<String>()
            for (a in content.select("a[href]")) {
                val href = a.attr("href").trim()
                val name = a.text().trim()
                if (isPlayerLink(name, href) && seen.add(href)) {
                    players.add(PlayerLink(if (name.isEmpty()) "PLAYER${players.size + 1}" else name, href))
                }
            }
            if (players.isNotEmpty()) {
                seasons = listOf(
                    ParsedSeason(
                        "Contenuto",
                        listOf(ParsedEpisode(1, "Film Completo", null, players))
                    )
                )
            }
        }

        val title = Jsoup.parse(
            doc.selectFirst("h1.entry-title")?.text()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: slug
        ).text()

        return ParsedDetail(
            title = title,
            overview = overview,
            poster = poster,
            released = year,
            originalTitle = originalTitle,
            status = status,
            country = country,
            totalEpisodes = totalEpisodes,
            seasons = seasons,
        )
    }

    private fun parsePosts(body: ResponseBody, key: String): List<AppAdapter.Item> {
        val arr = JSONArray(body.string())
        val out = mutableListOf<AppAdapter.Item>()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val link = p.optString("link")
            val slug = extractSlug(link)
            if (slug.isEmpty()) continue
            val rawTitle = p.optJSONObject("title")?.optString("rendered") ?: slug
            val title = Jsoup.parse(rawTitle).text()
            var img: String? = null
            val yoast = p.optJSONObject("yoast_head_json")
            if (yoast != null) {
                val og = yoast.optJSONArray("og_image")
                if (og != null && og.length() > 0) img = og.getJSONObject(0).optString("url", null)
            }
            img = img?.replace("http://", "https://")
            val item: AppAdapter.Item = if (key == "film_animazione") {
                Movie(id = slug, title = title, poster = img)
            } else {
                TvShow(id = slug, title = title, poster = img)
            }
            out.add(item)
        }
        return out
    }

    private fun meta(text: String, label: String): String? {
        val stop = listOf(
            "Titolo originale", "Titolo Alternativo", "Titoli Alternativi",
            "Paese di origine", "Data di pubblicazione", "Stato Opera", "N. Episodi",
            "Episodi disponibili", "Aggiornamento episodi", "Trama", "Link Streaming",
            "Scegli Stagione"
        )
        val p = Pattern.compile(
            Pattern.quote(label) + "\\s*:\\s*(.+?)(?:\\s*(" +
                    stop.joinToString("|") { Pattern.quote(it) } + ")\\s*:|$)",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )
        val m = p.matcher(text)
        if (!m.find()) return null
        val v = m.group(1)
            .replace(Regex("\\s+"), " ")
            .replace(Regex("^[ \\u00A0\\-–—]+|[ \\u00A0\\-–—]+$"), "")
            .trim()
        return if (v.isEmpty()) null else v
    }

    private fun isStreamingUrl(url: String?): Boolean {
        if (url == null) return false
        return try {
            val host = java.net.URL(url).host ?: return false
            STREAMING_DOMAINS.any { host.contains(it) }
        } catch (_: Exception) {
            false
        }
    }

    private fun isPlayerLink(name: String?, url: String?): Boolean {
        if (url == null || !url.startsWith("http")) return false
        if (!name.isNullOrEmpty() && PLAYER_NAME.matcher(name.trim()).matches()) return true
        return isStreamingUrl(url)
    }

    private fun extractSlug(link: String): String {
        var s = link.replaceFirst(Regex("^https?://toonitalia\\.xyz/"), "")
        s = s.replace(Regex("/+\$"), "")
        return if (s.contains("/")) "" else s
    }

    private fun splitByAnchors(content: Element, html: String): List<ParsedSeason> {
        val m = ANCHOR.matcher(html)
        val spans = mutableListOf<IntArray>()
        val anchors = mutableListOf<String>()
        while (m.find()) {
            anchors.add(m.group(1))
            spans.add(intArrayOf(m.start(), m.end()))
        }
        if (anchors.isEmpty()) return emptyList()

        val selectorNames = mutableMapOf<String, String>()
        for (a in content.select("a[href^='#S']")) {
            val name = a.text().replace(Regex("\\s+"), " ").trim()
            if (name.isNotEmpty()) selectorNames[a.attr("href").substring(1)] = name
        }

        val seasons = mutableListOf<ParsedSeason>()
        for (i in anchors.indices) {
            val start = spans[i][1]
            val end = if (i + 1 < spans.size) spans[i + 1][0] else html.length
            val block = html.substring(start, end)
            val eps = parseEpisodeLines(block)
            if (eps.isEmpty()) continue
            var name = selectorNames[anchors[i]]
            if (name == null) {
                val h = Jsoup.parse(block).selectFirst("h2, h3")
                name = h?.text()?.trim() ?: ("Stagione " + anchors[i].substring(1))
            }
            seasons.add(ParsedSeason(name, eps))
        }
        return seasons
    }

    private fun splitByHeaders(content: Element): List<ParsedSeason> {
        val headers = content.select("h2, h3")
        val seasons = mutableListOf<ParsedSeason>()
        for (h in headers) {
            val t = h.text().trim()
            val isSeason = t.isNotEmpty() && (
                    t.lowercase().contains("stagione")
                            || t.lowercase().contains("season")
                            || Regex("^\\d+\\s*[°ª].*").matches(t)
                    )
            if (!isSeason) continue

            val block = StringBuilder()
            var sib = h.nextElementSibling()
            while (sib != null) {
                if (sib.tagName() == "h2" || sib.tagName() == "h3") {
                    val st = sib.text().trim().lowercase()
                    if (st.contains("stagione") || st.contains("season")
                        || Regex("^\\d+\\s*[°ª].*").matches(st)
                    ) break
                }
                block.append(sib.outerHtml()).append("\n")
                sib = sib.nextElementSibling()
            }
            val eps = parseEpisodeLines(block.toString())
            if (eps.isNotEmpty()) seasons.add(ParsedSeason(t, eps))
        }
        return seasons
    }

    private fun parseEpisodeLines(blockHtml: String): List<ParsedEpisode> {
        val lines = blockHtml.split(
            Regex("(?i)<br\\s*/?>|</p>\\s*|<p[^>]*>|</li>\\s*<li[^>]*>|<li[^>]*>")
        )
        val byNum = LinkedHashMap<Int, ParsedEpisode>()
        for (line in lines) {
            if (!line.contains("<a") && !line.contains("–") && !line.contains("—")) continue
            val lineDoc = Jsoup.parse(line)
            val lineText = lineDoc.text().trim()

            var num: Int
            var rest: String
            var label: String? = null
            val mSxe = EP_LINE_SXE.matcher(lineText)
            if (mSxe.find()) {
                try {
                    val s = mSxe.group(1).toInt()
                    val e = mSxe.group(2).toInt()
                    num = s * 1000 + e
                    label = s.toString() + "x" + String.format(Locale.US, "%02d", e)
                } catch (_: NumberFormatException) {
                    continue
                }
                rest = mSxe.group(3)
            } else {
                val m = EP_LINE.matcher(lineText)
                if (!m.find()) continue
                try {
                    num = m.group(1).toInt()
                } catch (_: NumberFormatException) {
                    continue
                }
                rest = m.group(2)
            }

            val players = mutableListOf<PlayerLink>()
            val seenUrls = mutableSetOf<String>()
            for (a in lineDoc.select("a[href]")) {
                val href = a.attr("href").trim()
                val name = a.text().trim()
                if (!isPlayerLink(name, href) || !seenUrls.add(href)) continue
                players.add(PlayerLink(if (name.isEmpty()) "PLAYER${players.size + 1}" else name, href))
            }

            var title = TITLE_CUT.matcher(rest).replaceFirst("")
            title = title.replace(Regex("\\s*[–—-]\\s*$"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (title.isEmpty()) title = "Episodio $num"

            val ep = ParsedEpisode(num, title, label, players)
            val prev = byNum[num]
            if (prev == null || ep.players.size > prev.players.size) byNum[num] = ep
        }
        return byNum.values.toList()
    }

    // ────────────────────────────── rete ──────────────────────────────

    private interface ToonItaliaService {

        companion object {
            fun build(): ToonItaliaService {
                val client = OkHttpClient.Builder()
                    .cookieJar(MyCookieJar())
                    .readTimeout(30, TimeUnit.SECONDS)
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .dns(DnsResolver.doh)
                    .build()

                val retrofit = Retrofit.Builder()
                    .baseUrl("https://toonitalia.xyz/")
                    .addConverterFactory(JsoupConverterFactory.create())
                    .client(client)
                    .build()

                return retrofit.create(ToonItaliaService::class.java)
            }

            fun buildUnsafe(): ToonItaliaService {
                val trustAllCerts = arrayOf<TrustManager>(
                    object : X509TrustManager {
                        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                    }
                )
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, trustAllCerts, SecureRandom())
                val trustManager = trustAllCerts[0] as X509TrustManager

                val client = OkHttpClient.Builder()
                    .cookieJar(MyCookieJar())
                    .readTimeout(30, TimeUnit.SECONDS)
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .sslSocketFactory(sslContext.socketFactory, trustManager)
                    .hostnameVerifier { _, _ -> true }
                    .build()

                val retrofit = Retrofit.Builder()
                    .baseUrl("https://toonitalia.xyz/")
                    .addConverterFactory(JsoupConverterFactory.create())
                    .client(client)
                    .build()

                return retrofit.create(ToonItaliaService::class.java)
            }
        }

        @Headers(USER_AGENT)
        @GET
        suspend fun getPage(@Url url: String): Document

        @Headers(USER_AGENT)
        @GET("wp-json/wp/v2/posts")
        suspend fun getCategoryPosts(
            @Query("categories") categories: Int,
            @Query("per_page") perPage: Int,
            @Query("page") page: Int,
            @Query("orderby") orderby: String,
            @Query("order") order: String,
            @Query("_fields") fields: String,
        ): ResponseBody

        @Headers(USER_AGENT)
        @GET("wp-json/wp/v2/posts")
        suspend fun searchPosts(
            @Query("search") search: String,
            @Query("per_page") perPage: Int,
            @Query("_fields") fields: String,
        ): ResponseBody
    }
}

