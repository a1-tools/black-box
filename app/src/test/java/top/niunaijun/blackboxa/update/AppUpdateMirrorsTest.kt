package top.niunaijun.blackboxa.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateMirrorsTest {
    private val path = "a1-tools/black-box/main/update/app-version.json"

    @Test
    fun `a gh-proxy address gives the same file through every mirror in the reference order`() {
        val url = "https://gh-proxy.com/https://raw.githubusercontent.com/$path"
        assertEquals(
            listOf(
                url,
                "https://ghfast.top/https://raw.githubusercontent.com/$path",
                "https://cdn.jsdelivr.net/gh/a1-tools/black-box@main/update/app-version.json",
                "https://raw.githubusercontent.com/$path",
                "https://ghproxy.net/https://raw.githubusercontent.com/$path",
            ),
            AppUpdate.mirrorsOf(url),
        )
    }

    @Test
    fun `a raw address is expanded the same way`() {
        val gh = AppUpdate.mirrorsOf("https://gh-proxy.com/https://raw.githubusercontent.com/$path")
        assertEquals(gh, AppUpdate.mirrorsOf("https://raw.githubusercontent.com/$path"))
    }

    @Test
    fun `jsdelivr is skipped for files over 20MB`() {
        val url = "https://raw.githubusercontent.com/$path"
        assertTrue(AppUpdate.mirrorsOf(url, 5L * 1024 * 1024).any { "jsdelivr" in it })
        assertFalse(AppUpdate.mirrorsOf(url, 30L * 1024 * 1024).any { "jsdelivr" in it })
    }

    @Test
    fun `a github release address goes through the proxies but not jsdelivr`() {
        val url = "https://github.com/a1-tools/black-box/releases/download/v4.0.9/BlackBox_4.0.9_arm64-v8a-release.apk"
        assertEquals(
            listOf("https://gh-proxy.com/$url", "https://ghfast.top/$url", url, "https://ghproxy.net/$url"),
            AppUpdate.mirrorsOf(url),
        )
    }

    @Test
    fun `any other address is used as it is`() {
        assertEquals(listOf("https://example.com/a.apk"), AppUpdate.mirrorsOf("https://example.com/a.apk"))
    }

    @Test
    fun `candidates put the manifest mirrors first and drop non-http entries`() {
        val info = AppUpdate.Info(
            code = 1, name = "1", url = "https://example.com/a.apk", sha256 = "", notes = "", size = 0, force = false,
            mirrors = listOf("https://m.example.com/a.apk", "ftp://bad/a.apk"),
        )
        assertEquals(listOf("https://m.example.com/a.apk", "https://example.com/a.apk"), info.candidates())
    }
}
