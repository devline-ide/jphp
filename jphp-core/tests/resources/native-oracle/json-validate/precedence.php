<?php
$cases=['unclosed'=>['"abc'],'high-unclosed'=>['"\ud800'],'utf8-after-root'=>["[]\xff"],'depth-before-badbyte'=>["[\xff",1],'badbyte-before-close'=>["[\xff",2],'non-json-whitespace'=>["\v"]];
foreach($cases as$id=>$a){$r=json_validate(...$a);$e=json_last_error();$m=json_last_error_msg();echo json_encode([$id,$r,$e,$m]),"\n";}
