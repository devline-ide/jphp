<?php
require __DIR__ . "/helper.php";
function arg() { emit('arg','evaluated'); return 1; } #[NoDiscard] function marked($v) { emit('body',$v); return 2; }
set_error_handler(function($s,$m) { emit('warning',[$s,$m]); throw new Exception('handler'); });
attempt('call',function() { marked(arg()); return 'completed'; });
