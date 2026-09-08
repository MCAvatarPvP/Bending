package com.projectkorra.projectkorra.fabric.client.prediction.action;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class InputAbilityCreationsTest {
    @Test
    void delayedWallAcknowledgementPreservesEveryColumnAfterControllerFinishes() {
        final Node wall = new Node("RaiseEarth", null);
        final InputAbilityCreations<Node> input = new InputAbilityCreations<>();
        input.add(wall);
        // Only the columns are still live when the server's input reply arrives.
        final List<Node> columns = java.util.stream.IntStream.range(0, 7)
                .mapToObj(index -> new Node("RaiseEarth", wall)).toList();
        final Set<Node> rejected = input.rejected(List.of("RaiseEarth"), Node::name, ignored -> false);
        for (Node column : columns) {
            assertFalse(InputAbilityCreations.descendsFrom(column, rejected, Node::parent));
        }
        assertEquals(1, input.counts(Node::name).get("raiseearth"));
    }

    @Test
    void rejectedWallRemovesItsDescendantsEvenWhenTheirNamesDiffer() {
        final Node root = new Node("Shockwave", null);
        final Node child = new Node("Ripple", root);
        final Node grandchild = new Node("RaiseEarth", child);
        final Node unrelated = new Node("RaiseEarth", null);
        final InputAbilityCreations<Node> input = new InputAbilityCreations<>();
        input.add(root);
        final Set<Node> rejected = input.rejected(List.of(), Node::name, ignored -> false);
        assertTrue(InputAbilityCreations.descendsFrom(grandchild, rejected, Node::parent));
        assertFalse(InputAbilityCreations.descendsFrom(unrelated, rejected, Node::parent));
    }

    @Test
    void acceptedFinishedRootStillConsumesItsReceiptBeforeAnotherSameNameRoot() {
        final Node first = new Node("EarthSmash", null);
        final Node second = new Node("EarthSmash", null);
        final InputAbilityCreations<Node> input = new InputAbilityCreations<>();
        input.add(first);
        input.add(first);
        input.add(second);
        final Set<Node> rejected = input.rejected(List.of("EarthSmash"), Node::name, ignored -> false);
        assertEquals(1, rejected.size());
        assertTrue(rejected.contains(second));
    }

    private record Node(String name, Node parent) { }
}
