<?php
$base=1;$copy=$GLOBALS;$copy['base']=2;$base=3;echo json_encode([$copy['base'],$base,array_key_exists('GLOBALS',$copy)]);
