package org.develnext.jphp.ext.game.support;

import javafx.application.Platform;
import javafx.scene.layout.Pane;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Deterministic pulses, without displaying a window or depending on monitor refresh rate. */
public final class GameSceneTimingCheck {
    public static void main(String[] args) throws Exception {
        String desktop = System.getenv("DEVLINE_NATIVE_QA_DESKTOP");
        if (desktop == null || !desktop.startsWith("DevLine-QA-")) {
            throw new IllegalStateException("Use tools/native-qa-desktop.ps1 for this JavaFX check");
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        Platform.startup(() -> {
            try {
                checkScene();
                result.complete(null);
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        try {
            result.get(15, TimeUnit.SECONDS);
            System.out.println("GAME_SCENE_REFRESH_INDEPENDENCE_CAMERA_PAUSE_OWNERSHIP_PASS");
        } finally {
            Platform.exit();
        }
    }

    private static void checkScene() throws Exception {
        Method pulse = GameScene2D.class.getDeclaredMethod("processUpdate", long.class);
        pulse.setAccessible(true);
        for (int hz : new int[] {30, 60, 144}) {
            GameScene2D scene = new GameScene2D();
            GameEntity2D moving = entity("moving", 0, 0);
            moving.setBodyType(GameEntity2D.BodyType.KINEMATIC);
            moving.setVelocityX(1);
            scene.addEntity(moving);
            pulse.invoke(scene, 1L);
            for (int i = 1; i <= hz; i++) pulse.invoke(scene, 1L + Math.round(i * 1_000_000_000.0 / hz));
            near(moving.getX(), 20, "Motion depends on refresh rate " + hz);
            scene.clear();
        }

        GameScene2D scene = new GameScene2D();
        GameEntity2D player = entity("player", 50, 30);
        GameEntity2D other = entity("other", 500, 300);
        scene.addEntity(player);
        scene.addEntity(other);
        scene.setObservedObject(player);
        int[] scrolls = {0};
        scene.setScrollHandler((x, y) -> {
            scrolls[0]++;
            near(x, player.getCenterX(), "Camera follows another entity X");
            near(y, player.getCenterY(), "Camera follows another entity Y");
        });
        pulse.invoke(scene, 1L);
        pulse.invoke(scene, 16_666_668L);
        if (scrolls[0] != 1) throw new AssertionError("Camera must update once per pulse");
        scene.pause();
        player.setBodyType(GameEntity2D.BodyType.KINEMATIC);
        player.setVelocityX(-0.01);
        pulse.invoke(scene, 5_000_000_001L);
        near(player.getX(), 50, "Pause accumulated elapsed time");
        pulse.invoke(scene, 5_016_666_668L);
        near(player.getX(), 50 - 0.01 * 20 / 60, "Small negative velocity was discarded");

        double beforeStall = player.getX();
        pulse.invoke(scene, 15_016_666_668L);
        near(player.getX(), beforeStall - 0.01 * 20 / 60 * 8, "Unbounded catch-up after stall");
        GameScene2D foreign = new GameScene2D();
        foreign.removeEntity(player);
        if (player.getScene() != scene) throw new AssertionError("Foreign scene removed entity ownership");
        scene.removeEntity(player);
        if (scene.getObservedObject() != null) throw new AssertionError("Removed observed entity retained");
        scene.clear();
        foreign.clear();
    }

    private static GameEntity2D entity(String name, double x, double y) {
        Pane node = new Pane();
        node.resize(10, 10);
        node.setLayoutX(x);
        node.setLayoutY(y);
        return new GameEntity2D(name, node);
    }

    private static void near(double actual, double expected, String message) {
        if (Math.abs(actual - expected) > 0.00001) {
            throw new AssertionError(message + ": expected " + expected + ", actual " + actual);
        }
    }
}
