<?php
declare(strict_types=1);
class H { public Generator $x { get { echo "start\n"; yield "a" => 3; echo "resume\n"; yield 7; return 11; } } } $g=(new H)->x; echo "made\n"; foreach($g as $k=>$v) echo "$k:$v\n"; echo "ret:",$g->getReturn(),"\n";
