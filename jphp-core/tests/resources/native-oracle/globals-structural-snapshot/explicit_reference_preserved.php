<?php
$value=1;$alias=&$value;$copy=$GLOBALS;$copy['alias']=7;echo json_encode([$value,$alias,$copy['value']]);
