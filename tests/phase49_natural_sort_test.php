<?php
declare(strict_types=1);

/**
 * Natural Number, the fourth option under Sort by in the Android app.
 *
 * The ordering itself is tested on the JVM: the comparator in NaturalOrderTest
 * (the examples it was asked for, extensions, leading zeros, case, and that it
 * is a total order a sort can rely on), and the folder under every option in
 * FileSortTest. These pin the wiring around it -- one sorting system rather
 * than two, and the three options that were already there left exactly as
 * they were.
 */
$root = dirname(__DIR__);
$kotlin = $root.'/android/app/src/main/java/nl/tippie/cloudhub';
$readKt = fn(string $path): string => (string)@file_get_contents($kotlin.'/'.$path);
$readTest = fn(string $name): string =>
    (string)@file_get_contents($root.'/android/app/src/test/java/nl/tippie/cloudhub/'.$name);

$files = $readKt('ui/FilesScreen.kt');
$state = $readKt('ui/FilesViewModel.kt');
$order = $readKt('ui/NaturalOrder.kt');
$orderTests = $readTest('NaturalOrderTest.kt');
$sortTests = $readTest('FileSortTest.kt');

$checks = [];

/* --- the menu ---------------------------------------------------------------
 *
 * The same SortItem as the other three, so the same icon column, the same
 * tick and the same colours for the one in force -- and beside Name, which
 * it is with numbers read as numbers.
 */
$checks['the menu offers Natural Number like the other sorts'] =
    str_contains($files, 'SortItem("Natural Number", Icons.Default.FormatListNumbered, sort == FilesState.Sort.NATURAL) {')
    && str_contains($files, 'onOverflow(false); onSort(FilesState.Sort.NATURAL)')
    && str_contains($files, 'import androidx.compose.material.icons.filled.FormatListNumbered');
$name = strpos($files, 'SortItem("Name",');
$natural = strpos($files, 'SortItem("Natural Number",');
$newest = strpos($files, 'SortItem("Newest",');
$checks['it sits between Name and Newest'] =
    $name !== false && $natural !== false && $newest !== false && $name < $natural && $natural < $newest;

/* --- one sorting system -------------------------------------------------------- */

$checks['it is a fourth value of the existing sort'] =
    str_contains($state, 'enum class Sort { NAME, NATURAL, NEWEST, LARGEST }')
    && str_contains($state, 'Sort.NATURAL -> NaturalOrder.compare(a.name, b.name)');
// Folders first under every sort, this one included.
$checks['folders still come first'] =
    str_contains($state, 'compareByDescending<FileEntry> { it.isDirectory }.thenComparator { a, b ->');
$checks['Name, Newest and Largest are as they were'] =
    str_contains($state, 'Sort.NAME -> a.name.compareTo(b.name, ignoreCase = true)')
    && str_contains($state, 'Sort.NEWEST -> b.modified.compareTo(a.modified)')
    && str_contains($state, 'Sort.LARGEST -> b.size.compareTo(a.size)')
    && str_contains($state, 'val sort: Sort = Sort.NAME,');
// The grid, the empty-folder check and the scroll restore all read it; as a
// getter, every read sorted the folder again.
$checks['a state is sorted once, not on every read'] =
    str_contains($state, 'val visible: List<FileEntry> by lazy {')
    && !preg_match('/val visible: List<FileEntry>\s*\n\s*get\(\)/', $state);
// Choosing a sort changes what is on screen and nothing else: no request, so
// nothing on the server is moved, renamed or touched.
$checks['choosing a sort touches nothing but the screen'] =
    str_contains($state, 'fun setSort(sort: FilesState.Sort) = _state.update { it.copy(sort = sort) }');

/* --- the comparator ------------------------------------------------------------ */

$checks['the comparator is one the sort can take as it is'] =
    str_contains($order, 'object NaturalOrder : Comparator<String> {');
// Digits compared as text: a camera's 30-digit serial cannot overflow, and no
// regex or split allocates on every one of a big folder's comparisons.
$checks['numbers are never parsed, so they cannot overflow'] =
    !preg_match('/\.to(Long|Int|BigInteger|BigDecimal|Double)(OrNull)?\(/', $order);
$checks['nothing is allocated per comparison'] =
    !str_contains($order, 'Regex(') && !str_contains($order, '.split(') && !str_contains($order, 'substring(');
$checks['names that read the same are still settled'] =
    str_contains($order, 'private fun tieBreak(a: String, b: String): Int {')
    && str_contains($order, 'return a.compareTo(b)');

/* --- the tests that prove the order ------------------------------------------- */

$checks['the examples it was asked for are tested'] =
    str_contains($orderTests, 'assertOrder("1", "2", "3", "4", "5", "10", "11", "12")')
    && str_contains($orderTests, 'assertOrder("Folder 2", "Folder 10")')
    && str_contains($orderTests, 'assertOrder("Image 1.jpg", "Image 2.jpg", "Image 10.jpg")')
    && str_contains($orderTests, 'assertOrder("1.2.3", "1.2.4", "1.2.10")');
$checks['and so is what a sort relies on'] =
    str_contains($orderTests, 'it is a total order on these names')
    && str_contains($orderTests, 'an original comes before its copy however the copy was named');
$checks['the three older sorts are pinned by test'] =
    str_contains($sortTests, 'Name is as it was, a character at a time')
    && str_contains($sortTests, 'Newest is as it was')
    && str_contains($sortTests, 'Largest is as it was')
    && str_contains($sortTests, 'a state is sorted once, however often it is read');

$bad = false;
foreach ($checks as $label => $ok) {
    echo ($ok ? '[PASS] ' : '[FAIL] ').$label.PHP_EOL;
    $bad = $bad || !$ok;
}
exit($bad ? 1 : 0);
