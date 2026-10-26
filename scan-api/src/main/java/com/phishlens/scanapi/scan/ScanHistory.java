package com.phishlens.scanapi.scan;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.springframework.stereotype.Component;

/** The last {@value #CAPACITY} scans, in memory, newest first. Thread-safe. */
@Component
public class ScanHistory {

    public static final int CAPACITY = 100;

    private final Deque<ScanRecord> records = new ArrayDeque<>(CAPACITY + 1);

    public synchronized void add(ScanRecord record) {
        records.addFirst(record);
        if (records.size() > CAPACITY) {
            records.removeLast();
        }
    }

    /** Up to {@code limit} records, newest first, as an immutable snapshot. */
    public synchronized List<ScanRecord> recent(int limit) {
        return records.stream().limit(limit).toList();
    }
}
