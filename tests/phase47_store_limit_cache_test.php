<?php
declare(strict_types=1);

/**
 * The store limit is checked against a figure that keeps up with CloudHub's
 * own changes.
 *
 * STORAGE_LIMIT_GB is checked against storage_report(), which walks the whole
 * store once and caches the total for USAGE_CACHE_SECONDS. Until the cache
 * expired every upload was checked against that same total, so a batch of
 * files that each fitted on its own all went through and together ran past
 * the limit. storage_changed() applies each change to the cached figure.
 *
 * The function is lifted out of public/index.php, as phase44 does with
 * serve_file_range(), and run for real: against a cache file on disk, and
 * from twenty processes at once to prove no change is lost. The route wiring
 * is exercised over HTTP by tests/http/run.php.
 */
$root = dirname(__DIR__);
$checks = [];
$tmp = sys_get_temp_dir().'/cloudhub-p47-'.bin2hex(random_bytes(5));
mkdir($tmp.'/public', 0775, true);
mkdir($tmp.'/storage/.cache', 0775, true);

/** Lift one top-level function out of a PHP file, braces balanced. */
function lift47(string $source, string $name): string {
    $start = strpos($source, 'function '.$name.'(');
    if ($start === false) return '';
    $open = strpos($source, '{', $start);
    if ($open === false) return '';
    $depth = 0;
    for ($i = $open, $n = strlen($source); $i < $n; $i++) {
        if ($source[$i] === '{') $depth++;
        elseif ($source[$i] === '}') { $depth--; if ($depth === 0) return substr($source, $start, $i-$start+1); }
    }
    return '';
}

$index = (string)file_get_contents($root.'/public/index.php');
$fn = lift47($index, 'storage_changed');
$checks['storage_changed() could be lifted from index.php'] = $fn !== '';
// Under public/, so the function's dirname(__DIR__) is $tmp and its cache is
// $tmp/storage/.cache/usage.json -- the same layout as the application.
file_put_contents($tmp.'/public/lifted.php', "<?php\ndeclare(strict_types=1);\n".$fn."\n");
require $tmp.'/public/lifted.php';

$cache = $tmp.'/storage/.cache/usage.json';
$write = static function (array $report, int $age) use ($cache): void {
    file_put_contents($cache, json_encode($report));
    touch($cache, time() - $age);
    clearstatcache(true, $cache);
};
$read = static function () use ($cache): array {
    clearstatcache(true, $cache);
    return (array)json_decode((string)file_get_contents($cache), true);
};

// --- nothing cached: nothing to correct, and nothing invented ---------------
storage_changed(500);
$checks['with no cached figure nothing is written'] = !is_file($cache);

// --- a change lands in the figure the next check reads ----------------------
$write(['bytes' => 1000, 'files' => 3, 'measuredAt' => '2026-01-01T00:00:00+00:00'], 100);
$mtime = filemtime($cache);
storage_changed(400);
$checks['an upload is added to the cached figure'] = ($read()['bytes'] ?? null) === 1400;
$checks['the rest of the report is left as measured'] = ($read()['files'] ?? null) === 3
    && ($read()['measuredAt'] ?? null) === '2026-01-01T00:00:00+00:00';
clearstatcache(true, $cache);
// The timestamp is the measurement's age; refreshing it on every change
// would keep a busy server from ever measuring again.
$checks['the measurement keeps its age'] = filemtime($cache) === $mtime;

storage_changed(-900);
$checks['a delete is taken off it'] = ($read()['bytes'] ?? null) === 500;
storage_changed(-10_000);
$checks['it never goes below nothing'] = ($read()['bytes'] ?? null) === 0;

// --- a cache that is not a report is left for the next measurement ----------
file_put_contents($cache, 'not json');
storage_changed(100);
$checks['an unreadable cache is left alone'] = file_get_contents($cache) === 'not json';

// --- twenty processes at once, and not one change lost ----------------------
$write(['bytes' => 0], 10);
$worker = $tmp.'/public/worker.php';
file_put_contents($worker, "<?php\ndeclare(strict_types=1);\nrequire __DIR__.'/lifted.php';\n"
    ."for (\$i = 0; \$i < 50; \$i++) storage_changed(1);\n");
$procs = [];
for ($p = 0; $p < 20; $p++) {
    $procs[] = proc_open([PHP_BINARY, $worker], [1 => ['file', '/dev/null', 'w'], 2 => ['file', '/dev/null', 'w']], $pipes);
}
foreach ($procs as $proc) proc_close($proc);
$checks['concurrent changes are all counted'] = ($read()['bytes'] ?? null) === 1000;

// --- the routes that change the live tree report it -------------------------
$checks['a finished upload is counted'] =
    str_contains($index, "storage_changed((is_file(\$full)?(int)(filesize(\$full)?:0):0) - (int)\$done['replacedBytes']);");
$checks['each multipart file is counted before the next is checked'] = str_contains($index, 'storage_changed($size - $replaced);');
$checks['a copy is counted'] = str_contains($index, 'storage_changed($copiedBytes);');
$checks['a WebDAV PUT is counted'] = str_contains($index, 'storage_changed($bytes - $replaced);');
$checks['a trash restore is counted'] = str_contains($index, "storage_changed(\$restored['bytes']);");
$checks['deletes give the room back'] = str_contains($index, "storage_changed(-(int)(\$meta['bytes'] ?? 0));")
    && str_contains($index, 'storage_changed(-$gone);') && str_contains($index, 'storage_changed(-$bytes);');
$checks['the measurement itself is written under the same lock'] =
    str_contains($index, "@file_put_contents(\$cache, json_encode(\$report, JSON_UNESCAPED_SLASHES), LOCK_EX);");

$rmrf = static function (string $p) use (&$rmrf): void {
    if (is_link($p) || is_file($p)) { @unlink($p); return; }
    if (is_dir($p)) { foreach (scandir($p) ?: [] as $n) if ($n !== '.' && $n !== '..') $rmrf($p.'/'.$n); @rmdir($p); }
};
$rmrf($tmp);

$bad = false;
foreach ($checks as $name => $ok) { echo ($ok ? '[PASS] ' : '[FAIL] ').$name.PHP_EOL; $bad = $bad || !$ok; }
exit($bad ? 1 : 0);
