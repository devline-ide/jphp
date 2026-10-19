<?php declare(strict_types=1);
$warnings=[];$events=[];
set_error_handler(function($n,$s)use(&$warnings){$handlers=ob_list_handlers();$active=$handlers[count($handlers)-1];$warnings[]=[$n,str_replace($active,'<active-handler>',$s)];return true;});
$callback=null;
ob_start($callback,0,112);
echo 'held';$value=ob_get_flush();
$result=[$value,ob_get_level(),ob_get_contents(),$warnings,$events];
restore_error_handler();echo json_encode($result,JSON_THROW_ON_ERROR),"\n";
