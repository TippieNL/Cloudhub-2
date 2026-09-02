<?php
declare(strict_types=1);

/**
 * An upload must not take the whole connection.
 *
 * Uploading a large video made the app unusable: a video would not start and
 * photos took the better part of a second each. Measured on an 8 Mbit link,
 * 2 MB of video took 2.1s with the line free, 7.7-10.1s with an upload running
 * flat out beside it, and 2.2-2.5s with the same upload paced the way this
 * round paces it. The same measurement with four server workers instead of one
 * was no better, which is what says the fix belongs in the client: the bytes
 * are simply not there to be had.
 *
 * The arithmetic is exercised on the JVM in UploadPacingTest and the protocol
 * it depends on over real HTTP in tests/http/run.php. These are the pins over
 * the wiring neither can reach.
 */
$root = dirname(__DIR__);
$kotlin = $root.'/android/app/src/main/java/nl/tippie/cloudhub';
$readKt = fn(string $path): string => (string)@file_get_contents($kotlin.'/'.$path);
$read = fn(string $path): string => (string)@file_get_contents($root.'/'.$path);

$pacing = $readKt('work/UploadPacing.kt');
$worker = $readKt('work/Uploads.kt');
$player = $readKt('ui/PlayerScreen.kt');
$viewer = $readKt('ui/ViewerScreens.kt');
$tests = $read('android/app/src/test/java/nl/tippie/cloudhub/UploadPacingTest.kt');
$index = $read('public/index.php');
$http = $read('tests/http/run.php');

$checks = [];

/* --- the rules ------------------------------------------------------------- */

$checks['there is a signal for what is on screen'] =
    str_contains($pacing, 'object ForegroundMedia')
    && str_contains($pacing, 'val inUse: Boolean get() = open.get() > 0');
// A flag would be wrong: the player can be opened over the viewer, and the
// second screen closing must not say the coast is clear.
$checks['two open screens are counted, not flagged'] =
    str_contains($pacing, 'AtomicInteger(0)')
    && str_contains($pacing, 'fun leave() { open.updateAndGet { if (it > 0) it - 1 else 0 } }')
    && str_contains($tests, 'the viewer and the player can be open at once');
$checks['the pacing is a slice and a pause'] =
    str_contains($pacing, 'object UploadPacing')
    && str_contains($pacing, 'fun chunkBytes(serverChunkBytes: Long, mediaOnScreen: Boolean): Long')
    && str_contains($pacing, 'fun pauseMillis(mediaOnScreen: Boolean): Long');
// A chunk over the server's limit is refused with 413, which the worker treats
// as permanent -- the file would be dropped, not retried.
$checks['a chunk never exceeds what the server takes'] =
    str_contains($pacing, 'return minOf(wanted, allowed)')
    && str_contains($tests, 'a chunk is never larger than the server said it would take');
// Pacing can only start at a chunk boundary, so the idle chunk is also how
// long opening a video waits for the upload to notice.
$checks['the idle chunk is small enough to react quickly'] =
    str_contains($pacing, 'const val IDLE_CHUNK_BYTES = 2L * 1024 * 1024')
    && str_contains($tests, 'opening a video is noticed within one chunk');

/* --- the worker ------------------------------------------------------------ */

// Asked per chunk rather than once: the point is to notice a video opened
// part-way through, and to speed back up when it closes.
$checks['the worker asks again for every chunk'] =
    str_contains($worker, 'val watching = ForegroundMedia.inUse')
    && str_contains($worker, 'val slice = UploadPacing.chunkBytes(status.chunkBytes, watching)')
    && str_contains($worker, 'val end = minOf(offset + slice, item.size)');
$checks['the worker actually waits'] =
    str_contains($worker, 'val pause = UploadPacing.pauseMillis(watching)')
    && str_contains($worker, 'if (pause > 0) delay(pause)');

/* --- the screens that ask for the link ------------------------------------- */

$checks['the player says it is watching'] =
    (bool)preg_match('/DisposableEffect\(Unit\) \{\s*\n\s*ForegroundMedia\.enter\(\)\s*\n\s*onDispose \{ ForegroundMedia\.leave\(\) \}/', $player);
$checks['the photo viewer says so too'] =
    (bool)preg_match('/DisposableEffect\(Unit\) \{\s*\n\s*ForegroundMedia\.enter\(\)\s*\n\s*onDispose \{ ForegroundMedia\.leave\(\) \}/', $viewer);

/* --- the server ------------------------------------------------------------
 *
 * PHP's files session handler holds an exclusive lock for the whole request.
 * On a SAPI that hands the body to PHP as it arrives, a chunk PUT holds it for
 * the length of the upload and blocks every other request from that account --
 * the same account whose file list and video are being asked for.
 */
$checks['a chunk upload does not hold the session lock'] =
    (bool)preg_match('/uploads\/chunk.*?\n(.*?\n)*?\s*release_session_lock\(\);\s*\n\s*\$id = /', $index);
$checks['neither does asking where an upload is up to'] =
    (bool)preg_match('/uploads\/status.*?\n\s*release_session_lock\(\);/', $index);
$checks['sending less than the chunk size is covered over HTTP'] =
    str_contains($http, 'a chunk smaller than the server allows is accepted')
    && str_contains($http, 'the offset follows what was actually sent');

$bad = false;
foreach ($checks as $name => $ok) {
    echo ($ok ? '[PASS] ' : '[FAIL] ').$name.PHP_EOL;
    $bad = $bad || !$ok;
}
exit($bad ? 1 : 0);
