package nl.tippie.cloudhub

import kotlinx.serialization.json.Json
import nl.tippie.cloudhub.net.MyStorage
import nl.tippie.cloudhub.net.ServerStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The storage endpoints send shapes this client has to decode without throwing.
 *
 * `/api/storage/usage` reports `folders` as a list of per-folder rows -- not a
 * count -- so a `ServerStorage.folders: Int` field made kotlinx reject the
 * whole payload the moment a store had any folders, and the admin's "By
 * account" panel silently never loaded. These decode the real server shapes.
 */
class StorageContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `server storage decodes when folders is a per-folder array`() {
        // The exact shape public/index.php returns from /api/storage/usage:
        // storageReport() puts `folders` as objects, and byUser/largest as
        // their own arrays.
        val payload = """
            {"bytes":123,"files":4,
             "folders":[{"name":"Photos","path":"/Photos","bytes":100,"files":3},
                        {"name":"Docs","path":"/Docs","bytes":23,"files":1}],
             "diskFree":900,"diskTotal":1000,
             "trash":{"bytes":0,"files":0,"entries":0},
             "storageLimitBytes":0,"userQuotaBytes":0,"cached":false,
             "byUser":[{"userId":1,"username":"admin","bytes":123,"files":4}],
             "largest":[{"path":"/Photos/a.jpg","bytes":100}]}
        """.trimIndent()

        val decoded = json.decodeFromString<ServerStorage>(payload)
        assertEquals(123, decoded.bytes)
        assertEquals(1, decoded.byUser.size)
        assertEquals("admin", decoded.byUser.first().username)
        assertEquals(1, decoded.largest.size)
    }

    @Test
    fun `my storage decodes the per-account view`() {
        // /api/storage/me reports `folders` as a count, so the field there is
        // an Int and must stay one.
        val payload = """
            {"usedBytes":50,"quotaBytes":0,"storeUsedBytes":123,"storageLimitBytes":0,
             "diskFreeBytes":900,"diskTotalBytes":1000,"files":4,"folders":2,
             "trash":{"bytes":0,"files":0,"entries":0},
             "versions":{"bytes":0,"files":0},
             "cached":false,"measuredAt":null,"isAdmin":true}
        """.trimIndent()

        val decoded = json.decodeFromString<MyStorage>(payload)
        assertEquals(2, decoded.folders)
        assertTrue(decoded.isAdmin)
    }
}
