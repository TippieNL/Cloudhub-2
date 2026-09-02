<?php
declare(strict_types=1);

/**
 * Video playback: what it costs to watch something, and to watch it twice.
 *
 * The behaviour is exercised for real elsewhere -- the validators in
 * tests/http/run.php over HTTP, the buffering and tile rules in
 * PlaybackTest.kt on the JVM. These are the pins over the source for the parts
 * neither can reach: that the player reads through a cache, that a tile no
 * longer asks for a whole video, and that the frame it decodes is handed back.
 */
$root = dirname(__DIR__);
$kotlin = $root.'/android/app/src/main/java/nl/tippie/cloudhub';
$readKt = fn(string $path): string => (string)@file_get_contents($kotlin.'/'.$path);
$read = fn(string $path): string => (string)@file_get_contents($root.'/'.$path);

$index = $read('public/index.php');
$player = $readKt('ui/PlayerScreen.kt');
$tuning = $readKt('ui/PlaybackTuning.kt');
$cache = $readKt('data/MediaCache.kt');
$cards = $readKt('ui/FileCards.kt');
$thumbs = $readKt('ui/VideoThumbnails.kt');
$settings = $readKt('ui/SettingsScreen.kt');
$main = $readKt('MainActivity.kt');
$gradle = $read('android/app/build.gradle');
$tests = $read('android/app/src/test/java/nl/tippie/cloudhub/PlaybackTest.kt');
$http = $read('tests/http/run.php');

$checks = [];

/* --- the server: asking instead of fetching -------------------------------
 *
 * Without validators, Cache-Control is all a client has: when the lifetime
 * lapses the whole video comes down again to prove it has not changed.
 */
$checks['media responses carry validators'] =
    str_contains($index, "\$etag = sprintf('\"%x-%x\"', \$modified, \$size);")
    && str_contains($index, "header('ETag: '.\$etag);")
    && str_contains($index, "header('Last-Modified: '.\$lastModified);");
$checks['an unchanged file answers 304 with no body'] =
    str_contains($index, 'http_response_code(304);')
    && str_contains($index, "\$noneMatch = trim((string)(\$_SERVER['HTTP_IF_NONE_MATCH']??''));")
    && str_contains($index, "\$since = trim((string)(\$_SERVER['HTTP_IF_MODIFIED_SINCE']??''));");
// A 304 to a request that asked for part of a file strands the player: it
// wanted bytes, and freshness is not an answer to that.
$checks['a range request is never answered with 304'] =
    str_contains($index, "if (\$range === '') {\n        \$noneMatch");
// A file replaced between two range requests would otherwise be stitched
// together from two different videos, which no player can detect.
$checks['a resumed range is checked against the file it started on'] =
    str_contains($index, "\$ifRange = trim((string)(\$_SERVER['HTTP_IF_RANGE']??''));")
    && str_contains($index, "\$ifRange !== \$etag && \$ifRange !== \$lastModified");
$checks['both are covered over real HTTP'] =
    str_contains($http, 'a cached video is revalidated rather than fetched again')
    && str_contains($http, 'resuming a range only continues the same file');

/* --- one request is not a whole film ---------------------------------------
 *
 * A player asks for "bytes=0-" and holds one request open for the length of
 * the film. PHP's built-in server -- `php -S`, which many installations run --
 * serves one request at a time, so that single request blocks everything else:
 * measured, an ordinary file listing never answered at all while a video
 * streamed. Answering a shorter range is what HTTP allows and what media
 * clients already handle.
 */
$checks['a range request serves at most one chunk'] =
    str_contains($index, 'const MEDIA_RANGE_CHUNK_BYTES = 8 * 1024 * 1024;')
    && str_contains($index, 'if ($end-$start+1 > MEDIA_RANGE_CHUNK_BYTES) {');
// A 200 promises the whole file. Cutting that short would truncate every
// download of a large file -- a far worse bug than the one being fixed.
$checks['a download with no range is never shortened'] =
    (bool)preg_match('/\$status = 206;\s*\n\s*\n\s*\/\*.*?\*\/\s*\n\s*if \(\$end-\$start\+1 > MEDIA_RANGE_CHUNK_BYTES\)/s', $index)
    && str_contains($http, 'a fetch with no range is still the whole file');
$checks['the chunking is exercised over real HTTP'] =
    str_contains($http, 'one request does not carry a whole film')
    && str_contains($http, 'the rest follows from where it stopped');
// PHP's integer is signed: on a 32-bit build a file over 2 GB comes back
// negative, and the result is a nonsense Content-Length and a video that
// never starts with nothing saying why.
$checks['a 32-bit server says so instead of serving nonsense'] =
    str_contains($index, 'if ($size < 0) {')
    && str_contains($index, '32-bit build of PHP');

/* --- the player: bytes fetched once ---------------------------------------- */

$checks['playback reads through a disk cache'] =
    str_contains($player, 'CacheDataSource.Factory()')
    && str_contains($player, 'setCache(MediaCache.get(context))')
    && str_contains($player, 'setUpstreamDataSourceFactory(OkHttpDataSource.Factory(client.okHttp))');
// A cache that cannot be written must cost a cache, never the video.
$checks['a broken cache does not break playback'] =
    str_contains($player, 'CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR');
// Keyed on the URL alone, replacing a file with a different video of the same
// name would play the old one out of the cache for good.
$checks['the cache key follows the file, not just its name'] =
    str_contains($player, 'setCustomCacheKey(PlaybackTuning.cacheKey(entry.path, entry.modified))')
    && str_contains($tests, "a replaced file is not played from the old file's cache");
// A film bigger than the cache cannot be held by it: playing it evicts the
// spans it just wrote, and the evictor can drop one while it is being read.
$checks['a file too big for the cache is not written to it'] =
    str_contains($tuning, 'fun mayCache(sizeBytes: Long): Boolean = sizeBytes in 1..(CACHE_BYTES / 4)')
    && str_contains($player, 'if (!PlaybackTuning.mayCache(entry.size)) setCacheWriteDataSinkFactory(null)')
    && str_contains($tests, 'a film bigger than the cache is not written to it');
$checks['one cache object for the process'] =
    str_contains($cache, 'object MediaCache')
    && str_contains($cache, '@Synchronized')
    && str_contains($cache, 'LeastRecentlyUsedCacheEvictor(PlaybackTuning.CACHE_BYTES)');
// Emptying the directory behind the index leaves it describing videos that
// are no longer there.
$checks['clearing goes through the cache, not the filesystem'] =
    str_contains($cache, 'live.removeResource(key)');
$checks['the index it needs is declared'] =
    str_contains($gradle, "androidx.media3:media3-database:1.4.1")
    && str_contains($cache, 'StandaloneDatabaseProvider');

/* --- the player: starting sooner ------------------------------------------- */

$checks['the buffer policy is applied, not left at the default'] =
    str_contains($player, 'DefaultLoadControl.Builder()')
    && str_contains($player, 'PlaybackTuning.BUFFER_FOR_PLAYBACK_MS')
    && str_contains($player, 'setBackBuffer(PlaybackTuning.BACK_BUFFER_MS');
$checks['the numbers live apart from the player, with tests'] =
    str_contains($tuning, 'object PlaybackTuning')
    && str_contains($tests, 'the buffer bounds are ones Media3 will accept');

/* --- the tile: no longer the whole video -----------------------------------
 *
 * A frame can only be decoded from bytes that arrived, so handing the tile the
 * video's URL meant fetching the video -- gigabytes to draw ten stamps, over
 * the connection playback wants.
 */
$checks['a tile asks for a prefix rather than the file'] =
    str_contains($thumbs, 'object VideoThumbnails')
    && str_contains($cards, 'VideoThumbnails.rangeHeader(entry.size)')
    && str_contains($cards, 'setHeader("Range", range)');
// A prefix filed under the whole file's key would hand the next reader a
// truncated video with no way to know it.
$checks['a partial fetch is never cached as the file'] =
    str_contains($cards, 'diskCachePolicy(CachePolicy.DISABLED)');
$checks['a file too big to fetch whole gets the icon instead'] =
    str_contains($cards, 'VideoThumbnails.mayFetchWholeFile(entry.size)')
    && str_contains($tests, 'a huge file that will not decode from a prefix gets the icon');
// The API method existed and nothing called it: every device decoded its own
// frame, every time, forever.
$checks['the decoded frame is handed back to the server'] =
    str_contains($cards, 'api.contributeVideoThumbnail(entry.path, encoded)')
    && str_contains($cards, 'VideoThumbnails.markContributed(entry.path)');
$checks['a thumbnail that could not be stored is not an error'] =
    str_contains($cards, 'runCatching {');
$checks['the frame is scaled to something the server accepts'] =
    str_contains($cards, 'VideoThumbnails.scaledSize(frame.width, frame.height)')
    && str_contains($thumbs, 'const val MAX_EDGE_PX = 640');

/* --- what the cache costs, shown ------------------------------------------- */

// Spelling updated with the settings redesign: the size and the clear action
// became one row that shows what it holds and empties on a tap. What is pinned
// is that both are still there.
$checks['the video cache can be seen and emptied'] =
    str_contains($settings, 'title = "Cached video"')
    && str_contains($settings, 'onClearVideoCache()')
    && str_contains($settings, 'humanBytes(video)')
    && str_contains($main, 'onClearVideoCache = { MediaCache.clear(this@MainActivity) }');

$bad = false;
foreach ($checks as $name => $ok) {
    echo ($ok ? '[PASS] ' : '[FAIL] ').$name.PHP_EOL;
    $bad = $bad || !$ok;
}
exit($bad ? 1 : 0);
