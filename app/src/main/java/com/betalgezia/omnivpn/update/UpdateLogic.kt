package com.betalgezia.omnivpn.update

import java.net.URLDecoder
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

// The pure half of the in-app updater (see AppUpdater): versions, the GitHub
// release JSON, which APK suits this phone, and the release notes as text.

const val GITHUB_REPO = "Betalgezia/OmniVPN"
const val LATEST_RELEASE_API = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
const val LATEST_RELEASE_PAGE = "https://github.com/$GITHUB_REPO/releases/latest"

/** How often the app looks for a new release on its own (on launch). */
const val UPDATE_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000

/** One APK attached to a release. [sha256] is GitHub's digest, when it has one. */
data class ReleaseAsset(val name: String, val url: String, val sizeBytes: Long, val sha256: String?)

/** The newest published release - drafts and pre-releases are never "latest". */
data class ReleaseInfo(
    val tag: String,
    val version: String,
    val notes: String,
    val assets: List<ReleaseAsset>
)

/** "v1.2.0", "1.2", "1.3.0-beta" → [1, 2, 0]...; anything else (a local "dev.42") → null. */
fun parseVersion(text: String?): List<Int>? {
    val core = text?.trim()?.removePrefix("v")?.removePrefix("V")
        ?.substringBefore('-')?.substringBefore('+')
        ?: return null
    if (core.isEmpty()) return null
    val parts = core.split('.')
    if (parts.size > 4) return null
    return parts.map { part -> part.toIntOrNull()?.takeIf { it >= 0 } ?: return null }
}

/** Whether [candidate] is a later version than [current]; false if either isn't a version. */
fun isNewerVersion(candidate: String, current: String): Boolean {
    val a = parseVersion(candidate) ?: return false
    val b = parseVersion(current) ?: return false
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

/** Due for an automatic check: never checked, [intervalMs] passed, or the clock went back. */
fun updateCheckDue(lastCheckMs: Long, nowMs: Long, intervalMs: Long = UPDATE_CHECK_INTERVAL_MS): Boolean =
    lastCheckMs <= 0L || nowMs < lastCheckMs || nowMs - lastCheckMs >= intervalMs

private fun JSONObject.text(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

/** GET /repos/{repo}/releases/latest → [ReleaseInfo]; null for anything that isn't a usable release. */
fun parseReleaseJson(json: String): ReleaseInfo? = parseRelease(json)

private fun parseRelease(json: String): ReleaseInfo? {
    val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
    if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
    val tag = o.text("tag_name") ?: return null
    val version = tag.trim().removePrefix("v").removePrefix("V")
    if (parseVersion(version) == null) return null
    val array = o.optJSONArray("assets") ?: JSONArray()
    val assets = (0 until array.length()).mapNotNull { i ->
        val a = array.optJSONObject(i) ?: return@mapNotNull null
        val name = a.text("name") ?: return@mapNotNull null
        val url = a.text("browser_download_url")?.takeIf { it.startsWith("https://") } ?: return@mapNotNull null
        val digest = a.text("digest")?.takeIf { it.startsWith("sha256:", ignoreCase = true) }
            ?.substring("sha256:".length)?.lowercase()
            ?.takeIf { it.length == 64 && it.all { ch -> ch in '0'..'9' || ch in 'a'..'f' } }
        ReleaseAsset(name, url, a.optLong("size", -1L), digest)
    }
    return ReleaseInfo(tag = tag, version = version, notes = o.text("body").orEmpty(), assets = assets)
}

/**
 * The other way to find the latest release: github.com/{repo}/releases/latest
 * redirects to …/releases/tag/<tag>. Used when the API refuses (it allows 60
 * requests an hour per IP, and a VPN exit is shared by many people).
 */
fun tagFromReleaseUrl(location: String?): String? {
    val tail = location?.substringAfter("/releases/tag/", missingDelimiterValue = "")
        ?.substringBefore('?')?.substringBefore('#')?.trimEnd('/')
    if (tail.isNullOrEmpty() || '/' in tail) return null
    return runCatching { URLDecoder.decode(tail, "UTF-8") }.getOrNull()?.takeIf { parseVersion(it) != null }
}

/** A release known only by its tag: the APK names release.yml always uses, no notes. */
fun releaseFromTag(tag: String): ReleaseInfo {
    val version = tag.trim().removePrefix("v").removePrefix("V")
    val base = "https://github.com/$GITHUB_REPO/releases/download/$tag"
    return ReleaseInfo(
        tag = tag,
        version = version,
        notes = "",
        assets = listOf("arm64", "armv7").map { arch ->
            val name = "OmniVPN-$version-$arch.apk"
            ReleaseAsset(name, "$base/$name", -1L, null)
        }
    )
}

/**
 * The APK for this phone, by its ABIs in order of preference (Build.SUPPORTED_ABIS):
 * release.yml names them OmniVPN-<v>-arm64.apk and OmniVPN-<v>-armv7.apk.
 */
fun pickAsset(release: ReleaseInfo, supportedAbis: List<String>): ReleaseAsset? {
    for (abi in supportedAbis) {
        val suffix = when (abi) {
            "arm64-v8a" -> "-arm64.apk"
            "armeabi-v7a" -> "-armv7.apk"
            else -> continue
        }
        release.assets.firstOrNull { it.name.endsWith(suffix, ignoreCase = true) }?.let { return it }
    }
    return null
}

/** For the cache in UpdateStore, so the card is there at launch before any network. */
fun encodeRelease(release: ReleaseInfo): String = JSONObject().apply {
    put("tag", release.tag)
    put("version", release.version)
    put("notes", release.notes)
    put(
        "assets",
        JSONArray().apply {
            release.assets.forEach { a ->
                put(
                    JSONObject().apply {
                        put("name", a.name)
                        put("url", a.url)
                        put("size", a.sizeBytes)
                        a.sha256?.let { put("sha256", it) }
                    }
                )
            }
        }
    )
}.toString()

fun decodeRelease(json: String?): ReleaseInfo? {
    if (json.isNullOrBlank()) return null
    return runCatching {
        val o = JSONObject(json)
        val array = o.getJSONArray("assets")
        ReleaseInfo(
            tag = o.getString("tag"),
            version = o.getString("version"),
            notes = o.text("notes").orEmpty(),
            assets = (0 until array.length()).map { i ->
                val a = array.getJSONObject(i)
                ReleaseAsset(a.getString("name"), a.getString("url"), a.optLong("size", -1L), a.text("sha256"))
            }
        )
    }.getOrNull()
}

/** «26 МБ», «12,4 МБ» - sizes on the update sheet. */
fun formatMegabytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    val text = if (mb >= 10 || mb == Math.floor(mb)) {
        String.format(Locale.ROOT, "%.0f", mb)
    } else {
        String.format(Locale.ROOT, "%.1f", mb)
    }
    return text.replace('.', ',') + " МБ"
}

// ---- Release notes ----

/** The release description, reduced to what a phone screen can show without a Markdown renderer. */
sealed interface NoteBlock {
    data class Heading(val text: String) : NoteBlock
    /** [lead]: a leading «**Главная.**», shown bold. */
    data class Bullet(val lead: String?, val text: String) : NoteBlock
    data class Paragraph(val text: String) : NoteBlock
}

private val LINK = Regex("""!?\[([^\]]*)]\([^)]*\)""")
private val HTML_COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
private val LINE_BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
private val SPACES = Regex("""\s+""")
private val BULLET = Regex("""^\s*(?:[-*+]|\d+[.)])\s+(.*)$""")
private val HEADING = Regex("""^\s*(#{1,6})\s+(.*?)\s*#*\s*$""")
private val LEAD = Regex("""^\*\*(.+?)\*\*\s*(.*)$""")
private val RULE = Regex("""^([-*_])(\s*\1){2,}$""")

private fun stripInline(text: String): String = text
    .replace(LINK) { it.groupValues[1] }
    .replace("**", "")
    .replace("__", "")
    .replace("`", "")
    .replace(LINE_BREAK, " ")
    .replace(SPACES, " ")
    .trim()

/**
 * Turns the Markdown of a release description into blocks. Leaves out the
 * top-level title (the sheet has its own), the «Какой файл скачать» section
 * (the app picks the file itself), GitHub's «Full Changelog» line and rules.
 */
fun releaseNoteBlocks(markdown: String): List<NoteBlock> {
    val blocks = mutableListOf<NoteBlock>()
    val paragraph = StringBuilder()
    var bulletLead: String? = null
    var bullet: StringBuilder? = null
    var skipping = false
    var inFence = false

    fun flush() {
        val b = bullet
        if (b != null) {
            val text = stripInline(b.toString())
            if (text.isNotEmpty() || bulletLead != null) blocks += NoteBlock.Bullet(bulletLead, text)
        }
        bullet = null
        bulletLead = null
        if (paragraph.isNotEmpty()) {
            stripInline(paragraph.toString()).takeIf { it.isNotEmpty() }?.let { blocks += NoteBlock.Paragraph(it) }
            paragraph.clear()
        }
    }

    val lines = markdown.replace(HTML_COMMENT, "").replace("\r\n", "\n").replace('\r', '\n').split('\n')
    for (raw in lines) {
        val line = raw.trimEnd()
        if (line.trimStart().startsWith("```")) {
            flush()
            inFence = !inFence
            continue
        }
        if (inFence) continue
        val heading = HEADING.matchEntire(line)
        if (heading != null) {
            flush()
            val level = heading.groupValues[1].length
            val title = stripInline(heading.groupValues[2])
            skipping = title.contains("скачать", ignoreCase = true) || title.contains("какой файл", ignoreCase = true)
            if (!skipping && level >= 3 && title.isNotEmpty()) blocks += NoteBlock.Heading(title)
            continue
        }
        if (skipping) continue
        val trimmed = line.trim()
        if (trimmed.isEmpty() || RULE.matches(trimmed)) {
            flush()
            continue
        }
        if (trimmed.startsWith("**Full Changelog**", ignoreCase = true)) continue
        val item = BULLET.matchEntire(line)
        if (item != null) {
            flush()
            val body = item.groupValues[1].trim()
            val lead = LEAD.matchEntire(body)
            if (lead != null) {
                bulletLead = stripInline(lead.groupValues[1])
                bullet = StringBuilder(lead.groupValues[2])
            } else {
                bullet = StringBuilder(body)
            }
            continue
        }
        val current = bullet
        if (current != null && raw.startsWith(" ")) {
            current.append(' ').append(trimmed)
        } else {
            if (current != null) flush()
            if (paragraph.isNotEmpty()) paragraph.append(' ')
            paragraph.append(trimmed)
        }
    }
    flush()
    return blocks
}
