<?php
// PHP8.5 additions and the PHP8.4 class-constant contract, oracle pending.
$dcEvents = [];
set_error_handler(function($severity, $message) use (&$dcEvents) { $dcEvents[] = [$severity, $message]; return true; });
#[Deprecated('trait')] trait DcOldTrait { function value() { return 3; } }
class DcUsesTrait { use DcOldTrait; }
class DcConstants { #[Deprecated('class constant')] const OLD = 4; }
#[Deprecated('global constant')] const DC_OLD = 5;
echo 'trait-link:', json_encode($dcEvents), "\n";
$dcEvents = [];
$r = new ReflectionClassConstant(DcConstants::class, 'OLD'); $g = new ReflectionConstant('DC_OLD');
echo 'constant-reflection:', json_encode([$r->isDeprecated(), $g->isDeprecated(), $r->getValue(), $g->getValue(), $dcEvents]), "\n";
$dcEvents = [];
echo 'constant-repeated-read:', json_encode([[DcConstants::OLD, DcConstants::OLD, DC_OLD, DC_OLD], $dcEvents]), "\n";
restore_error_handler();
