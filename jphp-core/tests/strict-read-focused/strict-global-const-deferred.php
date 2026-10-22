<?php
error_reporting(-1);$events=[];set_error_handler(function($n,$s)use(&$events){$events[]='warn:'.$n.':'.$s;return true;});$events[]='before-declaration';const STRICT_ALIAS=E_STRICT;$events[]='after-declaration';$events[]='first:'.STRICT_ALIAS;$events[]='second:'.STRICT_ALIAS;echo serialize($events);
