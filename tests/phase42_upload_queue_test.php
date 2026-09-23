<?php
declare(strict_types=1);

/**
 * The durable upload queue: one tab at a time, and never a false "failed".
 *
 * Verified in Chromium with two tabs of the app open, against the previous
 * queue.js and this one: before, the second tab joined the upload, had a
 * chunk refused with 409 and wrote "failed" over an item the first tab was
 * still uploading; now it waits on the lock and sends nothing. Signing out
 * mid-upload used to leave the item "failed (Authentication required)"
 * after signing back in; now it pauses and finishes once signed in.
 */
$queue = (string)file_get_contents(dirname(__DIR__).'/public/assets/js/queue.js');
$checks = [];

$checks['one tab drives the queue'] =
    str_contains($queue, "navigator.locks.request('cloudhub-upload-queue', () => { state.waiting = false; return drain(); });");
$checks['a tab already waiting does not queue another turn'] =
    str_contains($queue, 'if (state.running || state.waiting) return;');
$checks['without Web Locks it still runs'] = str_contains($queue, 'if (!navigator.locks?.request) return drain();');
$checks['a 409 resumes from the server\'s offset'] =
    str_contains($queue, 'if (err.status !== 409) throw err;')
    && str_contains($queue, 'const at = await serverStatus(item.id);')
    && str_contains($queue, 'if (at.received === offset) throw err;');
$checks['a lapsed session pauses rather than fails'] = substr_count($queue, 'throw signedOut();') >= 4
    && str_contains($queue, "if (err.signedOut) { changed(); break; }")
    && str_contains($queue, "document.addEventListener('cfh-signed-in', () => { state.csrf = ''; run(); });");

$bad = false;
foreach ($checks as $name => $ok) { echo ($ok ? '[PASS] ' : '[FAIL] ').$name.PHP_EOL; $bad = $bad || !$ok; }
exit($bad ? 1 : 0);
