<?php
// Source-only controls. Expected output must be captured from the native oracle.
ini_set('pcre.backtrack_limit', '1000000');
ini_set('pcre.recursion_limit', '100000');
echo 'engine:', json_encode([PHP_VERSION, PCRE_VERSION, PCRE_JIT_SUPPORT, ini_get('pcre.backtrack_limit'), ini_get('pcre.recursion_limit')]), "\n";
class CacheReviewString {
    public $work;
    function __construct($work) { $this->work = $work; }
    function __toString(): string { return ($this->work)(); }
}
function cache_review($name, $work) {
    $saved = [ini_get('pcre.jit'), ini_get('pcre.backtrack_limit'), ini_get('pcre.recursion_limit')];
    $warnings = []; $count = 777; $GLOBALS['cacheReviewThrowAt'] = 0;
    set_error_handler(function($type, $message) use (&$warnings) {
        $warnings[] = $type;
        if ($GLOBALS['cacheReviewThrowAt'] === count($warnings)) throw new RuntimeException('review-warning');
        return true;
    });
    try {
        $value = $work($count);
        $result = ['ok', bin2hex(serialize($value)), $count, preg_last_error(), $warnings];
    } catch (Throwable $error) {
        $result = ['throw', get_class($error), $error->getMessage(), $count, preg_last_error(), $warnings];
    } finally {
        restore_error_handler();
        ini_set('pcre.jit', $saved[0]);
        ini_set('pcre.backtrack_limit', $saved[1]);
        ini_set('pcre.recursion_limit', $saved[2]);
        unset($GLOBALS['cacheReviewThrowAt']);
    }
    echo str_replace(':', '-', $name), ':', json_encode($result), "\n";
}
foreach (['preg_replace', 'preg_filter'] as $fn) {
    cache_review($fn . ':conversion-order-jit', function(&$count) use ($fn) {
        $events = []; $patternReads = 0; $replacementReads = 0;
        $pattern = new CacheReviewString(function() use (&$events, &$patternReads) {
            $events[] = 'pattern:' . ++$patternReads . ':' . ini_get('pcre.jit'); return '~a~';
        });
        $replacement = new CacheReviewString(function() use (&$events, &$replacementReads) {
            ++$replacementReads; ini_set('pcre.jit', $replacementReads % 2 ? '0' : '1');
            $events[] = 'replacement:' . $replacementReads . ':' . ini_get('pcre.jit'); return 'x';
        });
        ini_set('pcre.jit', '1');
        $out = $fn([$pattern], [$replacement], ['a', 'a', 'a'], -1, $count);
        return [$out, $events, $patternReads, $replacementReads];
    });
    cache_review($fn . ':cached-hit-error-reset', function(&$count) use ($fn) {
        $events = [];
        $subject = new CacheReviewString(function() use (&$events) {
            preg_match('~.~u', "\xff"); $events[] = preg_last_error(); return 'a';
        });
        return [$fn('~a~', 'x', ['a', $subject, $subject], -1, $count), $events];
    });
    cache_review($fn . ':subject-conversion-jit', function(&$count) use ($fn) {
        $events = []; $reads = 0;
        $subject = new CacheReviewString(function() use (&$events, &$reads) {
            ini_set('pcre.jit', ++$reads % 2 ? '0' : '1');
            $events[] = ini_get('pcre.jit'); return 'a';
        });
        return [$fn('~a~', 'x', [$subject, $subject, $subject], -1, $count), $events];
    });
    cache_review($fn . ':converted-pattern-bytes-change', function(&$count) use ($fn) {
        $reads = 0;
        $pattern = new CacheReviewString(function() use (&$reads) { return ++$reads % 2 ? '~a~' : '~b~'; });
        return [$fn([$pattern], ['X'], ['ab', 'ab', 'ab'], -1, $count), $reads];
    });
    cache_review($fn . ':cached-hit-limit-refresh', function(&$count) use ($fn) {
        ini_set('pcre.jit', '0'); ini_set('pcre.backtrack_limit', '1000000');
        $low = new CacheReviewString(function() {
            ini_set('pcre.backtrack_limit', '1'); return str_repeat('a', 24) . '!';
        });
        $restored = new CacheReviewString(function() {
            ini_set('pcre.backtrack_limit', '1000000'); return 'a';
        });
        return $fn('~^(a+)+$~', 'x', ['a', $low, $restored], -1, $count);
    });
    cache_review($fn . ':repeated-invalid-warning', function(&$count) use ($fn) {
        return $fn(['~a~', '~[~'], ['x', 'y'], ['a', 'a'], -1, $count);
    });
    cache_review($fn . ':second-warning-throws-count', function(&$count) use ($fn) {
        $GLOBALS['cacheReviewThrowAt'] = 2;
        return $fn(['~a~', '~[~'], ['x', 'y'], ['a', 'a'], -1, $count);
    });
    cache_review($fn . ':A-B-A-order', function(&$count) use ($fn) {
        return $fn(['~a~', '~b~', '~a~'], ['b', 'a', 'x'], ['a', 'ab'], -1, $count);
    });
    cache_review($fn . ':binary-pattern-identity', function(&$count) use ($fn) {
        return $fn(["~\0~", "~\xff~", "~\0~"], ['N', 'F', 'Q'], ["a\0\xff", "\xff\0"], -1, $count);
    });
    cache_review($fn . ':modifier-key-identity', function(&$count) use ($fn) {
        return $fn(['~a~', '~a~i', '~a~'], ['b', 'x', 'z'], ['A', 'aA'], -1, $count);
    });
    cache_review($fn . ':many-unique-then-repeat', function(&$count) use ($fn) {
        $patterns = []; $replacements = [];
        for ($i = 0; $i < 12; ++$i) { $patterns[] = '~(?#' . $i . ')a~'; $replacements[] = 'a'; }
        $patterns[] = $patterns[0]; $replacements[] = 'x';
        return $fn($patterns, $replacements, ['a', 'aa'], -1, $count);
    });
    cache_review($fn . ':oversized-key-one-shot', function(&$count) use ($fn) {
        $pattern = '~(?#' . str_repeat('q', 65536) . ')a~';
        return $fn([$pattern, '~b~'], ['b', 'X'], ['a', 'aa'], -1, $count);
    });
    cache_review($fn . ':replacement-conversion-reentry', function(&$count) use ($fn) {
        $events = [];
        $replacement = new CacheReviewString(function() use ($fn, &$events) {
            $nestedCount = 99; $nested = $fn('~a~', 'N', 'a', -1, $nestedCount);
            $events[] = [$nested, $nestedCount, preg_last_error()]; return 'X';
        });
        return [$fn(['~a~'], [$replacement], ['a', 'aa'], -1, $count), $events];
    });
    cache_review($fn . ':count-alias', function(&$count) use ($fn) {
        $alias =& $count; $out = $fn('~a~', 'x', ['a', 'aa'], -1, $alias);
        return [$out, $alias, $count];
    });
    cache_review($fn . ':zero-limit-still-compile-warnings', function(&$count) use ($fn) {
        return $fn(['~a~', '~[~'], ['x', 'y'], ['a', 'a'], 0, $count);
    });
    cache_review($fn . ':empty-subject-array-no-pattern-coercion', function(&$count) use ($fn) {
        $events = [];
        $pattern = new CacheReviewString(function() use (&$events) { $events[] = 'pattern'; return '~[~'; });
        $replacement = new CacheReviewString(function() use (&$events) { $events[] = 'replacement'; return 'x'; });
        return [$fn([$pattern], [$replacement], [], -1, $count), $events];
    });
}
