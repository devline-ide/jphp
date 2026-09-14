package org.develnext.jphp.ext.gui.desktop.classes;

import javafx.geometry.Rectangle2D;
import javafx.scene.image.Image;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.stage.Screen;
import org.develnext.jphp.ext.gui.desktop.GuiDesktopExtension;
import org.develnext.jphp.ext.javafx.support.JavaFXRuntime;
import php.runtime.annotation.Reflection;
import php.runtime.annotation.Reflection.Getter;
import php.runtime.annotation.Reflection.Nullable;
import php.runtime.annotation.Reflection.Setter;
import php.runtime.annotation.Reflection.Signature;
import php.runtime.common.StringUtils;
import php.runtime.env.Environment;
import php.runtime.lang.BaseObject;
import php.runtime.lang.BaseWrapper;
import php.runtime.reflection.ClassEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Reflection.Namespace(GuiDesktopExtension.NS)
public class Robot extends BaseWrapper<javafx.scene.robot.Robot> {
    private static javafx.scene.robot.Robot sharedRobot;

    interface WrappedInterface {

    }

    public Robot(Environment env, ClassEntity clazz) {
        super(env, clazz);
    }

    public Robot(Environment env, javafx.scene.robot.Robot wrappedObject) {
        super(env, wrappedObject);
    }

    @Signature
    public void __construct() {
        __wrappedObject = getSharedRobot();
    }

    private static javafx.scene.robot.Robot getSharedRobot() {
        return JavaFXRuntime.runAndWait(() -> {
            if (sharedRobot == null) {
                sharedRobot = new javafx.scene.robot.Robot();
            }

            return sharedRobot;
        });
    }

    static int mouseX() {
        return JavaFXRuntime.runAndWait(() -> (int) getSharedRobot().getMouseX());
    }

    static int mouseY() {
        return JavaFXRuntime.runAndWait(() -> (int) getSharedRobot().getMouseY());
    }

    @Getter
    public int getX() {
        return mouseX();
    }

    @Setter
    public void setX(int x) {
        JavaFXRuntime.runAndWait(() -> {
            getWrappedObject().mouseMove(x, getWrappedObject().getMouseY());
            return null;
        });
    }

    @Getter
    public int getY() {
        return mouseY();
    }

    @Setter
    public void setY(int y) {
        JavaFXRuntime.runAndWait(() -> {
            getWrappedObject().mouseMove(getWrappedObject().getMouseX(), y);
            return null;
        });
    }

    @Getter
    public int[] getPosition() {
        return JavaFXRuntime.runAndWait(() -> new int[] {
                (int) getWrappedObject().getMouseX(),
                (int) getWrappedObject().getMouseY()
        });
    }

    @Setter
    public void setPosition(int[] pos) {
        if (pos.length >= 2) {
            JavaFXRuntime.runAndWait(() -> {
                getWrappedObject().mouseMove(pos[0], pos[1]);
                return null;
            });
        }
    }

    @Signature
    public void mouseClick() {
        mouseClick(MouseButton.PRIMARY);
    }

    @Signature
    public void mouseClick(MouseButton button) {
        mouseDown(button);
        mouseUp(button);
    }

    @Signature
    public void mouseDown() {
        mouseDown(MouseButton.PRIMARY);
    }

    @Signature
    public void mouseDown(MouseButton button) {
        JavaFXRuntime.runAndWait(() -> {
            getWrappedObject().mousePress(button);
            return null;
        });
    }

    @Signature
    public void mouseUp() {
        mouseUp(MouseButton.PRIMARY);
    }

    @Signature
    public void mouseUp(MouseButton button) {
        JavaFXRuntime.runAndWait(() -> {
            getWrappedObject().mouseRelease(button);
            return null;
        });
    }

    @Signature
    public void mouseScroll(int wheelAmt) {
        JavaFXRuntime.runAndWait(() -> {
            getWrappedObject().mouseWheel(wheelAmt);
            return null;
        });
    }

    @Signature
    public void type(String characters) {
        JavaFXRuntime.runAndWait(() -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(characters);
            Clipboard.getSystemClipboard().setContent(content);

            getWrappedObject().keyPress(KeyCode.CONTROL);
            getWrappedObject().keyPress(KeyCode.V);
            getWrappedObject().keyRelease(KeyCode.V);
            getWrappedObject().keyRelease(KeyCode.CONTROL);
            return null;
        });
    }

    protected List<KeyCode> keyCodes(String keys) {
        String[] strings = StringUtils.split(keys, '+');

        List<KeyCode> codes = new ArrayList<>();

        for (String string : strings) {
            string = string.trim().replace(' ', '_').replace('-', '_').toUpperCase();
            KeyCode matched = null;

            for (KeyCode code : KeyCode.values()) {
                String name = code.getName().replace(' ', '_').replace('-', '_').toUpperCase();

                if (code.name().equals(string) || name.equals(string)) {
                    matched = code;
                    break;
                }
            }

            if (matched == null) {
                throw new IllegalArgumentException("Unknown key - " + string);
            }

            codes.add(matched);
        }

        if (codes.isEmpty()) {
            throw new IllegalArgumentException("Key combination cannot be empty");
        }

        return codes;
    }

    @Signature
    public void keyDown(String keys) {
        JavaFXRuntime.runAndWait(() -> {
            for (KeyCode code : keyCodes(keys)) {
                getWrappedObject().keyPress(code);
            }
            return null;
        });
    }

    @Signature
    public void keyUp(String keys) {
        JavaFXRuntime.runAndWait(() -> {
            for (KeyCode code : keyCodes(keys)) {
                getWrappedObject().keyRelease(code);
            }
            return null;
        });
    }

    @Signature
    public void keyPress(String keys) {
        JavaFXRuntime.runAndWait(() -> {
            List<KeyCode> codes = keyCodes(keys);

            for (KeyCode code : codes) {
                getWrappedObject().keyPress(code);
            }

            Collections.reverse(codes);

            for (KeyCode code : codes) {
                getWrappedObject().keyRelease(code);
            }
            return null;
        });
    }

    @Signature
    public Image screenshot() {
        return screenshot(null);
    }

    @Signature
    public Image screenshot(@Nullable Rectangle2D bounds) {
        return screenshot(bounds, null);
    }

    @Signature
    public Image screenshot(@Nullable Rectangle2D bounds, @Nullable Screen screen) {
        final Rectangle2D requestedBounds = bounds;
        final Screen requestedScreen = screen;

        return JavaFXRuntime.runAndWait(() -> {
            Screen actualScreen = requestedScreen == null ? Screen.getPrimary() : requestedScreen;
            Rectangle2D actualBounds = requestedBounds == null ? actualScreen.getVisualBounds() : requestedBounds;

            return getWrappedObject().getScreenCapture(
                    null,
                    actualBounds.getMinX(),
                    actualBounds.getMinY(),
                    actualBounds.getWidth(),
                    actualBounds.getHeight(),
                    false
            );
        });
    }

    protected Image screenshotArea(int x, int y, int width, int height) {
        return JavaFXRuntime.runAndWait(() ->
                getWrappedObject().getScreenCapture(null, x, y, width, height, false)
        );
    }
}
