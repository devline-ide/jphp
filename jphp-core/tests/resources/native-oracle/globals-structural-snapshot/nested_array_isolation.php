<?php
$bag=['n'=>1];$copy=$GLOBALS;$copy['bag']['n']=2;$bag['n']=3;echo json_encode([$copy['bag']['n'],$bag['n']]);
