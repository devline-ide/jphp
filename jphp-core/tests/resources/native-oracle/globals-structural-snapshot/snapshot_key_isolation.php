<?php
$base=1;$copy=$GLOBALS;$copy['snapshotOnly']=2;$GLOBALS['tableOnly']=3;echo json_encode([array_key_exists('snapshotOnly',$GLOBALS),array_key_exists('tableOnly',$copy),$copy['snapshotOnly'],$GLOBALS['tableOnly']]);
