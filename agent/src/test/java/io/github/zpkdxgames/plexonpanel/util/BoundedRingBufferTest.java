package io.github.zpkdxgames.plexonpanel.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BoundedRingBufferTest {
    @Test
    void evictsOldestEntriesAndDrainsInOrder() {
        BoundedRingBuffer<Integer> buffer = new BoundedRingBuffer<>(3);
        buffer.add(1);
        buffer.add(2);
        buffer.add(3);
        buffer.add(4);

        assertEquals(List.of(2, 3, 4), buffer.snapshot());
        assertEquals(List.of(2, 3), buffer.drain(2));
        assertEquals(List.of(4), buffer.snapshot());
    }
}
