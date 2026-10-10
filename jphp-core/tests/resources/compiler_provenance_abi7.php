<?php
class ProvenanceSeven { public int $x=7; }
function provenance_seven($value) { return $value+1; }
return provenance_seven((new ProvenanceSeven)->x);
