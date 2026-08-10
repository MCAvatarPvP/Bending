package com.projectkorra.projectkorra.airbending;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TornadoAirRightClickBoundaryTest {
    @Test
    void preCancelledAirClickStillReachesOnlyAnActiveTornado() throws IOException {
        Path source = Path.of("src/main/java/com/projectkorra/projectkorra/PKListener.java");
        if (!Files.exists(source)) {
            source = Path.of("bukkit/src/main/java/com/projectkorra/projectkorra/PKListener.java");
        }
        assertTrue(Files.exists(source), source.toString());

        final String listener = Files.readString(source);
        final String airHandler = method(listener,
                "public void onCancelledTornadoAirInteraction",
                "public void onPlayerInteraction");

        assertTrue(listener.contains("@EventHandler(priority = EventPriority.LOWEST)\n"
                        + "    public void onCancelledTornadoAirInteraction")
                        || listener.contains("@EventHandler(priority = EventPriority.LOWEST)\r\n"
                        + "    public void onCancelledTornadoAirInteraction"));
        assertTrue(airHandler.contains("event.isCancelled()")
                        && airHandler.contains("Action.RIGHT_CLICK_AIR")
                        && airHandler.contains("EquipmentSlot.HAND"),
                "the fallback should accept Bukkit's pre-cancelled main-hand air input");
        assertTrue(airHandler.contains("CoreAbility.hasAbility(player, Tornado.class)"),
                "cancelled input must not be reopened for unrelated abilities");
        assertTrue(airHandler.contains("PaperPredictionServer.handleRightClick")
                        && airHandler.contains("CommonInputHandler.handleRightClick(player, ClickType.RIGHT_CLICK)"),
                "air input should retain the normal authoritative activation and prediction path");
        assertFalse(airHandler.contains("Action.RIGHT_CLICK_BLOCK"),
                "cancelled block interaction must remain protected");
    }

    private static String method(final String source, final String startMarker, final String endMarker) {
        final int start = source.indexOf(startMarker);
        final int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0 && end > start,
                "missing method boundary " + startMarker + " -> " + endMarker);
        return source.substring(start, end);
    }
}
