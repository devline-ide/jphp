<?php 
$handler=function($s,$p){return '<'.$p.':'.bin2hex($s).'>';};
ob_start($handler);