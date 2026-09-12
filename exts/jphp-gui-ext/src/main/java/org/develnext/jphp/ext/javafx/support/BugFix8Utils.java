package org.develnext.jphp.ext.javafx.support;

import javafx.scene.control.TextField;
import javafx.scene.input.ContextMenuEvent;

public class BugFix8Utils {
    static public void disableContextMenu(TextField textField) {
        textField.addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, ContextMenuEvent::consume);
    }
}
