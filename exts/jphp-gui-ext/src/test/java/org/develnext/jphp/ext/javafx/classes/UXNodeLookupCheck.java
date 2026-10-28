package org.develnext.jphp.ext.javafx.classes;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SubScene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Standalone JavaFX oracle. Run on an isolated desktop with the baseline and candidate GUI classes. */
public final class UXNodeLookupCheck {
    private static final List<String> results = new ArrayList<>();
    private static int sink;

    private static final class LookupPane extends Pane {
        private final Node virtual = named(new Pane(), "virtual", "virtual-override");
        @Override public Node lookup(String selector) {
            return "#virtual".equals(selector) || "#custom".equals(selector) ? virtual : super.lookup(selector);
        }
    }

    private static final class LookupShape extends javafx.scene.shape.Rectangle {
        private final Node virtual = named(new Pane(), "shapeVirtual", "shape-virtual-override");
        @Override public Node lookup(String selector) {
            return "#shapeVirtual".equals(selector) ? virtual : super.lookup(selector);
        }
    }

    private static <T extends Node> T named(T node, String id, String label) {
        node.setId(id); node.getProperties().put("lookup-check-name", label); return node;
    }

    private static void check(String test, Node root, String selector) {
        try {
            Node found = UXNode.__globalLookup(root, selector);
            results.add(test + "=" + (found == null ? "null" : found.getProperties().getOrDefault("lookup-check-name", found.getClass().getSimpleName())));
        } catch (RuntimeException failure) {
            results.add(test + "=" + failure.getClass().getName());
        }
    }

    private static void run(Path output) throws Exception {
        Pane first = named(new Pane(), "duplicate", "first");
        Pane second = named(new Pane(), "duplicate", "second");
        Pane branch = new Pane(first); Pane root = named(new VBox(branch, second), "root", "root");
        check("null", null, "#duplicate"); check("self", root, "#root");
        check("preorder-duplicate", root, "#duplicate"); check("missing", root, "#absent");
        first.setId("renamed"); check("rename-old", root, "#duplicate"); check("rename-new", root, "#renamed");
        branch.getChildren().remove(first); root.getChildren().add(first); check("reparent", root, "#renamed");
        root.getChildren().remove(second); check("removed", root, "#duplicate");
        first.getStyleClass().add("marked"); check("class-selector", root, ".marked");
        check("compound-selector", root, "#renamed.marked"); check("descendant-selector", root, "#root #renamed");
        first.setId("colon:id"); check("escaped-selector", root, "#colon\\:id");
        first.setId("123"); check("numeric-selector", root, "#123");
        first.setId("узел"); check("unicode-selector", root, "#узел");

        Pane scrollChild = named(new Pane(), "scrollChild", "scroll-child");
        ScrollPane scroll = new ScrollPane(new Pane(scrollChild));
        Pane titledChild = named(new Pane(), "titledChild", "titled-child");
        TitledPane titled = new TitledPane("title", new Pane(titledChild)); titled.setExpanded(false);
        Pane splitChild = named(new Pane(), "splitChild", "split-child");
        SplitPane split = new SplitPane(new Pane(splitChild));
        Pane tabChild = named(new Pane(), "tabChild", "unselected-tab-child");
        TabPane tabs = new TabPane(new Tab("one", new Pane()), new Tab("two", new Pane(tabChild)));
        Pane direct = named(new Pane(), "duplicate", "attached-priority");
        scrollChild.setId("duplicate"); root.getChildren().setAll(scroll, titled, split, tabs, direct);
        for (boolean skinned : new boolean[]{false, true}) {
            Stage stage = null;
            if (skinned) { stage = new Stage(); stage.setScene(new Scene(root, 640, 480)); stage.show(); root.applyCss(); root.layout(); }
            String state = skinned ? "skinned-" : "unskinned-";
            check(state + "attached-before-logical", root, "#duplicate");
            check(state + "scroll", root, "#scrollChild"); check(state + "titled", root, "#titledChild");
            check(state + "split", root, "#splitChild"); check(state + "tab", root, "#tabChild");
            check(state + "type", root, "ScrollPane");
            if (stage != null) stage.close();
        }
        scrollChild.setId("scrollChild"); check("detached-rename", root, "#scrollChild");
        tabs.getTabs().get(1).setContent(new Pane(named(new Pane(), "tabChild", "replacement-tab-child")));
        check("replace-tab", root, "#tabChild");
        scroll.setContent(new Pane(named(new Pane(), "scrollChild", "replacement-scroll-child")));
        check("replace-scroll", root, "#scrollChild");
        titled.setContent(new Pane(named(new Pane(), "titledChild", "replacement-titled-child")));
        check("replace-titled", root, "#titledChild");
        split.getItems().clear(); check("remove-split-item", root, "#splitChild");
        SubScene subScene = named(new SubScene(new Pane(named(new Pane(), "subChild", "sub-child")), 40, 40), "subScene", "sub-scene");
        root.getChildren().setAll(subScene);
        check("sub-scene-self", root, "#subScene"); check("sub-scene-boundary", root, "#subChild");
        LookupPane custom = named(new LookupPane(), "custom", "custom-root");
        check("custom-root-override", custom, "#custom"); check("custom-virtual", custom, "#virtual");
        root.getChildren().setAll(custom); check("custom-nested-override", root, "#virtual");
        LookupShape customNode = new LookupShape();
        check("custom-node-root", customNode, "#shapeVirtual");
        root.getChildren().setAll(customNode); check("custom-node-nested", root, "#shapeVirtual");
        if (Boolean.getBoolean("devline.lookup.check.skipBenchmark")) {
            Files.write(output, results); System.out.println("LOOKUP_ORACLE checks=" + results.size()); return;
        }

        Pane large = new VBox();
        for (int i = 0; i < 40; i++) { Pane row = new HBox(); for (int j = 0; j < 16; j++) row.getChildren().add(named(new Pane(), "node_" + i + "_" + j, "hit")); large.getChildren().add(row); }
        String[] queries = {"#node_39_15", "#node_30_8", "#node_20_7", "#node_10_6"};
        for (int i = 0; i < 4000; i++) sink ^= System.identityHashCode(UXNode.__globalLookup(large, queries[i & 3]));
        long[] rounds = new long[5];
        for (int round = 0; round < rounds.length; round++) {
            long began = System.nanoTime();
            for (int i = 0; i < 10000; i++) sink ^= System.identityHashCode(UXNode.__globalLookup(large, queries[i & 3]));
            rounds[round] = System.nanoTime() - began;
        }
        Arrays.sort(rounds); Files.write(output, results);
        System.out.println("LOOKUP_ORACLE checks=" + results.size() + " median10000Ms=" + rounds[2] / 1_000_000.0 + " sink=" + sink);
    }

    public static void main(String[] args) throws Exception {
        if (!System.getenv().getOrDefault("DEVLINE_NATIVE_QA_DESKTOP", "").startsWith("DevLine-QA-")) throw new IllegalStateException("Isolated QA desktop required");
        CompletableFuture<Void> result = new CompletableFuture<>();
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            Platform.runLater(() -> { try { run(Path.of(args[0])); result.complete(null); } catch (Throwable failure) { result.completeExceptionally(failure); } });
        });
        try { result.get(); } finally { Platform.exit(); }
    }
}
