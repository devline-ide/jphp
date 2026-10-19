<?php interface I {public int $x {get;}}class C implements I {public int $x=2 {&get{return $this->x;}}}echo (new C)->x;
