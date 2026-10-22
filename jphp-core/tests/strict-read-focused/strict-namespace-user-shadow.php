<?php
namespace StrictShadow;error_reporting(-1);$events=[];set_error_handler(function($n,$s)use(&$events){$events[]='warn:'.$n.':'.$s;return true;});const E_STRICT=9;$events[]='local:'.E_STRICT;$events[]='dynamic-local:'.constant(__NAMESPACE__.'\E_STRICT');$events[]='before-builtin';$v=\E_STRICT;$events[]='builtin:'.$v;echo serialize($events);
