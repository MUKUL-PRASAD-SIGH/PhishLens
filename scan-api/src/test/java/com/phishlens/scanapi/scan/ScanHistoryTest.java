package com.phishlens.scanapi.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.phishlens.scanapi.scoring.Verdict;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ScanHistoryTest {

    private final ScanHistory history = new ScanHistory();

    private static ScanRecord record(int n) {
        return new ScanRecord("http://example.com/" + n, new BigDecimal("0.10"), Verdict.SAFE,
                ScanRecord.SOURCE_FALLBACK, ScanRecord.FALLBACK_MODEL_VERSION, Instant.EPOCH.plusSeconds(n));
    }

    @Test
    void readsNewestFirst() {
        for (int n = 1; n <= 3; n++) {
            history.add(record(n));
        }
        assertThat(history.recent(10)).extracting(ScanRecord::url)
                .containsExactly("http://example.com/3", "http://example.com/2", "http://example.com/1");
        assertThat(history.recent(2)).extracting(ScanRecord::url)
                .containsExactly("http://example.com/3", "http://example.com/2");
    }

    @Test
    void keepsOnlyTheLastHundred() {
        for (int n = 1; n <= 150; n++) {
            history.add(record(n));
        }
        List<ScanRecord> all = history.recent(1000);
        assertThat(all).hasSize(ScanHistory.CAPACITY);
        assertThat(all.get(0).url()).isEqualTo("http://example.com/150");
        assertThat(all.get(99).url()).isEqualTo("http://example.com/51");
    }

    @Test
    void returnsAnImmutableSnapshot() {
        history.add(record(1));
        List<ScanRecord> snapshot = history.recent(10);
        history.add(record(2));
        assertThat(snapshot).hasSize(1);
        assertThatThrownBy(() -> snapshot.add(record(3))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void concurrentWritesAndReadsAreSafe() throws Exception {
        int writers = 8;
        int perWriter = 1000;
        ExecutorService pool = Executors.newFixedThreadPool(writers + 2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            int base = w * perWriter;
            tasks.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perWriter; i++) {
                    history.add(record(base + i));
                }
                return null;
            }));
        }
        for (int r = 0; r < 2; r++) {
            tasks.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 2000; i++) {
                    assertThat(history.recent(ScanHistory.CAPACITY).size()).isLessThanOrEqualTo(ScanHistory.CAPACITY);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> task : tasks) {
            task.get(30, TimeUnit.SECONDS);  // rethrows any exception from the worker
        }
        pool.shutdown();

        List<ScanRecord> all = history.recent(1000);
        assertThat(all).hasSize(ScanHistory.CAPACITY).doesNotContainNull().doesNotHaveDuplicates();
    }
}
