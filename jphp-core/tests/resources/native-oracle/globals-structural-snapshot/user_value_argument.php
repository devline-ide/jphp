<?php
function change($table){$table['existing']=9;$table['childOnly']=4;return $table;}$existing=1;$copy=change($GLOBALS);$GLOBALS['parentOnly']=8;echo json_encode([$existing,$copy['existing'],array_key_exists('childOnly',$GLOBALS),array_key_exists('parentOnly',$copy)]);
