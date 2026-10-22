<?php
class ClosureCfgNested {
    public function run($skip) {
        $log = '';
        $unused = $skip && (function() use (&$log) { $log .= 'unexpected'; return true; })();
        $factory = function() use (&$log) {
            $log .= 'F';
            return function() use (&$log) { $log .= 'N'; return 'nested'; };
        };
        $nested = $factory();
        $tail = function() use (&$log) { $log .= 'T'; return 'tail'; };
        $a = $nested();
        $b = $tail();
        return $unused === false && $a === 'nested' && $b === 'tail' && $log === 'FNT';
    }
}
return (new ClosureCfgNested())->run(false);
