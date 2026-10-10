<?php
class LegacyConstOne{const VALUE=1;}
class LegacyConstTwo{const VALUE=2;}
function legacy_const_read($c){return $c::VALUE;}
return [legacy_const_read("LegacyConstOne"),legacy_const_read("LegacyConstTwo"),legacy_const_read("LegacyConstOne"),legacy_const_read("LegacyConstTwo")];
