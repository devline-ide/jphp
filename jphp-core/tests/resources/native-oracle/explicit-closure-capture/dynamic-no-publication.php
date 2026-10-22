<?php
$events=[];set_error_handler(function($type,$message)use(&$events){$events[]=[$type,$message];return true;});
function dynamic(){ $before=array_key_exists('notCreated',get_defined_vars());$f=function()use($notCreated){return$notCreated;};$after=array_key_exists('notCreated',get_defined_vars());return[$before,$f(),$after]; }
$GLOBALS['fromTable']=23;$fromTable=function()use($fromTable){return$fromTable;};
$first=function()use(&$createdByReference){return$createdByReference;};$second=function()use($createdByReference){return$createdByReference;};
$rows=[dynamic(),$fromTable(),$first(),$second()];restore_error_handler();echo json_encode([$rows,$events]);
