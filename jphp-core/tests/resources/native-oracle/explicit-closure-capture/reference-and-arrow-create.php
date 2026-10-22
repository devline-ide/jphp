<?php
$events=[];set_error_handler(function($type,$message)use(&$events){$events[]=[$type,$message];return true;});
$ref=function()use(&$referenceMissing){return$referenceMissing;};
$before=$ref();$referenceMissing=19;$after=$ref();
$copy=function()use($referenceMissing){return$referenceMissing;};
$arrow=fn()=>$implicitMissing;$events[]='after-arrow-create';
$known=13;$knownArrow=fn()=>$known;$known=14;
restore_error_handler();echo json_encode([$before,$after,$copy(),$knownArrow(),$events]);
