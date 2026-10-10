<?php
namespace php\gui;

/**
 * Class UXPopupWindow
 * @package php\gui
 * @packages gui, javafx
 */
class UXPopupWindow extends UXWindow
{


    /**
     * @var bool
     */
    public $autoFix;

    /**
     * @var bool
     */
    public $autoHide;

    /**
     * @var bool
     */
    public $hideOnEscape;

    /**
     * Popup anchor relative to the window or content, including CSS bounds.
     * @var string WINDOW_TOP_LEFT, WINDOW_TOP_RIGHT, WINDOW_BOTTOM_LEFT, WINDOW_BOTTOM_RIGHT,
     *             CONTENT_TOP_LEFT, CONTENT_TOP_RIGHT, CONTENT_BOTTOM_LEFT, CONTENT_BOTTOM_RIGHT
     */
    public $anchorLocation;

    /**
     * @var string
     */
    public $style;

    /**
     * @var UXList
     */
    public $classes;

    /**
     * @param UXWindow $window
     * @param double $screenX (optional)
     * @param double $screenY (optional)
     */
    public function show(UXWindow $window, $screenX, $screenY) {}

    /**
     * @param UXNode $node
     * @param double $offsetX (optional)
     * @param double $offsetY (optional)
     */
    public function showByNode(UXNode $node, $offsetX, $offsetY) {}

    /**
     * ...
     */
    public function hide() {}
}