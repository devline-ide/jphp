<?php
declare(strict_types=1);
interface I{public private(set) int$x{get;set;}}class H implements I{public private(set) int$x=2;}echo (new H)->x,"\n";
