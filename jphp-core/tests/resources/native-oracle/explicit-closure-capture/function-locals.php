<?php
$events=[];set_error_handler(function($type,$message)use(&$events){$events[]=[$type,$message];return true;});
function conditional($set,$value){if($set)$captured=$value;$f=function()use($captured){return$captured;};return$f();}
function removed(){ $gone=11;unset($gone);$f=function()use($gone){return$gone;};return$f(); }
function nested(){return(function(){if(false)$inside=9;return function()use($inside){return$inside;};})();}
$rows=[conditional(false,7),conditional(true,7),conditional(true,null),removed(),nested()()];
restore_error_handler();echo json_encode([$rows,$events]);
