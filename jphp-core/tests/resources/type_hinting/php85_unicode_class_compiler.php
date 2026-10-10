<?php
namespace UCC;

const DEFERRED_VALUE = 31;
class Σ { const V = 11; const DEFERRED = DEFERRED_VALUE; }
class σ {
    const V = 22;
    const FROM_OTHER = Σ::V;
    const FROM_DYNAMIC = ('UCC\\Σ')::V;
    const DEFERRED = 42;
    const FROM_DEFERRED = Σ::DEFERRED;
    public int $value = Σ::V;
    public static int $shared = Σ::V;
}
class ſtatic {}
class ſelf {}
class KeywordTypes {
    public ſtatic $value;
    public ſelf $other;
}
interface Ι {}
interface ι {}
interface Κ {}
class Both implements Ι, ι {}
class UpperOnly implements Ι {}
class LowerOnly implements ι {}
class Alternate implements Κ {}
function union_value(Σ|σ $value): string { return get_class($value); }
function intersection_value(Ι&ι $value): string { return get_class($value); }
function dnf_value((Ι&ι)|Κ $value): string { return get_class($value); }
function dnf_alternatives((Ι&ι)|(Ι&Κ) $value): string { return get_class($value); }
class UpperAlternate implements Ι, Κ {}
function keyword_return(ſtatic $value): ſtatic { return $value; }
function keyword_self(ſelf $value): ſelf { return $value; }
function upper_value(Σ $value): Σ { return $value; }
class TypedProperties { public Σ $upper; public σ $lower; }
class ΣException extends \Exception {}
class σException extends \Exception {}
trait Τ { public function pick() { return 'upper'; } }
trait τ { public function pick() { return 'lower'; } }
trait K { public function pick() { return 'kelvin'; } }
trait K { public function pick() { return 'ascii'; } }
class TraitChoice {
    use Τ, τ, K, K {
        Τ::pick insteadof τ, K, K;
        τ::pick as lower;
        K::pick as kelvin;
        K::pick as ascii;
    }
}
class TraitMergedChoice {
    use Τ, τ, K, K {
        Τ::pick insteadof τ;
        Τ::pick insteadof K, K;
        τ::pick as lower;
        K::pick as kelvin;
        K::pick as ascii;
    }
}
function case_value($label, $body) {
    $diagnostics = [];
    set_error_handler(function($type, $message) use (&$diagnostics) {
        $diagnostics[] = [$type, $message];
        return true;
    });
    try { return [$label, 'ok', $body(), $diagnostics]; }
    catch (\Throwable $error) { return [$label, get_class($error), $error->getMessage(), $diagnostics]; }
    finally { restore_error_handler(); }
}
function class_name() { return 'UCC\\Σ'; }
function receiver() { $GLOBALS['ucc_order'][] = 'receiver'; return new Σ; }
function rhs_name() { $GLOBALS['ucc_order'][] = 'class'; return 'UCC\\Σ'; }
function replace_receiver(&$value) { $value = new σ; return 'UCC\\Σ'; }
return [
    case_value('class-names', function() { return [Σ::class, σ::class, ſtatic::class, ſelf::class]; }),
    case_value('ascii-namespace-case', function() { $name = 'ucc\\Σ'; return get_class(new $name); }),
    case_value('dynamic-distinct-class', function() { $name = 'UCC\\σ'; return get_class(new $name); }),
    case_value('literal-instanceof-same', function() { $value = new Σ; return $value instanceof Σ; }),
    case_value('literal-instanceof-distinct', function() { $value = new Σ; return $value instanceof σ; }),
    case_value('dynamic-instanceof-same', function() { $value = new Σ; $name = 'UCC\\Σ'; return $value instanceof $name; }),
    case_value('dynamic-instanceof-distinct', function() { $value = new Σ; $name = 'UCC\\σ'; return $value instanceof $name; }),
    case_value('call-rhs-instanceof-same', function() { $value = new Σ; return $value instanceof (class_name()); }),
    case_value('call-left-instanceof-same', function() { return receiver() instanceof Σ; }),
    case_value('both-calls-evaluation-order', function() { $GLOBALS['ucc_order'] = []; $result = receiver() instanceof (rhs_name()); return [$result, $GLOBALS['ucc_order']]; }),
    case_value('discarded-rhs-instanceof', function() { $value = new Σ; $value instanceof (class_name()); return 17; }),
    case_value('rhs-replaces-receiver', function() { $value = new Σ; $result = $value instanceof (replace_receiver($value)); return [$result, get_class($value)]; }),
    case_value('nested-instanceof-booleans', function() { $value = new Σ; return [($value instanceof (class_name())) && ($value instanceof Σ), ($value instanceof σ) || ($value instanceof (class_name()))]; }),
    case_value('upper-catch', function() { try { throw new ΣException; } catch (σException $e) { return 'wrong'; } catch (ΣException $e) { return 'upper'; } }),
    case_value('lower-catch', function() { try { throw new σException; } catch (ΣException $e) { return 'wrong'; } catch (σException $e) { return 'lower'; } }),
    case_value('other-owner-constant', function() { return [σ::V, σ::FROM_OTHER, σ::FROM_DYNAMIC, σ::FROM_DEFERRED]; }),
    case_value('other-owner-property-default', function() { return [(new σ)->value, σ::$shared]; }),
    case_value('union-upper', function() { return union_value(new Σ); }),
    case_value('union-lower', function() { return union_value(new σ); }),
    case_value('union-error', function() { return union_value(new Both); }),
    case_value('intersection-both', function() { return intersection_value(new Both); }),
    case_value('intersection-upper-error', function() { return intersection_value(new UpperOnly); }),
    case_value('intersection-lower-error', function() { return intersection_value(new LowerOnly); }),
    case_value('dnf-intersection', function() { return dnf_value(new Both); }),
    case_value('dnf-alternative', function() { return dnf_value(new Alternate); }),
    case_value('dnf-error', function() { return dnf_value(new UpperOnly); }),
    case_value('dnf-distinct-intersections-first', function() { return dnf_alternatives(new Both); }),
    case_value('dnf-distinct-intersections-second', function() { return dnf_alternatives(new UpperAlternate); }),
    case_value('unicode-keyword-return', function() { return get_class(keyword_return(new ſtatic)); }),
    case_value('unicode-self-return', function() { return get_class(keyword_self(new ſelf)); }),
    case_value('unicode-keyword-property', function() { $value = new KeywordTypes; $value->value = new ſtatic; $value->other = new ſelf; return [get_class($value->value), get_class($value->other)]; }),
    case_value('typed-upper-value', function() { return get_class(upper_value(new Σ)); }),
    case_value('typed-distinct-error', function() { return upper_value(new σ); }),
    case_value('typed-properties-distinct', function() { $value = new TypedProperties; $value->upper = new Σ; $value->lower = new σ; return [get_class($value->upper), get_class($value->lower)]; }),
    case_value('typed-property-wrong-class', function() { $value = new TypedProperties; $value->upper = new σ; return $value->upper; }),
    case_value('trait-distinct-selection', function() { $value = new TraitChoice; return [$value->pick(), $value->lower(), $value->kelvin(), $value->ascii()]; }),
    case_value('trait-merged-exclusions', function() { $value = new TraitMergedChoice; return [$value->pick(), $value->lower(), $value->kelvin(), $value->ascii()]; }),
    case_value('ascii-method-case-control', function() { return (new TraitChoice)->PICK(); })
];
