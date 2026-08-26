package nl.tippie.cloudhub

import nl.tippie.cloudhub.data.ServerAddress
import nl.tippie.cloudhub.net.CloudHubApi
import nl.tippie.cloudhub.net.CloudHubClient
import nl.tippie.cloudhub.net.InMemoryCookieStore
import nl.tippie.cloudhub.net.PinnedCertificates
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How a request URL is built.
 *
 * These need no server, so they run on every build -- which is the point. The
 * bug they exist to catch only appeared on a subdirectory install, and the
 * live suite was pointed at a root install where it could not show up:
 *
 *   the app asked for /Cloud%20File%20Hub without a trailing slash;
 *   the web server answered 301 to add one;
 *   OkHttp follows a 301 by re-sending as a GET with the body dropped;
 *   the query survived, so route=/api/auth/login arrived as a GET;
 *   nothing matches GET /api/auth/login, so the reply was
 *   "API endpoint not found" -- naming an endpoint that exists.
 *
 * Every write broke that way, not just login: uploads are PUTs and deletes are
 * DELETEs, and 301 converts those to GET too.
 */
class UrlBuildingTest {

    private fun apiFor(baseUrl: String) = CloudHubApi(
        baseUrl,
        CloudHubClient(InMemoryCookieStore(), object : PinnedCertificates {
            override fun isPinned(fingerprint: String) = false
        }),
    )

    @Test fun `a subdirectory install addresses the front controller, not the directory`() {
        val url = apiFor("http://host:8000/Cloud%20File%20Hub").url("/api/auth/login")
        assertEquals("/Cloud%20File%20Hub/", url.encodedPath,
            "without the trailing slash the server redirects and the POST becomes a GET")
        assertEquals("/api/auth/login", url.queryParameter("route"))
    }

    @Test fun `a bare origin does not gain a doubled slash`() {
        val url = apiFor("http://host:8000").url("/api/files/list")
        assertEquals("/", url.encodedPath, "addPathSegment would have produced //")
    }

    @Test fun `a base that already ends in a slash is left alone`() {
        assertEquals("/sub/", apiFor("http://host/sub/").url("/api/files/list").encodedPath)
    }

    @Test fun `every url the app builds carries the trailing slash`() {
        val api = apiFor("http://host:8000/Cloud%20File%20Hub")
        // Thumbnails, streaming, downloads and preview all go through url(),
        // so one fix covers them -- but prove it rather than assume it.
        for (url in listOf(
            api.streamUrl("/a.mp4"),
            api.downloadUrl("/a.bin"),
            api.previewUrl("/a.png"),
            api.url("/api/uploads/chunk", "id" to "x"),
        )) {
            assertTrue(url.encodedPath.endsWith("/"), "$url does not address the front controller")
        }
    }

    @Test fun `extra query parameters survive alongside the route`() {
        val url = apiFor("http://host/sub").url("/api/files/list", "path" to "/Photos/a b")
        assertEquals("/api/files/list", url.queryParameter("route"))
        assertEquals("/Photos/a b", url.queryParameter("path"))
    }

    /* ---- the stored address ------------------------------------------- */

    @Test fun `a typed space and a typed percent-20 become the same address`() {
        assertEquals(
            ServerAddress.normalise("http://host:8000/Cloud File Hub"),
            ServerAddress.normalise("http://host:8000/Cloud%20File%20Hub"),
        )
    }

    @Test fun `the usual ways of typing one server all agree`() {
        val expected = "https://files.example.com"
        for (typed in listOf(
            "files.example.com",
            "https://files.example.com",
            "https://files.example.com/",
            "https://files.example.com/index.php",
            "  files.example.com  ",
        )) {
            assertEquals(expected, ServerAddress.normalise(typed), "typed as: $typed")
        }
    }

    @Test fun `nothing usable returns nothing`() {
        assertNull(ServerAddress.normalise(null))
        assertNull(ServerAddress.normalise("   "))
    }

    @Test fun `plain http is recognised so its cost can be stated`() {
        assertTrue(ServerAddress.isInsecure("http://192.168.1.10:8000"))
        assertTrue(!ServerAddress.isInsecure("https://files.example.com"))
    }
}
