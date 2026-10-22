<?php
echo 'engine:',json_encode([PHP_VERSION,PCRE_VERSION,PCRE_JIT_SUPPORT,ini_get('pcre.backtrack_limit'),ini_get('pcre.recursion_limit')]),"\n";
// Native oracle input: one shared PHP compile diagnostic through six function routes.
foreach (['match', 'all', 'split', 'grep', 'replace', 'filter'] as $prErrorRoute) {
    $prErrorWarnings = [];
    $prErrorMatches = 'matches-sentinel';
    $prErrorCount = 777;
    $prErrorResult = 'not-assigned';
    $prErrorException = null;
    set_error_handler(function ($severity, $message) use (&$prErrorWarnings) {
        $prErrorWarnings[] = [$severity, bin2hex($message)];
        return true;
    });
    try {
        switch ($prErrorRoute) {
            case 'match': $prErrorResult = preg_match('~\C~u', 'a', $prErrorMatches); break;
            case 'all': $prErrorResult = preg_match_all('~\C~u', 'a', $prErrorMatches); break;
            case 'split': $prErrorResult = preg_split('~\C~u', 'a'); break;
            case 'grep': $prErrorResult = preg_grep('~\C~u', ['a']); break;
            case 'replace': $prErrorResult = preg_replace('~\C~u', 'X', 'a', -1, $prErrorCount); break;
            case 'filter': $prErrorResult = preg_filter('~\C~u', 'X', 'a', -1, $prErrorCount); break;
        }
    } catch (Throwable $failure) {
        $prErrorException = [get_class($failure), $failure->getMessage()];
    } finally {
        restore_error_handler();
    }
    echo 'backslash-c-', $prErrorRoute, ':', json_encode([
        'case' => $prErrorRoute,
        'result' => $prErrorResult,
        'error' => preg_last_error(),
        'warnings' => $prErrorWarnings,
        'matches' => $prErrorMatches,
        'count' => $prErrorCount,
        'exception' => $prErrorException,
    ], JSON_UNESCAPED_SLASHES), "\n";
}
