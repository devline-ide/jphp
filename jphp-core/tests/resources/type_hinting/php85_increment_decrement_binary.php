<?php
$samples=[['invalid-prefix',"\xffz"],['invalid-tail',"\xff"],['invalid-middle',"a\xffz"],['numeric99','99'],['numeric9','9'],['nul-prefix',"\x00z"],['nul-middle',"a\x00z"],['ascii-z','z'],['utf8','é']];
$rows=[];
foreach($samples as $sample)foreach(['post++','pre++','post--','pre--'] as $mode){$events=[];$v=$sample[1];$result=null;set_error_handler(function($s,$m)use(&$events){$events[]=[$s,$m];return true;});if($mode==='post++')$result=$v++;elseif($mode==='pre++')$result=++$v;elseif($mode==='post--')$result=$v--;else $result=--$v;restore_error_handler();$rows[]=[$sample[0].':'.$mode,[gettype($result),is_string($result)?bin2hex($result):serialize($result)],[gettype($v),is_string($v)?bin2hex($v):serialize($v)],$events];}
return $rows;
