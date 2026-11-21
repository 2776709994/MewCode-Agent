package com.mewcode;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/** A bounded event stream whose consumer can interrupt an abandoned producer. */
public final class EventQueue<T> extends LinkedBlockingQueue<T> {
    private Thread producer;

    public EventQueue() {
        super(65);
    }

    public void start(Runnable task) {
        producer = Thread.startVirtualThread(task);
    }

    public void finish(T terminalEvent) {
        if (Thread.currentThread().isInterrupted() && remainingCapacity() == 0) {
            poll();
        }
        if (offer(terminalEvent)) return;
        try {
            put(terminalEvent);
        } catch (InterruptedException e) {
            poll();
            offer(terminalEvent);
            Thread.currentThread().interrupt();
        }
    }

    public static void cancel(BlockingQueue<?> queue) {
        if (queue instanceof EventQueue<?> events && events.producer != null) {
            events.producer.interrupt();
        }
    }
}
