<?php
echo 'engine:',json_encode([PHP_VERSION,PCRE_VERSION,PCRE_JIT_SUPPORT,ini_get('pcre.backtrack_limit'),ini_get('pcre.recursion_limit')]),"\n";
// Oracle input only. Execute with the same PHP configuration as the parent corpus.
class PrFrameMutator {
    public function __toString() {
        global $prFrameInput, $prFrameOperation, $prFrameEvents;
        $prFrameEvents[] = $prFrameOperation;
        if ($prFrameOperation === 'unset') unset($prFrameInput['tail']);
        if ($prFrameOperation === 'replace') $prFrameInput['tail'] = 'b';
        if ($prFrameOperation === 'append') $prFrameInput['added'] = 'a';
        return 'a';
    }
}
foreach (['unset', 'replace', 'append'] as $prFrameOperation) {
    foreach (['direct3', 'direct4', 'direct5', 'dynamic3', 'filter3'] as $prFrameRoute) {
        $prFrameEvents = [];
        $prFrameWarnings = [];
        $prFrameInput = ['first' => new PrFrameMutator(), 'tail' => 'a'];
        $prFrameCount = 777;
        $prFrameException = null;
        $prFrameResult = 'not-assigned';
        set_error_handler(function ($severity, $message) use (&$prFrameWarnings) {
            $prFrameWarnings[] = [$severity, $message];
            return true;
        });
        try {
            switch ($prFrameRoute) {
                case 'direct3':
                    $prFrameResult = preg_replace('~a~', 'x', $prFrameInput);
                    break;
                case 'direct4':
                    $prFrameResult = preg_replace('~a~', 'x', $prFrameInput, -1);
                    break;
                case 'direct5':
                    $prFrameResult = preg_replace('~a~', 'x', $prFrameInput, -1, $prFrameCount);
                    break;
                case 'dynamic3':
                    $prFrameFunction = 'preg_replace';
                    $prFrameResult = $prFrameFunction('~a~', 'x', $prFrameInput);
                    break;
                case 'filter3':
                    $prFrameResult = preg_filter('~a~', 'x', $prFrameInput);
                    break;
            }
        } catch (Throwable $failure) {
            $prFrameException = [get_class($failure), $failure->getMessage()];
        } finally {
            restore_error_handler();
        }
        $prFrameAfter = [];
        foreach ($prFrameInput as $key => $value) {
            $prFrameAfter[$key] = is_object($value) ? '<Stringable>' : $value;
        }
        echo 'frameless-', $prFrameOperation, '-', $prFrameRoute, ':', json_encode([
            'case' => $prFrameOperation . ':' . $prFrameRoute,
            'result' => $prFrameResult,
            'inputAfter' => $prFrameAfter,
            'events' => $prFrameEvents,
            'count' => $prFrameCount,
            'error' => preg_last_error(),
            'warnings' => $prFrameWarnings,
            'exception' => $prFrameException,
        ], JSON_UNESCAPED_SLASHES), "\n";
    }
}
