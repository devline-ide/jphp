<?php declare(strict_types=1);
$handler=function($s,$p){return '<'.$p.':'.bin2hex($s).'>';};
ob_start($handler,3);foreach(['ab','c','de','f','ghij']as $piece)echo $piece;