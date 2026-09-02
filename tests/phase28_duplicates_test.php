<?php
declare(strict_types=1);

/**
 * The duplicate finder, run for real.
 *
 * Not pins over the source: this builds a store on disk with known copies in
 * it, runs the finder over it, and checks what comes back. The interesting
 * failures are all here -- two files that merely share a size, a file that
 * differs only in its last byte, a copy in another folder under another name --
 * and none of them can be asserted by reading the code.
 */
require dirname(__DIR__).'/src/Services/FileService.php';
require dirname(__DIR__).'/src/Services/DuplicateFinder.php';

use CloudHub\Services\DuplicateFinder;
use CloudHub\Services\FileService;

$checks = [];

$root = sys_get_temp_dir().'/cloudhub-dupes-'.bin2hex(random_bytes(4));
$cache = $root.'-cache.json';
mkdir($root.'/Photos/2026', 0775, true);
mkdir($root.'/Backup', 0775, true);
mkdir($root.'/.trash/x/payload', 0775, true);

$put = function (string $rel, string $bytes, ?int $modified = null) use ($root): string {
    $full = $root.'/'.$rel;
    if (!is_dir(dirname($full))) mkdir(dirname($full), 0775, true);
    file_put_contents($full, $bytes);
    if ($modified !== null) touch($full, $modified);
    return $full;
};

$photo = str_repeat('A', 4096).'the original bytes'.str_repeat('B', 4096);
$other = str_repeat('A', 4096).'a different photo!'.str_repeat('B', 4096);

// The plain case: the same photo in two folders, under two names.
$put('Photos/holiday.jpg', $photo, 1_700_000_000);
$put('Backup/holiday (1).jpg', $photo, 1_800_000_000);
// ...and a third copy, deeper.
$put('Photos/2026/holiday-copy.jpg', $photo, 1_900_000_000);

// Same size, different content: the trap a size-only finder falls into.
$put('Photos/beach.jpg', $other, 1_700_000_100);

// A video duplicated as well, to prove the finder is not photo-only.
$clip = str_repeat("\x00\x01\x02\x03", 20000);
$put('Photos/clip.mp4', $clip, 1_700_000_200);
$put('Backup/clip.mp4', $clip, 1_700_000_300);

// Files whose ends match but whose middles do not: the sample pass says
// "maybe", and only the full hash can say no.
$head = str_repeat('H', DuplicateFinder::SAMPLE_BYTES);
$tail = str_repeat('T', DuplicateFinder::SAMPLE_BYTES);
$put('Photos/twin-a.png', $head.'MIDDLE ONE'.$tail, 1_700_000_400);
$put('Photos/twin-b.png', $head.'MIDDLE TWO'.$tail, 1_700_000_500);

// Empty files are identical to each other, which is true and useless.
$put('Photos/empty-1.jpg', '', 1_700_000_600);
$put('Photos/empty-2.jpg', '', 1_700_000_700);

// A document, so the media scope can be seen to skip it, in two copies.
$notes = str_repeat('note', 500);
$put('Photos/notes.txt', $notes, 1_700_000_800);
$put('Backup/notes.txt', $notes, 1_700_000_900);

// A copy inside the trash: reachable on disk, invisible to CloudHub, and it
// must stay invisible here or the report tells you to delete what you already
// deleted.
file_put_contents($root.'/.trash/x/payload/holiday.jpg', $photo);

$fs = new FileService(['root_dir' => $root, 'max_upload_bytes' => 1 << 30]);
$finder = new DuplicateFinder($fs, $cache);
$report = $finder->scan();

$groups = $report['groups'];
$pathsOf = function (array $group): array {
    $paths = array_column($group['files'], 'path');
    sort($paths);
    return $paths;
};
$groupWith = function (string $path) use ($groups, $pathsOf): ?array {
    foreach ($groups as $group) if (in_array($path, $pathsOf($group), true)) return $group;
    return null;
};

/* --- what it found --------------------------------------------------------- */

$photos = $groupWith('/Photos/holiday.jpg');
$checks['the same photo in three places is one group'] =
    $photos !== null && $photos['copies'] === 3
    && $pathsOf($photos) === ['/Backup/holiday (1).jpg', '/Photos/2026/holiday-copy.jpg', '/Photos/holiday.jpg'];
$checks['a duplicated video is found too'] =
    ($clipGroup = $groupWith('/Photos/clip.mp4')) !== null && $clipGroup['copies'] === 2;

/* --- what it did not ------------------------------------------------------- */

// The whole point of hashing rather than trusting the size.
$checks['two files of the same size are not duplicates'] = $groupWith('/Photos/beach.jpg') === null;
// The sample pass cannot tell these apart; only the full hash can.
$checks['files that match at both ends but differ inside are not duplicates'] =
    $groupWith('/Photos/twin-a.png') === null;
$checks['empty files are not reported as copies of each other'] =
    $groupWith('/Photos/empty-1.jpg') === null;
// Deleted files are gone as far as anyone using CloudHub is concerned.
$checks['nothing in the trash is reported'] =
    !str_contains(json_encode($groups), '.trash');
// Photos and videos, unless asked otherwise.
$checks['a duplicated document is skipped by a media scan'] = $groupWith('/Photos/notes.txt') === null;

$all = (new DuplicateFinder($fs, $cache))->scan(DuplicateFinder::EVERYTHING);
$documents = null;
foreach ($all['groups'] as $group) {
    if (in_array('/Photos/notes.txt', array_column($group['files'], 'path'), true)) $documents = $group;
}
$checks['...and found when the scan covers everything'] = $documents !== null && $documents['copies'] === 2;

/* --- what it says ---------------------------------------------------------- */

$checks['the space to be won back is what deleting the extras gives'] =
    $photos !== null && $photos['wastedBytes'] === $photos['bytes'] * 2;
$checks['the total is the sum of the groups'] =
    $report['wastedBytes'] === array_sum(array_column($groups, 'wastedBytes'));
// Biggest saving first: that is the order anyone works through them in.
$wasted = array_column($groups, 'wastedBytes');
$descending = $wasted;
rsort($descending);
$checks['groups are ordered by what they cost'] = $wasted === $descending;
$checks['the scan says how much it looked at'] =
    $report['filesScanned'] >= 9 && $report['candidates'] >= 5 && $report['complete'] === true;

/* --- which copy to keep -----------------------------------------------------
 *
 * Nothing is ever deleted for you, but something has to be suggested, and a
 * suggestion that moved between scans would be impossible to trust.
 */
$checks['the oldest copy is the one suggested to keep'] =
    $photos !== null && $photos['keep'] === '/Photos/holiday.jpg';
$checks['the suggestion is one of the copies'] =
    $photos !== null && in_array($photos['keep'], $pathsOf($photos), true);
$sameAge = [
    ['path' => '/a/b/c/deep.jpg', 'modified' => 100],
    ['path' => '/shallow.jpg', 'modified' => 100],
];
$checks['files of the same age keep the one nearest the top'] =
    DuplicateFinder::suggestedKeeper($sameAge) === '/shallow.jpg';
$checks['and otherwise the same answer every time'] =
    DuplicateFinder::suggestedKeeper([
        ['path' => '/b.jpg', 'modified' => 5], ['path' => '/a.jpg', 'modified' => 5],
    ]) === '/a.jpg';

/* --- the second scan --------------------------------------------------------
 *
 * Hashes are cached against size and modification time, so a scan of an
 * unchanged store re-reads nothing.
 */
$checks['a hash cache is written'] = is_file($cache);
$checks['the first scan had to read files'] = $report['hashedFiles'] > 0;
$again = (new DuplicateFinder($fs, $cache))->scan();
$checks['a second scan finds the same duplicates'] =
    $again['groupCount'] === $report['groupCount'] && $again['wastedBytes'] === $report['wastedBytes'];
// The point of the cache: nothing is read twice.
$checks['and reads nothing from an unchanged store'] = $again['hashedFiles'] === 0;
/*
 * The first version of the cache kept only what the scan it just ran had
 * looked at, so a photos-and-videos scan threw away the hashes an all-files
 * scan had paid for, and alternating the two re-read the store every time.
 */
$documentsAgain = (new DuplicateFinder($fs, $cache))->scan(DuplicateFinder::EVERYTHING);
$checks['a scan of another scope does not throw away what it cached'] =
    $documentsAgain['hashedFiles'] === 0;

// A file whose contents changed has a new modification time, so its cached
// hash cannot be reused -- otherwise editing a file would leave it grouped
// with what it used to be a copy of.
$put('Backup/holiday (1).jpg', 'entirely different now', 2_000_000_000);
$third = (new DuplicateFinder($fs, $cache))->scan();
$stillTogether = null;
foreach ($third['groups'] as $group) {
    if (in_array('/Photos/holiday.jpg', array_column($group['files'], 'path'), true)) $stillTogether = $group;
}
$checks['a changed file is no longer a copy of what it was'] =
    $stillTogether !== null && $stillTogether['copies'] === 2
    && !in_array('/Backup/holiday (1).jpg', array_column($stillTogether['files'], 'path'), true);

/* --- the route and the two clients -------------------------------------------
 *
 * The finder is exercised above and over HTTP in tests/http/run.php. These are
 * the pins for the wiring around it, which neither can reach: who may force a
 * scan, and the one rule the screens must never break.
 */
$readRepo = fn(string $path): string => (string)@file_get_contents(dirname(__DIR__).'/'.$path);
$index = $readRepo('public/index.php');
$appJs = $readRepo('public/assets/js/app.js');
$appView = $readRepo('views/pages/app.php');
$screen = $readRepo('android/app/src/main/java/nl/tippie/cloudhub/ui/DuplicatesScreen.kt');
$rules = $readRepo('android/app/src/main/java/nl/tippie/cloudhub/ui/DuplicateRules.kt');
$androidTests = $readRepo('android/app/src/test/java/nl/tippie/cloudhub/DuplicateRulesTest.kt');

// Walking the store and hashing what could match is the expensive request.
$checks['forcing a scan is an admin action'] =
    str_contains($index, "\$refresh = !empty(\$_GET['refresh']);")
    && str_contains($index, 'if ($refresh)Authorization::requireAdmin();');
// ...but a cold cache must not leave an ordinary account with nothing.
$checks['a scan still runs for whoever asks first'] =
    str_contains($index, '$finder = new DuplicateFinder($fs, dirname(__DIR__).\'/storage/.cache/hashes.json\');')
    && str_contains($index, '$report = $finder->scan($categories);');
$checks['the report is cached like the storage figure is'] =
    str_contains($index, "\$cache = dirname(__DIR__).'/storage/.cache/duplicates-'")
    && str_contains($index, "return \$stored+['cached' => true");

// The rule both screens exist to keep.
$checks['the app never offers to delete every copy'] =
    str_contains($rules, 'fun wouldEmptyAGroup(')
    && str_contains($screen, 'DuplicateRules.wouldEmptyAGroup(groups, selected)')
    && str_contains($androidTests, 'a hand-made selection that empties a group is caught');
$checks['the web page keeps the same rule'] =
    str_contains($appJs, 'group.files.length > 0 && group.files.every(f => dupes.selected.has(f.path))')
    && str_contains($appJs, 'Every copy selected');
// Deleting goes through the ordinary route, so it lands in the trash and can
// be undone -- the one thing that makes a bulk delete survivable.
$checks['deleting goes to the trash like any other delete'] =
    str_contains($screen, 'api.delete(path)')
    && str_contains($appJs, "await api('/api/files/delete', { method: 'DELETE', body: { path } })");
$checks['both clients can reach it'] =
    str_contains($appView, 'data-route="/duplicates"')
    && str_contains($index, "'/storage', '/duplicates'], true)")
    && str_contains($readRepo('android/app/src/main/java/nl/tippie/cloudhub/ui/FilesScreen.kt'), 'MenuItem(Icons.Default.ContentCopy, "Duplicates")');
// A scan that ran out of time found real duplicates but perhaps not all of
// them, and saying so is cheaper than being wrong.
$checks['an incomplete scan says so'] =
    str_contains($screen, 'report?.complete == false')
    && str_contains($appJs, 'the scan stopped early');

/* --- tidy up ---------------------------------------------------------------- */

$wipe = function (string $dir) use (&$wipe): void {
    foreach (scandir($dir) ?: [] as $name) {
        if ($name === '.' || $name === '..') continue;
        $full = $dir.'/'.$name;
        is_dir($full) ? $wipe($full) : @unlink($full);
    }
    @rmdir($dir);
};
$wipe($root);
@unlink($cache);

$bad = false;
foreach ($checks as $name => $ok) {
    echo ($ok ? '[PASS] ' : '[FAIL] ').$name.PHP_EOL;
    $bad = $bad || !$ok;
}
exit($bad ? 1 : 0);
