package com.betalgezia.omnivpn.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateLogicTest {
    private val sha = "c3eb7c67" + "0".repeat(54) + "48"

    private val releaseJson = """
        {
          "tag_name": "v1.3.0",
          "draft": false,
          "prerelease": false,
          "body": "## OmniVPN 1.3.0\r\n\r\nТекст.",
          "assets": [
            {"name": "OmniVPN-1.3.0-armv7.apk", "size": 25000000, "digest": null,
             "browser_download_url": "https://github.com/Betalgezia/OmniVPN/releases/download/v1.3.0/OmniVPN-1.3.0-armv7.apk"},
            {"name": "OmniVPN-1.3.0-arm64.apk", "size": 27262976, "digest": "sha256:$sha",
             "browser_download_url": "https://github.com/Betalgezia/OmniVPN/releases/download/v1.3.0/OmniVPN-1.3.0-arm64.apk"},
            {"name": "broken.apk", "browser_download_url": "http://example.com/x.apk"}
          ]
        }
    """.trimIndent()

    @Test
    fun versions() {
        assertEquals(listOf(1, 2, 0), parseVersion("v1.2.0"))
        assertEquals(listOf(1, 3), parseVersion("1.3-beta"))
        assertNull(parseVersion("dev.214"))
        assertNull(parseVersion(""))
        assertTrue(isNewerVersion("1.3.0", "1.2.0"))
        assertTrue(isNewerVersion("1.10.0", "1.9.9"))
        assertTrue(isNewerVersion("1.2.1", "1.2"))
        assertFalse(isNewerVersion("1.2.0", "1.2"))
        assertFalse(isNewerVersion("1.2.0", "1.2.0"))
        assertFalse(isNewerVersion("1.1.9", "1.2.0"))
        // A local build ("dev.N") is never offered an update automatically.
        assertFalse(isNewerVersion("1.3.0", "dev.214"))
    }

    @Test
    fun checkInterval() {
        val hour = 60 * 60 * 1000L
        assertTrue(updateCheckDue(0, 10 * hour))
        assertFalse(updateCheckDue(10 * hour, 15 * hour))
        assertTrue(updateCheckDue(10 * hour, 16 * hour))
        assertTrue(updateCheckDue(10 * hour, 9 * hour))
    }

    @Test
    fun parsesTheLatestRelease() {
        val release = parseReleaseJson(releaseJson)!!
        assertEquals("v1.3.0", release.tag)
        assertEquals("1.3.0", release.version)
        assertEquals(2, release.assets.size)
        val arm64 = release.assets.first { it.name.endsWith("arm64.apk") }
        assertEquals(sha, arm64.sha256)
        assertEquals(27262976L, arm64.sizeBytes)
        assertNull(release.assets.first { it.name.endsWith("armv7.apk") }.sha256)

        assertNull(parseReleaseJson("""{"tag_name":"v1.4.0","prerelease":true,"assets":[]}"""))
        assertNull(parseReleaseJson("""{"tag_name":"nightly","assets":[]}"""))
        assertNull(parseReleaseJson("not json"))
    }

    @Test
    fun picksTheApkForThePhone() {
        val release = parseReleaseJson(releaseJson)!!
        assertEquals("OmniVPN-1.3.0-arm64.apk", pickAsset(release, listOf("arm64-v8a", "armeabi-v7a", "armeabi"))?.name)
        assertEquals("OmniVPN-1.3.0-armv7.apk", pickAsset(release, listOf("armeabi-v7a", "armeabi"))?.name)
        assertNull(pickAsset(release, listOf("x86_64")))
    }

    @Test
    fun fallbackByRedirect() {
        assertEquals("v1.3.0", tagFromReleaseUrl("https://github.com/Betalgezia/OmniVPN/releases/tag/v1.3.0"))
        assertNull(tagFromReleaseUrl("https://github.com/Betalgezia/OmniVPN/releases"))
        assertNull(tagFromReleaseUrl(null))
        val release = releaseFromTag("v1.3.0")
        assertEquals("1.3.0", release.version)
        assertEquals(
            "https://github.com/Betalgezia/OmniVPN/releases/download/v1.3.0/OmniVPN-1.3.0-arm64.apk",
            pickAsset(release, listOf("arm64-v8a"))?.url
        )
    }

    @Test
    fun cacheRoundTrips() {
        val release = parseReleaseJson(releaseJson)!!
        assertEquals(release, decodeRelease(encodeRelease(release)))
        assertNull(decodeRelease("{}"))
        assertNull(decodeRelease(null))
    }

    @Test
    fun sizes() {
        assertEquals("26 МБ", formatMegabytes(27262976))
        assertEquals("2,5 МБ", formatMegabytes(2621440))
        assertEquals("1 МБ", formatMegabytes(1048576))
    }

    @Test
    fun releaseNotes() {
        val notes = """
            ## OmniVPN 1.2.0 — новый дизайн

            Приложение полностью переделано:
            светлая и тёмная тема.

            ### Что нового
            - **Главная.** Большая кнопка подключения
              со всеми состояниями.
            - Обычный пункт со `ссылкой` [на GitHub](https://github.com).

            ### Какой файл скачать
            - **OmniVPN-1.2.0-arm64.apk** — для большинства телефонов.

            Ставится поверх прошлой версии.

            ### Исправлено
            * Уведомление
            ---
            **Full Changelog**: https://github.com/Betalgezia/OmniVPN/compare/v1.1.1...v1.2.0
        """.trimIndent()
        assertEquals(
            listOf(
                NoteBlock.Paragraph("Приложение полностью переделано: светлая и тёмная тема."),
                NoteBlock.Heading("Что нового"),
                NoteBlock.Bullet("Главная.", "Большая кнопка подключения со всеми состояниями."),
                NoteBlock.Bullet(null, "Обычный пункт со ссылкой на GitHub."),
                NoteBlock.Heading("Исправлено"),
                NoteBlock.Bullet(null, "Уведомление")
            ),
            releaseNoteBlocks(notes)
        )
        assertEquals(emptyList(), releaseNoteBlocks(""))
    }
}
