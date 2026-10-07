package com.livingmods.sidecar;

import com.livingmods.common.event.CivilizationEventType;
import com.livingmods.protocol.EventPayload;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded sidecar outbound event queue with drop/coalesce policy.
 * Same-type events for the same region coalesce (latest wins); overflow drops oldest.
 */
public final class BoundedEventQueue {
    public static final int DEFAULT_CAPACITY = 512;

    private final int capacity;
    private final ArrayDeque<EventPayload> queue = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();
    private long dropped;

    public BoundedEventQueue() {
        this(DEFAULT_CAPACITY);
    }

    public BoundedEventQueue(int capacity) {
        this.capacity = Math.max(16, capacity);
    }

    public void offer(EventPayload payload) {
        if (payload == null) {
            return;
        }
        lock.lock();
        try {
            coalesceSameRegionType(payload);
            while (queue.size() >= capacity) {
                queue.pollFirst();
                dropped++;
            }
            queue.addLast(payload);
        } finally {
            lock.unlock();
        }
    }

    public EventPayload poll() {
        lock.lock();
        try {
            return queue.pollFirst();
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    public long dropped() {
        return dropped;
    }

    private void coalesceSameRegionType(EventPayload incoming) {
        CivilizationEventType type = incoming.eventType();
        if (type == CivilizationEventType.SIDECAR_DEGRADED) {
            // Keep all degradation events distinct.
            return;
        }
        Iterator<EventPayload> it = queue.iterator();
        while (it.hasNext()) {
            EventPayload existing = it.next();
            if (existing.eventType() == type
                    && existing.regionX() == incoming.regionX()
                    && existing.regionZ() == incoming.regionZ()) {
                it.remove();
                // Preserve diagnostic data keys from the prior event when missing on the new one.
                if (incoming.data() != null && existing.data() != null) {
                    for (Map.Entry<String, String> e : existing.data().entrySet()) {
                        incoming.data().putIfAbsent(e.getKey(), e.getValue());
                    }
                }
                break;
            }
        }
    }
}
