package org.develnext.jphp.ext.javafx.support.control;

import javafx.beans.binding.Bindings;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.SubScene;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class FragmentPane extends VBox {
    private Parent root;

    public FragmentPane() {
        super();
        getStyleClass().add("fragment-pane");
    }

    public FragmentPane(double spacing) {
        super(spacing);
    }

    public FragmentPane(Node... children) {
        super(children);
    }

    public FragmentPane(double spacing, Node... children) {
        super(spacing, children);
    }

    public void applyFragment(Stage form) {
        for (Node child : getChildren()) {
            if (child instanceof SubScene scene) {
                scene.widthProperty().unbind();
                scene.heightProperty().unbind();
            }
        }
        this.getChildren().clear();
        root = null;

        if (form != null) {
            root = form.getScene().getRoot();
            form.getScene().setRoot(new Pane());

            double width = getPrefWidth(), height = getPrefHeight();

            SubScene scene = new SubScene(root, width, height);

            // A full-size child plus VBox insets feeds its minimum size back into the host.
            if (getMinWidth() == Region.USE_COMPUTED_SIZE) setMinWidth(0);
            if (getMinHeight() == Region.USE_COMPUTED_SIZE) setMinHeight(0);
            scene.widthProperty().bind(Bindings.createDoubleBinding(
                    () -> Math.max(0, getWidth() - getInsets().getLeft() - getInsets().getRight()), widthProperty(), insetsProperty()));
            scene.heightProperty().bind(Bindings.createDoubleBinding(
                    () -> Math.max(0, getHeight() - getInsets().getTop() - getInsets().getBottom()), heightProperty(), insetsProperty()));

            VBox.setVgrow(scene, Priority.ALWAYS);

            this.getChildren().add(scene);
        }
    }

    public Parent getLayout() {
        return root;
    }
}
