<?php
namespace StrictDiag;error_reporting(-1);$events=[];set_error_handler(function($n,$s)use(&$events){$events[]='warn:'.$n.':'.$s;return true;});for($i=0;$i<3;$i++){$events[]='loop:'.$i;$x=E_STRICT;$events[]='value:'.$x;}$events[]='before-qualified';$x=\E_STRICT;$events[]='qualified:'.$x;echo serialize($events);
