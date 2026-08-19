package com.cycletrading.util;

import java.util.Comparator;
import java.util.PriorityQueue;

/** 统一到期队列：按 matureAt 升序，供全局线程轮询结算。 */
public final class MaturityQueue<T extends Matures> {

    private final PriorityQueue<T> queue = new PriorityQueue<>(Comparator.comparingLong(Matures::matureAt));

    public void add(T item) {
        queue.add(item);
    }

    public boolean remove(T item) {
        return queue.remove(item);
    }

    public T peek() {
        return queue.peek();
    }

    public T poll() {
        return queue.poll();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public int size() {
        return queue.size();
    }
}
