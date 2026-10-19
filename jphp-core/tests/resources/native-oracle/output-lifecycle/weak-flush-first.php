<?php 
$handler=function($s,$p){return '<'.$p.':'.bin2hex($s).'>';};
ob_start($handler,3);echo 'ab';ob_flush();foreach(['cd','e']as $piece)echo $piece;