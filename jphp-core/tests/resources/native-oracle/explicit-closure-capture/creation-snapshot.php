<?php
$events=[];error_reporting(E_ALL);
set_error_handler(function($type,$message)use(&$events){$events[]=[$type,$message];if($message==='Undefined variable $healed')$GLOBALS['healed']=17;return true;});
$events[]='before';$f=function()use($missing){return$missing;};$events[]='after';
$rows=[$f(),array_key_exists('missing',$GLOBALS)];
$null=null;$n=function()use($null){return$null;};$rows[]=$n();
$multi=function()use($first,$second){return[$first,$second];};$rows[]=$multi();
$healing=function()use($healed){return$healed;};$rows[]=$healing();$rows[]=$GLOBALS['healed'];
restore_error_handler();echo json_encode([$rows,$events]);
