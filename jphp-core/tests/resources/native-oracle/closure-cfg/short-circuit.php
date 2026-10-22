<?php
function closureCfgApply($callback) { return $callback(); }
class ClosureCfgShortCircuit {
    public function run($left, $right) {
        $log = '';
        $a = $left && closureCfgApply(function() use (&$log) { $log .= 'A'; return 'a'; }) === 'a';
        $b = $right && closureCfgApply(function() use (&$log) { $log .= 'B'; return 'b'; }) === 'b';
        $last = function() use (&$log) { $log .= 'Z'; return 'z'; };
        $z = $last();
        $wanted = ($left ? 'A' : '').($right ? 'B' : '').'Z';
        return $a === $left && $b === $right && $z === 'z' && $log === $wanted;
    }
}
$probe = new ClosureCfgShortCircuit();
return $probe->run(false, false) && $probe->run(false, true) && $probe->run(true, false) && $probe->run(true, true);
