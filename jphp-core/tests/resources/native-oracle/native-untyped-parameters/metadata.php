<?php
// NATIVE_DECLARATIONS_BEGIN
function qa_meta_untyped(&$value = null) {}
function qa_meta_mixed(mixed $value = null) {}
function qa_meta_class(?stdClass $value = null) {}
function qa_meta_union(array|string $value) {}
function qa_meta_default(int $value = 7) {}
// NATIVE_DECLARATIONS_END
foreach (['qa_meta_untyped','qa_meta_mixed','qa_meta_class','qa_meta_union','qa_meta_default'] as $name) {
    $function = new ReflectionFunction($name);
    $parameter = $function->getParameters()[0];
    $type = $parameter->getType();
    echo $name, ':', json_encode([
        $parameter->getName(), $parameter->hasType(), $type ? (string) $type : null,
        $type ? $type->allowsNull() : null, $parameter->allowsNull(),
        $parameter->isPassedByReference(), $parameter->isOptional(),
        $parameter->isDefaultValueAvailable(),
        $parameter->isDefaultValueAvailable() ? $parameter->getDefaultValue() : 'absent',
        $function->getNumberOfRequiredParameters(), $function->getNumberOfParameters()
    ], JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE), "\n";
}
