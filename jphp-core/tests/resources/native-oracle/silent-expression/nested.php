<?php
$rows=[];
foreach([0,E_WARNING,E_ALL]as$old)foreach([null,0,1,81,123,E_WARNING,E_ALL]as$new)foreach([false,true]as$escape){
 error_reporting($old);$events=[];
 try{@(function()use($new,$escape,&$events){$events[]=['outer',error_reporting()];try{@(function()use($new,&$events){$events[]=['inner',error_reporting()];if($new!==null)error_reporting($new);throw new RuntimeException('inner');})();}catch(RuntimeException$e){$events[]=['caught-inner',error_reporting()];if($escape)throw$e;}finally{$events[]=['outer-finally',error_reporting()];}$events[]=['outer-end',error_reporting()];})();}catch(RuntimeException$e){$events[]=['caught-outer',error_reporting()];}
 $rows[]=[$old,$new,$escape,$events,error_reporting()];
}
echo json_encode($rows);
