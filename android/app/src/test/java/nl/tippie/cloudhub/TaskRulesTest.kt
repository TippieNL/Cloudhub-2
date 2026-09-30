package nl.tippie.cloudhub

import kotlinx.serialization.json.Json
import nl.tippie.cloudhub.net.BackgroundTask
import nl.tippie.cloudhub.net.FileEntry
import nl.tippie.cloudhub.net.RelocateResult
import nl.tippie.cloudhub.net.SimpleResult
import nl.tippie.cloudhub.net.TaskList
import nl.tippie.cloudhub.net.TaskProgress
import nl.tippie.cloudhub.ui.TaskRules
import org.junit.Test
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the app says about background tasks, and when it looks again.
 *
 * The replies below are Cloudhub-web's own, as JobTypes::present() writes
 * them, so a change to their shape on the server shows up here first.
 */
class TaskRulesTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun task(body: String): BackgroundTask = json.decodeFromString(body)
    private fun bytes(n: Long) = "${n}B"

    private val checksumDone = task("""
        {"id":"01a0f3c54dbb854f2820c9982a576808","type":"checksum","label":"SHA-256 of \"hello.txt\"",
         "target":"/_q","status":"completed","progress":{"done":12,"total":12,"unit":"bytes","percent":100},
         "currentItem":null,"error":null,
         "result":{"algorithm":"sha256","files":[{"path":"/_q/hello.txt","bytes":12,
           "hash":"a948904f2f0f479b8f8197694b30184b0d2ed1c1cd2a1ec0fb85d299a192a447"}]},
         "attempts":1,"cancelRequested":false,"canCancel":false,"canRetry":false,"canRemove":true,
         "hasDownload":false,"createdAt":"2026-09-30T19:23:09+00:00","startedAt":"2026-09-30T19:23:13+00:00",
         "finishedAt":"2026-09-30T19:23:13+00:00"}
    """)

    private fun running(done: Long = 3, total: Long = 12, percent: Int? = 25, unit: String = "items") =
        BackgroundTask(id = "a", type = "copy", label = "Copy 3 items", status = TaskRules.PROCESSING,
            progress = TaskProgress(done, total, unit, percent))

    /* ---- reading the server's replies ------------------------------------------ */

    @Test
    fun `a task reply decodes as the server writes it`() {
        assertEquals("checksum", checksumDone.type)
        assertEquals(100, checksumDone.progress.percent)
        assertTrue(checksumDone.canRemove)
        assertFalse(checksumDone.hasDownload)
    }

    @Test
    fun `an empty result, which PHP writes as a list, does not break the reply`() {
        // json_encode([]) is "[]": a typed result class would refuse the whole task.
        val t = task("""{"id":"b","type":"copy","label":"Copy","status":"failed","result":[],"error":"Disk full"}""")
        assertNull(TaskRules.resultText(t, ::bytes))
        assertEquals(emptyList(), TaskRules.failures(t))
        assertEquals("Failed: Copy — Disk full", TaskRules.finishedMessage(t))
    }

    @Test
    fun `a delete or copy the server queued says so, and one it did at once does not`() {
        val queued = json.decodeFromString<SimpleResult>("""
            {"success":true,"trashed":false,"queued":true,"message":"Deleting in the background",
             "job":{"id":"c","type":"purge","label":"Delete \"Photos\"","status":"pending"}}
        """)
        assertTrue(queued.queued)
        assertEquals("purge", queued.job?.type)

        val copy = json.decodeFromString<RelocateResult>("""
            {"success":true,"queued":true,"message":"Copying in the background",
             "job":{"id":"d","type":"copy","label":"Copy 400 items","status":"pending"}}
        """)
        assertTrue(copy.queued)
        assertEquals("Copying in the background", copy.message)

        // Cloudhub-2's own server, or a small copy: no queue in sight.
        val plain = json.decodeFromString<RelocateResult>("""{"success":true,"completed":2,"failed":[]}""")
        assertFalse(plain.queued)
        assertNull(plain.job)
    }

    @Test
    fun `a server without the jobs table says so rather than failing`() {
        val list = json.decodeFromString<TaskList>("""
            {"jobs":[],"active":0,"runner":"none","available":false,"message":"Background tasks are not available: run php database/migrate.php"}
        """)
        assertFalse(list.available)
        assertEquals("Background tasks are not available: run php database/migrate.php", TaskRules.note(list))
        // An older Cloudhub-web does not send the key: its queue is there.
        assertTrue(json.decodeFromString<TaskList>("""{"jobs":[],"active":0,"runner":"cli"}""").available)
    }

    /* ---- what a task says about itself ----------------------------------------- */

    @Test
    fun `status reads as on the web, and a stop asked for is shown`() {
        assertEquals("Queued", TaskRules.statusLabel(running().copy(status = TaskRules.PENDING)))
        assertEquals("Running", TaskRules.statusLabel(running()))
        assertEquals("Stopping…", TaskRules.statusLabel(running().copy(cancelRequested = true)))
        assertEquals("Completed", TaskRules.statusLabel(checksumDone))
        // Once it has stopped, it is simply cancelled.
        assertEquals("Cancelled", TaskRules.statusLabel(running().copy(status = TaskRules.CANCELLED, cancelRequested = true)))
    }

    @Test
    fun `progress counts items or bytes, and says when it cannot`() {
        assertEquals("3 of 12 · 25%", TaskRules.progressText(running(), ::bytes))
        assertEquals("100B of 400B · 25%", TaskRules.progressText(running(100, 400, 25, "bytes"), ::bytes))
        assertEquals("Starting…", TaskRules.progressText(running(0, 0, null), ::bytes))
        assertEquals("Waiting to start", TaskRules.progressText(running().copy(status = TaskRules.PENDING), ::bytes))
        assertEquals("12B of 12B · 100%", TaskRules.progressText(checksumDone, ::bytes))
    }

    @Test
    fun `a queued task has no bar, a running one without a total an indeterminate one`() {
        assertFalse(TaskRules.showsBar(running().copy(status = TaskRules.PENDING)))
        assertTrue(TaskRules.showsBar(running()))
        assertEquals(0.25f, TaskRules.fraction(running()))
        assertNull(TaskRules.fraction(running(0, 0, null)))
        assertEquals(1f, TaskRules.fraction(checksumDone))
    }

    @Test
    fun `each type says what it did`() {
        fun result(type: String, r: String) = TaskRules.resultText(task("""{"type":"$type","status":"completed","result":$r}"""), ::bytes)
        assertEquals("18 of 20 copied into /Backup", result("copy", """{"destination":"/Backup","copied":18,"of":20,"failed":[]}"""))
        assertEquals("/Photos/Trip.zip · 2048B · 31 files", result("archive", """{"name":"Trip.zip","path":"/Photos/Trip.zip","bytes":2048,"files":31}"""))
        assertEquals("Trip.zip · 10B · 1 file", result("archive", """{"name":"Trip.zip","bytes":10,"files":1,"download":true}"""))
        assertEquals("Extracted 5 files into /Trip · 1 link skipped", result("extract", """{"path":"/Trip","files":5,"skipped":1}"""))
        assertEquals("1 file deleted", result("purge", """{"files":1,"entries":1}"""))
        assertEquals("4 made · 10 already there", result("thumbnails", """{"path":"/","made":4,"existing":10,"skipped":0}"""))
        assertEquals("2 groups of duplicates · 900B reclaimable", result("duplicates", """{"groups":2,"reclaimable":900}"""))
        // Listed in full instead.
        assertNull(TaskRules.resultText(checksumDone, ::bytes))
    }

    @Test
    fun `a copy's failures are listed per item`() {
        val t = task("""{"type":"copy","status":"completed","result":{"destination":"/B","copied":1,"of":2,
            "failed":[{"path":"/A/x.jpg","message":"Not enough space"}]}}""")
        assertEquals(listOf(TaskRules.Failure("/A/x.jpg", "Not enough space")), TaskRules.failures(t))
    }

    @Test
    fun `checksums read as sha256sum prints them`() {
        val sums = TaskRules.checksums(checksumDone)
        assertEquals(1, sums.size)
        assertEquals("a948904f2f0f479b8f8197694b30184b0d2ed1c1cd2a1ec0fb85d299a192a447  /_q/hello.txt",
            TaskRules.checksumLines(sums))
    }

    @Test
    fun `a finished archive is saved under the name it was made with`() {
        assertEquals("Trip.zip", TaskRules.downloadName(task("""{"type":"archive","result":{"name":"Trip.zip"}}""")))
        // Never a path, whatever the server says.
        assertEquals("evil.zip", TaskRules.downloadName(task("""{"type":"archive","result":{"name":"../../evil.zip"}}""")))
        assertEquals("download.zip", TaskRules.downloadName(task("""{"type":"archive","result":[]}""")))
    }

    @Test
    fun `finishing is announced, but not a cancel`() {
        assertEquals("Done: SHA-256 of \"hello.txt\"", TaskRules.finishedMessage(checksumDone))
        assertEquals("Failed: Copy 3 items", TaskRules.finishedMessage(running().copy(status = TaskRules.FAILED)))
        assertNull(TaskRules.finishedMessage(running().copy(status = TaskRules.CANCELLED)))
    }

    @Test
    fun `times are shown in the phone's zone, with the day when it is not today`() {
        val utc = TimeZone.getTimeZone("UTC")
        val sameDay = TaskRules.parseTime("2026-09-30T20:00:00+00:00")!!
        val text = TaskRules.timesText(checksumDone, now = sameDay, zone = utc)
        assertTrue(text.startsWith("queued ") && text.contains("started ") && text.contains("finished "), text)
        assertFalse(text.contains("2026") || text.contains("26"), "no date for today: $text")
        val nextWeek = TaskRules.parseTime("2026-10-07T09:00:00+00:00")!!
        assertTrue(TaskRules.timesText(checksumDone, now = nextWeek, zone = utc).contains("26"), "the day for an older task")
        assertEquals("attempt 2", TaskRules.timesText(running().copy(attempts = 2)))
        assertNull(TaskRules.parseTime("yesterday"))
    }

    /* ---- when to look, and when to ask the server to run --------------------------- */

    @Test
    fun `looking stops when nothing is queued or running`() {
        assertNull(TaskRules.nextPollMs(TaskList(active = 0, runner = "inline"), failed = false, watching = false))
        assertEquals(TaskRules.POLL_MS, TaskRules.nextPollMs(TaskList(active = 2, runner = "inline"), failed = false, watching = true))
        // Nothing can move until an administrator starts a worker.
        assertEquals(TaskRules.IDLE_POLL_MS, TaskRules.nextPollMs(TaskList(active = 1, runner = "none"), failed = false, watching = true))
    }

    @Test
    fun `a failed look is tried again only while there is something to follow`() {
        assertEquals(TaskRules.RETRY_POLL_MS, TaskRules.nextPollMs(null, failed = true, watching = true))
        assertNull(TaskRules.nextPollMs(null, failed = true, watching = false))
    }

    @Test
    fun `the web server is asked to run the queue when tasks wait and nothing runs them`() {
        val waiting = BackgroundTask(id = "p", status = TaskRules.PENDING)
        assertTrue(TaskRules.shouldKick(TaskList(jobs = listOf(waiting), active = 1, runner = "inline")))
        // Already running: a second runner would only compete for the same queue.
        assertFalse(TaskRules.shouldKick(TaskList(jobs = listOf(waiting, running()), active = 2, runner = "inline")))
        // A worker process runs them without being asked.
        assertFalse(TaskRules.shouldKick(TaskList(jobs = listOf(waiting), active = 1, runner = "cli")))
        assertFalse(TaskRules.shouldKick(TaskList(jobs = listOf(waiting), active = 1, runner = "none")))
    }

    @Test
    fun `the Tasks screen says why tasks wait when nothing runs them`() {
        val waiting = TaskList(jobs = listOf(BackgroundTask(status = TaskRules.PENDING)), active = 1, runner = "none")
        assertTrue(TaskRules.note(waiting).contains("php tools/worker.php"))
        assertTrue(TaskRules.note(TaskList(runner = "inline")).contains("carry on if you close the app"))
    }

    /* ---- what the file actions offer --------------------------------------------- */

    private fun file(name: String) = FileEntry(name = name, path = "/A/$name", isDirectory = false)
    private fun folder(name: String) = FileEntry(name = name, path = "/A/$name", isDirectory = true)

    @Test
    fun `nothing is offered where the server has no queue`() {
        assertEquals(TaskRules.Offers(), TaskRules.offersFor(folder("Trip"), canWrite = true, available = false))
        assertFalse(TaskRules.offersForSelection(listOf(file("a.jpg")), canWrite = true, available = false).any)
    }

    @Test
    fun `a folder comes down as a ZIP, and an editor may compress it or make its thumbnails`() {
        val o = TaskRules.offersFor(folder("Trip"), canWrite = true, available = true)
        assertTrue(o.zipDownload && o.compress && o.thumbnails)
        assertFalse(o.extract || o.checksum)
    }

    @Test
    fun `a zip file can be extracted by an editor, and any file checksummed`() {
        val zip = TaskRules.offersFor(file("Trip.ZIP"), canWrite = true, available = true)
        assertTrue(zip.extract && zip.checksum && zip.compress)
        // A single file already has a plain Download.
        assertFalse(zip.zipDownload)
        assertFalse(TaskRules.offersFor(file("a.jpg"), canWrite = true, available = true).extract)
    }

    @Test
    fun `a viewer may only read`() {
        val o = TaskRules.offersFor(folder("Trip"), canWrite = false, available = true)
        assertTrue(o.zipDownload)
        assertFalse(o.compress || o.thumbnails || o.extract)
        assertTrue(TaskRules.offersFor(file("a.zip"), canWrite = false, available = true).checksum)
        assertFalse(TaskRules.offersFor(file("a.zip"), canWrite = false, available = true).extract)
    }

    @Test
    fun `a selection comes down as one ZIP, and is checksummed only when it is all files`() {
        val mixed = TaskRules.offersForSelection(listOf(file("a.jpg"), folder("B")), canWrite = true, available = true)
        assertTrue(mixed.zipDownload && mixed.compress)
        assertFalse(mixed.checksum)
        assertTrue(TaskRules.offersForSelection(listOf(file("a.jpg"), file("b.jpg")), canWrite = false, available = true).checksum)
        assertFalse(TaskRules.offersForSelection(emptyList(), canWrite = true, available = true).any)
    }

    @Test
    fun `a ZIP is named after its one item, or Archive`() {
        assertEquals("Trip.zip", TaskRules.archiveName(listOf("/Photos/Trip")))
        assertEquals("report.zip", TaskRules.archiveName(listOf("/Docs/report.pdf")))
        // As on the web: a name that is all extension leaves nothing to keep.
        assertEquals("Archive.zip", TaskRules.archiveName(listOf("/.bashrc")))
        assertEquals("Archive.zip", TaskRules.archiveName(listOf("/a", "/b")))
    }

    @Test
    fun `the types that change the store reload the folder`() {
        assertTrue("copy" in TaskRules.CHANGES_FILES && "extract" in TaskRules.CHANGES_FILES)
        assertFalse("checksum" in TaskRules.CHANGES_FILES)
        assertNotNull(TaskRules.CHANGES_FILES.firstOrNull())
    }
}
