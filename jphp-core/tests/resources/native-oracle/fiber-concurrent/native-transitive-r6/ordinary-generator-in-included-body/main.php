<?php
$GLOBALS['events']=[];$GLOBALS['events'][]='outer-ret:'.(include __DIR__.'/outer.php');echo json_encode($GLOBALS['events']);
