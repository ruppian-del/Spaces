package com.arcinteractive.spaces.data.spaces

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Patterns
import com.arcinteractive.spaces.data.model.LinkPreviewData
import com.arcinteractive.spaces.data.model.SpaceLinkAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class EncryptedTextMessageContent(
    val version: Int = 1,
    val text: String,
    val linkPreview: LinkPreviewData? = null,
    val spaceLinks: List<SpaceLinkAttachment>? = null
)

class LinkPreviewService {
    private val cacheTtlMillis = 7L * 24L * 60L * 60L * 1000L

    fun firstUrl(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val matcher = Patterns.WEB_URL.matcher(trimmed)
        while (matcher.find()) {
            val raw = matcher.group().orEmpty()
            val normalized = normalizeUrl(raw) ?: continue
            return normalized
        }

        trimmed.split(Regex("\\s+")).forEach { token ->
            normalizeUrl(token)?.let { return it }
        }
        return null
    }

    suspend fun cachedPreview(context: Context, url: String): LinkPreviewData? = withContext(Dispatchers.IO) {
        val key = cacheKey(url)
        val json = cachePrefs(context).getString(key, null) ?: return@withContext null
        val parsed = runCatching { JSONObject(json) }.getOrNull() ?: return@withContext null
        val cachedAt = parsed.optLong("cachedAt", 0L)
        if (cachedAt <= 0L || System.currentTimeMillis() - cachedAt > cacheTtlMillis) {
            cachePrefs(context).edit().remove(key).apply()
            return@withContext null
        }
        parsePreviewJson(parsed.optJSONObject("preview"))
    }

    suspend fun preview(context: Context, url: String): LinkPreviewData? = withContext(Dispatchers.IO) {
        cachedPreview(context, url)?.let { return@withContext it }

        val normalizedUrl = normalizeUrl(url) ?: return@withContext null
        val htmlResult = fetchText(normalizedUrl) ?: return@withContext null
        val metadata = parseMetadata(htmlResult.html, normalizedUrl)
        val title = sanitize(metadata.title, 120) ?: return@withContext null
        val previewImage = metadata.imageUrl?.let { fetchImageBytes(it) }
        val preview = LinkPreviewData(
            originalUrl = normalizedUrl,
            canonicalUrl = metadata.canonicalUrl,
            domain = domain(metadata.canonicalUrl ?: normalizedUrl),
            title = title,
            summary = sanitize(metadata.description, 220),
            siteName = sanitize(metadata.siteName, 80),
            imageDataBase64 = previewImage?.first?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
            imageMimeType = previewImage?.second
        )
        storePreview(context, normalizedUrl, preview)
        preview
    }

    private fun cachePrefs(context: Context) =
        context.applicationContext.getSharedPreferences("spaces_link_preview_cache", Context.MODE_PRIVATE)

    private fun storePreview(context: Context, url: String, preview: LinkPreviewData) {
        val json = JSONObject().apply {
            put("cachedAt", System.currentTimeMillis())
            put("preview", JSONObject().apply {
                put("originalUrl", preview.originalUrl)
                put("canonicalUrl", preview.canonicalUrl)
                put("domain", preview.domain)
                put("title", preview.title)
                put("summary", preview.summary)
                put("siteName", preview.siteName)
                put("imageDataBase64", preview.imageDataBase64)
                put("imageMimeType", preview.imageMimeType)
            })
        }
        cachePrefs(context).edit().putString(cacheKey(url), json.toString()).apply()
    }

    private fun parsePreviewJson(json: JSONObject?): LinkPreviewData? {
        json ?: return null
        val originalUrl = json.optString("originalUrl").ifBlank { return null }
        val domain = json.optString("domain").ifBlank { return null }
        val title = json.optString("title").ifBlank { return null }
        return LinkPreviewData(
            originalUrl = originalUrl,
            canonicalUrl = json.optString("canonicalUrl").ifBlank { null },
            domain = domain,
            title = title,
            summary = json.optString("summary").ifBlank { null },
            siteName = json.optString("siteName").ifBlank { null },
            imageDataBase64 = json.optString("imageDataBase64").ifBlank { null },
            imageMimeType = json.optString("imageMimeType").ifBlank { null }
        )
    }

    private fun cacheKey(url: String): String = normalizeUrl(url)?.lowercase().orEmpty()

    private fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim().trimEnd('.', ',', ';', ':', '!', '?', ')', ']')
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.startsWith("www.", ignoreCase = true)) {
            "https://$trimmed"
        } else {
            trimmed
        }
        return runCatching {
            val uri = URI(withScheme)
            val scheme = uri.scheme?.lowercase()
            if (scheme == "http" || scheme == "https") uri.toString() else null
        }.getOrNull()
    }

    private data class HtmlFetchResult(
        val finalUrl: String,
        val html: String
    )

    private fun fetchText(urlString: String): HtmlFetchResult? {
        val connection = (URL(urlString).openConnection() as? HttpURLConnection) ?: return null
        return try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "Spaces/1.1")
            connection.connect()
            if (connection.responseCode !in 200..399) return null
            val finalUrl = connection.url.toString()
            val body = connection.inputStream.buffered().reader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(256_000)
                val read = reader.read(buffer)
                if (read <= 0) "" else String(buffer, 0, read)
            }
            if (body.isBlank()) null else HtmlFetchResult(finalUrl, body)
        } catch (_: Throwable) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchImageBytes(urlString: String): Pair<ByteArray, String>? {
        val connection = (URL(urlString).openConnection() as? HttpURLConnection) ?: return null
        return try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "Spaces/1.1")
            connection.connect()
            if (connection.responseCode !in 200..399) return null
            val raw = connection.inputStream.use { it.readBytes() }
            val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
            val resized = resizeBitmap(bitmap)
            val output = ByteArrayOutputStream()
            resized.compress(android.graphics.Bitmap.CompressFormat.JPEG, 78, output)
            output.toByteArray() to "image/jpeg"
        } catch (_: Throwable) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun resizeBitmap(bitmap: android.graphics.Bitmap): android.graphics.Bitmap {
        val maxDimension = 720f
        val largest = maxOf(bitmap.width, bitmap.height).toFloat()
        if (largest <= maxDimension) return bitmap
        val scale = maxDimension / largest
        return android.graphics.Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt(),
            (bitmap.height * scale).toInt(),
            true
        )
    }

    private data class ParsedMetadata(
        val title: String?,
        val description: String?,
        val siteName: String?,
        val canonicalUrl: String?,
        val imageUrl: String?
    )

    private fun parseMetadata(html: String, sourceUrl: String): ParsedMetadata {
        val ogTitle = metaContent(html, property = "og:title")
        val ogDescription = metaContent(html, property = "og:description")
        val ogSiteName = metaContent(html, property = "og:site_name")
        val ogImage = metaContent(html, property = "og:image")
        return ParsedMetadata(
            title = ogTitle ?: titleTag(html),
            description = ogDescription ?: metaContent(html, name = "description"),
            siteName = ogSiteName,
            canonicalUrl = canonicalUrl(html, sourceUrl),
            imageUrl = resolveUrl(ogImage, sourceUrl)
        )
    }

    private fun metaContent(html: String, property: String? = null, name: String? = null): String? {
        val attributeName = if (property != null) "property" else "name"
        val attributeValue = Regex.escape(property ?: name ?: "")
        val patterns = listOf(
            """<meta[^>]*$attributeName\s*=\s*["']$attributeValue["'][^>]*content\s*=\s*["']([^"']+)["'][^>]*>""",
            """<meta[^>]*content\s*=\s*["']([^"']+)["'][^>]*$attributeName\s*=\s*["']$attributeValue["'][^>]*>"""
        )
        return patterns.firstNotNullOfOrNull { pattern ->
            Regex(pattern, setOf(RegexOption.IGNORE_CASE)).find(html)?.groupValues?.getOrNull(1)
        }
    }

    private fun titleTag(html: String): String? {
        return Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun canonicalUrl(html: String, sourceUrl: String): String? {
        val patterns = listOf(
            """<link[^>]*rel\s*=\s*["']canonical["'][^>]*href\s*=\s*["']([^"']+)["'][^>]*>""",
            """<link[^>]*href\s*=\s*["']([^"']+)["'][^>]*rel\s*=\s*["']canonical["'][^>]*>"""
        )
        return patterns.firstNotNullOfOrNull { pattern ->
            Regex(pattern, setOf(RegexOption.IGNORE_CASE))
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.let { resolveUrl(it, sourceUrl) }
        }
    }

    private fun resolveUrl(raw: String?, sourceUrl: String): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        return runCatching {
            val uri = URI(value)
            if (uri.scheme != null) {
                uri.toString()
            } else {
                URI(sourceUrl).resolve(uri).toString()
            }
        }.getOrNull()
    }

    private fun sanitize(text: String?, limit: Int): String? {
        val decoded = decodeEntities(text.orEmpty())
            .replace(Regex("<[^>]+>"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (decoded.isEmpty()) return null
        return decoded.take(limit)
    }

    private fun decodeEntities(text: String): String {
        return text
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
    }

    private fun domain(urlString: String): String {
        return runCatching { URI(urlString).host.orEmpty().removePrefix("www.") }.getOrDefault(urlString)
    }
}
