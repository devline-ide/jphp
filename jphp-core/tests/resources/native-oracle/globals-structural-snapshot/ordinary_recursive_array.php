<?php
$a=[];$a['GLOBALS']=&$a;$copy=$a;$copy['fresh']=2;echo json_encode([isset($a['fresh']),isset($copy['GLOBALS']['fresh']),$copy['fresh']]);
