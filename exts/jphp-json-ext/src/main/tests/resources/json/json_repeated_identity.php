<?php
function formatRepeatedJson($value) { return json_encode($value); }
class JsonRepeatedPayload implements JsonSerializable {
    public $calls = 0;
    public $value;
    public function jsonSerialize(): mixed {
        ++$this->calls;
        return $this->value === null ? ['value' => $this->calls] : $this->value;
    }
}
$leaf = new stdClass();
$leaf->value = 1;
$results = [];
$results[] = formatRepeatedJson([$leaf, $leaf]);
$diamond = new stdClass();
$diamond->left = $leaf;
$diamond->right = $leaf;
$results[] = formatRepeatedJson($diamond);
$hook = new JsonRepeatedPayload();
$results[] = formatRepeatedJson([$hook, $hook]);
$hook->value = [$leaf, $leaf];
$results[] = formatRepeatedJson([$hook, $hook]);
return $results;
