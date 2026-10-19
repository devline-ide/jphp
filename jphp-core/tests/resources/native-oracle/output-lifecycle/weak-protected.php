<?php 
$handler=function($s,$p){return '<'.$p.':'.bin2hex($s).'>';};
ob_start($handler,3,0);foreach(['ab','c','de']as $piece)echo $piece;