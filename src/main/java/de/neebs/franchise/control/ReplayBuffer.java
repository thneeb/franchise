package de.neebs.franchise.control;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

class ReplayBuffer<T> {

    private final int capacity;
    private final List<T> items = new ArrayList<>();
    private final Random random = new Random();

    ReplayBuffer(int capacity) {
        this.capacity = capacity;
    }

    synchronized void add(T item) {
        if (items.size() == capacity) {
            items.remove(0);
        }
        items.add(item);
    }

    synchronized void addAll(List<T> newItems) {
        for (T item : newItems) {
            add(item);
        }
    }

    synchronized List<T> sample(int batchSize) {
        if (items.isEmpty()) return List.of();
        List<T> copy = new ArrayList<>(items);
        Collections.shuffle(copy, random);
        return new ArrayList<>(copy.subList(0, Math.min(batchSize, copy.size())));
    }
}
