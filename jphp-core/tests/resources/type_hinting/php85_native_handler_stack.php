<?php
$events=[];
$one=function($s,$m)use(&$events){$events[]='one';return true;};
$two=function($s,$m)use(&$events){$events[]='two';return true;};
set_error_handler($one);set_error_handler($two);trigger_error('first');restore_error_handler();trigger_error('second');restore_error_handler();restore_error_handler();
$outer=function($s,$m)use(&$events){$events[]='outer';$last=set_error_handler(function($s,$m)use(&$events){$events[]='inner';return true;});$events[]=($last===null);trigger_error('nested');restore_error_handler();$events[]='outer-end';return true;};
set_error_handler($outer);trigger_error('outer');$last=set_error_handler($one);$events[]=($last===$outer);restore_error_handler();restore_error_handler();restore_error_handler();
set_error_handler(function($s,$m)use(&$events){$events[]='throw';throw new Exception('stop');});try{trigger_error('throws');}catch(Throwable $e){$events[]=$e->getMessage();}restore_error_handler();restore_error_handler();
echo serialize($events),"\n";
