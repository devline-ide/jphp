<?php
class ClosureCfgConsecutive {
    public function run() {
        $log = '';
        $base = 'original';
        $first = function() use (&$log, $base) { $log .= 'A'; return $base; };
        $base = 'changed';
        $second = function() use (&$log) { $log .= 'B'; return 'second'; };
        $a = $first();
        $b = $second();
        return $a === 'original' && $b === 'second' && $log === 'AB';
    }
}
return (new ClosureCfgConsecutive())->run();
