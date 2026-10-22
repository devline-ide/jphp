<?php
$a=['GLOBALS'=>['n'=>1]];$copy=$a;$copy['GLOBALS']['n']=2;echo json_encode([$a['GLOBALS']['n'],$copy['GLOBALS']['n']]);
