package com.genesis.p2p.core.handlers;

/**
 * Status of the message processing queue.
 */
public record QueueStatus(int size, int capacity, int remaining) {
    public double getUtilization() {
        if (capacity == 0) return 0.0;
        return (double) size / capacity * 100.0;
    }
}