<?php
declare(strict_types=1);

/**
 * The upload progress tracker, and the silent drop it exposed.
 *
 * Regression pins over the source. The arithmetic itself is covered by
 * UploadProgressTest, which needs neither a server nor a device -- the trap it
 * guards (a fraction computed over a queue that shrinks as files finish) is
 * only visible on a real multi-file upload over a slow connection, which is
 * not something to go looking for by hand.
 */
$root = dirname(__DIR__);
$kotlin = $root.'/android/app/src/main/java/nl/tippie/cloudhub';
$readKt = fn(string $path): string => (string)@file_get_contents($kotlin.'/'.$path);
$read = fn(string $path): string => (string)@file_get_contents($root.'/'.$path);

$uploads = $readKt('work/Uploads.kt');
$progress = $readKt('ui/UploadProgress.kt');
$tracker = $readKt('ui/UploadTracker.kt');
$files = $readKt('ui/FilesScreen.kt');
$main = $readKt('MainActivity.kt');
$stores = $readKt('data/Stores.kt');
$manifest = $read('android/app/src/main/AndroidManifest.xml');
$tests = $read('android/app/src/test/java/nl/tippie/cloudhub/UploadProgressTest.kt');

$checks = [];

/* --- the arithmetic ------------------------------------------------------------
 *
 * The queue shrinks as files finish, so a fraction over "what is left" lurches
 * backwards every time something completes.
 */
$checks['the progress rule is a pure function'] =
    str_contains($progress, 'object UploadProgress')
    && str_contains($progress, 'fun summarise(')
    && str_contains($tests, 'class UploadProgressTest');
$checks['the denominator does not shrink as files finish'] =
    str_contains($progress, 'fun batchTotal(previousTotal: Long, remainingBytes: Long): Long =')
    && str_contains($progress, 'maxOf(previousTotal, remainingBytes)')
    && str_contains($tests, 'the fraction does not go backwards as files finish');
// A 10 KB note finishing beside a 4 GB video is not half the work.
$checks['progress is measured in bytes, not files'] =
    str_contains($progress, 'val remainingBytes = remaining.sumOf { it.size.coerceAtLeast(0) }')
    && str_contains($tests, 'progress is measured in bytes, not in files');
// /api/uploads/init reports what the server already holds, which can exceed
// what this file needs if an earlier run left bytes behind.
$checks['a stale resumed offset is clamped'] =
    str_contains($progress, 'currentSent.coerceIn(0L, current.size)');
$checks['an empty batch does not divide by zero'] =
    str_contains($progress, 'if (total <= 0) 0f else');
// Opening the app with nothing queued must not flash "all done".
$checks['a queue that never had anything says nothing'] =
    str_contains($progress, 'val finished get() = !active && totalBytes > 0');

/* --- the tracker ---------------------------------------------------------------- */

// The worker has published id/sent/total on every chunk since uploads were
// built; nothing read it.
$checks['the progress the worker already published is now read'] =
    str_contains($uploads, 'setProgress(workDataOf("id" to item.id')
    && str_contains($tracker, 'getWorkInfosForUniqueWorkFlow(UploadWorker.WORK)')
    && str_contains($tracker, 'progress?.getString("id")');
// The queue file is not observable on its own; the progress flow is the signal.
$checks['the queue is re-read on every tick, not only on state changes'] =
    str_contains($tracker, 'LaunchedEffect(infos, infos?.progress)');
$checks['the bar is docked rather than floating over the files'] =
    str_contains($files, 'bottomBar = {') && str_contains($files, 'UploadTracker(');
$checks['every queued file is listed, with the one in flight marked'] =
    str_contains($tracker, 'val inFlight = item.id == state.currentId')
    && str_contains($tracker, 'Waiting · ');
// A row of empty bars for files that have not started reads as stalled.
$checks['only the file actually moving gets its own bar'] =
    str_contains($tracker, 'if (inFlight) {') && str_contains($tracker, 'ProgressBar(');
$checks['finishing is shown, then gets out of the way'] =
    str_contains($tracker, 'FINISHED_LINGER_MS') && str_contains($tracker, 'All uploads finished');
$checks['the tracker respects Reduce Motion'] =
    str_contains($tracker, 'LocalReduceMotion.current');

/* --- the silent drop ---------------------------------------------------------------
 *
 * An upload the server refuses for good was removed from the queue and its
 * staged bytes deleted, with nobody told: the file you were shown as "queued"
 * simply never arrived, and nothing anywhere said why.
 */
$checks['a refused upload is recorded rather than vanishing'] =
    str_contains($uploads, 'data class UploadFailure(')
    && str_contains($uploads, 'queue.recordFailure(')
    && str_contains($uploads, 'UploadFailure(item.name, e.message');
$checks['the reason is the server\'s own words'] =
    str_contains($uploads, "e.message ?: \"The server refused this file\"");
$checks['refusals are shown and can be dismissed'] =
    str_contains($tracker, 'state.failures') && str_contains($tracker, 'Text("Dismiss")')
    && str_contains($main, 'onDismissUploadFailures = { queue.clearFailures() }');
// A queue stuck against a full quota would otherwise write a record per attempt.
$checks['the failure record is bounded'] =
    str_contains($uploads, 'takeLast(MAX_FAILURES)');

/* --- the notification, and what it costs ---------------------------------------------- */

$checks['progress is posted as a notification'] =
    str_contains($uploads, 'NotificationCompat.Builder(applicationContext, CHANNEL)')
    && str_contains($uploads, 'setProgress(100, percent, false)');
// On API 26+ a notification to a channel that does not exist is dropped
// silently, which is a maddening thing to debug.
$checks['the channel exists before anything is posted'] =
    str_contains($uploads, 'fun ensureChannel(context: Context)')
    && str_contains($uploads, 'ensureChannel(applicationContext)');
$checks['an ongoing notification is cleared when uploading stops'] =
    substr_count($uploads, 'clearNotification()') >= 3;
// An upload must not fail because nobody wanted to be told about it.
$checks['a refused permission costs only the notification'] =
    str_contains($uploads, 'if (!manager.areNotificationsEnabled()) return')
    && str_contains($uploads, 'runCatching { manager.notify(');
/*
 * setForeground() would additionally need FOREGROUND_SERVICE_DATA_SYNC and a
 * service type on targetSdk 34, to buy expedited scheduling this app does not
 * need. One permission, not two.
 */
$declarations = (string)preg_replace('/<!--.*?-->/s', '', $manifest);
// Stripped, or the comment explaining why setForeground() is avoided trips the
// check that it is not used.
$uploadCode = (string)preg_replace('#/\*.*?\*/|//[^\n]*#s', '', $uploads);
$checks['exactly one permission was added, and it is the notification one'] =
    str_contains($declarations, 'android.permission.POST_NOTIFICATIONS')
    && substr_count($declarations, '<uses-permission') === 3;
$checks['no foreground service was taken on'] =
    !str_contains($declarations, 'FOREGROUND_SERVICE_DATA_SYNC')
    && !str_contains($uploadCode, 'setForeground(');
$checks['the permission is asked for in context, not at launch'] =
    str_contains($main, 'private fun requestNotificationsOnce()')
    && str_contains($main, 'requestNotificationsOnce()')
    && !str_contains($main, 'requestNotificationsOnce()\n        setContent');
// Android stops showing the dialog after two refusals; re-prompting on every
// upload would be worse than not asking.
$checks['the prompt is not repeated on every upload'] =
    str_contains($stores, 'var notificationsAsked: Boolean')
    && str_contains($main, 'if (app.settings.notificationsAsked) return');
// The other permissions this app has never wanted.
foreach (['CAMERA', 'RECORD_AUDIO', 'READ_MEDIA_IMAGES', 'READ_MEDIA_VIDEO', 'EXTERNAL_STORAGE'] as $absent) {
    $checks["still no $absent permission"] = !str_contains($declarations, $absent);
}

$bad = false;
foreach ($checks as $name => $ok) {
    echo ($ok ? '[PASS] ' : '[FAIL] ').$name.PHP_EOL;
    $bad = $bad || !$ok;
}
exit($bad ? 1 : 0);
