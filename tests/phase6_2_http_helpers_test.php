<?php
declare(strict_types=1);
require dirname(__DIR__).'/src/Helpers/Http.php';
use CloudHub\Helpers\Http;

$bad=false;
$cases=[
 ['/Cloud-File-Hub-PHP/api/files?x=1','/Cloud-File-Hub-PHP','/api/files'],
 ['/Cloud-File-Hub-PHP/','/Cloud-File-Hub-PHP','/'],
 ['/api/files','','/api/files'],
];
foreach($cases as [$uri,$base,$expected]){
 $_SERVER['REQUEST_URI']=$uri;
 $actual=Http::requestPath($base);
 $ok=$actual===$expected;
 echo($ok?'[PASS] ':'[FAIL] ').$uri.' => '.$actual.PHP_EOL;
 $bad=$bad||!$ok;
}

/*
 * Paths on their way into a URL.
 *
 * SCRIPT_NAME arrives decoded, so an install in a folder called "Cloud File
 * Hub" produces a base path with real spaces. A share link is a URL made to
 * be handed to someone else: with a space in it, chat clients cut it short at
 * the space and curl refuses it outright.
 */
$paths=[
 ['','' ],
 ['/Cloud File Hub','/Cloud%20File%20Hub'],
 ['/Cloud-File-Hub-PHP','/Cloud-File-Hub-PHP'],
 // Segment by segment, so the separators are still separators.
 ['/two words/deep','/two%20words/deep'],
 // A folder named with a percent sign encodes; it is not read as an escape.
 ['/100%','/100%25'],
];
foreach($paths as [$in,$expected]){
 $actual=Http::encodePath($in);
 $ok=$actual===$expected;
 echo($ok?'[PASS] ':'[FAIL] ').'encodePath('.var_export($in,true).') => '.$actual.PHP_EOL;
 $bad=$bad||!$ok;
}
exit($bad?1:0);
