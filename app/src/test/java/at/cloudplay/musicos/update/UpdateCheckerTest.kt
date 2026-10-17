package at.cloudplay.musicos.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun parsesGithubRelease() {
        val json = JSONObject(
            """
            {"tag_name":"v0.1.12","body":"Neu: Verlauf\n- Details",
             "assets":[{"name":"notes.txt","browser_download_url":"https://x/notes.txt"},
                       {"name":"Music-os.apk","browser_download_url":"https://x/Music-os.apk"}]}
            """.trimIndent()
        )
        val info = UpdateChecker.parse(json)!!
        assertEquals(12, info.versionCode)
        assertEquals("0.1.12", info.versionName)
        assertEquals("https://x/Music-os.apk", info.apkUrl)
        assertEquals("Neu: Verlauf\n- Details", info.notes)
    }

    @Test
    fun ignoresReleaseWithoutApk() {
        assertNull(UpdateChecker.parse(JSONObject("""{"tag_name":"v0.1.3","assets":[]}""")))
    }

    @Test
    fun ignoresUnexpectedTag() {
        assertNull(UpdateChecker.parse(JSONObject("""{"tag_name":"latest","assets":[{"name":"a.apk","browser_download_url":"u"}]}""")))
    }
}
