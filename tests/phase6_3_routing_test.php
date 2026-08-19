<?php
declare(strict_types=1);
require dirname(__DIR__).'/src/Helpers/Http.php';
use CloudHub\Helpers\Http;
$bad=false;
$_SERVER['SCRIPT_NAME']='/Cloud-File-Hub-PHP/index.php';
$_SERVER['REQUEST_URI']='/Cloud-File-Hub-PHP/api/auth/status';
$base=Http::basePath();$path=Http::requestPath($base);
$index=file_get_contents(dirname(__DIR__).'/public/index.php');
$logoutStart=strpos($index,"if(\$path==='/api/auth/logout'");
$logoutEnd=$logoutStart===false?false:strpos($index,"\nif(", $logoutStart+1);
$logout=$logoutStart===false?'':substr($index,$logoutStart,($logoutEnd?:strlen($index))-$logoutStart);
$checks=[
 'android subdirectory base path'=>$base==='/Cloud-File-Hub-PHP',
 'android API route stripping'=>$path==='/api/auth/status',
 'router passes basePath to requestPath'=>str_contains($index,'Http::requestPath($basePath)'),
 'logout route exists'=>$logoutStart!==false,
 'logout does not require write role'=>!str_contains($logout,'requireWrite'),
 'logout verifies CSRF'=>str_contains($logout,'verifyCsrf'),
 'auth endpoints excluded from protected guard'=>str_contains($index,'$isAuthEndpoint'),
 'API catch-all before page rendering'=>strpos($index,'API endpoint not found')<strrpos($index,'views/pages/app.php')
];
foreach($checks as $n=>$ok){echo($ok?'[PASS] ':'[FAIL] ').$n.PHP_EOL;$bad=$bad||!$ok;}
exit($bad?1:0);
