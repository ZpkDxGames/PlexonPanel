package io.github.zpkdxgames.plexonpanel.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public final class BoundedRingBuffer<T> {
    private final int capacity;
    private final ArrayDeque<T> entries;

    public BoundedRingBuffer(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.entries = new ArrayDeque<>(capacity);
    }

    public synchronized void add(T value) {
        if (entries.size() == capacity) {
            entries.removeFirst();
        }
        entries.addLast(value);
    }

    public synchronized List<T> snapshot() {
        return List.copyOf(entries);
    }

    public synchronized List<T> drain(int maximum) {
        int count = Math.min(Math.max(maximum, 0), entries.size());
        List<T> drained = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            drained.add(entries.removeFirst());
        }
        return drained;
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void clear() {
        entries.clear();
    }
}
