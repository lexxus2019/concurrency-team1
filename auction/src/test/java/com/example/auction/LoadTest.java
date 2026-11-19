package com.example.auction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Загрузочный тест: измеряет быстродействие аукциона.
 *
 * Корректность здесь не проверяется (для этого есть RaceTest) — тест печатает
 * пропускную способность, чтобы видеть цену синхронизации в следующих ветках.
 *
 * Запуск: mvn -pl auction test -Dtest=LoadTest
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LoadTest {

    private static final int SEQUENTIAL_OPS = 2000;
    private static final int CONCURRENT_OPS = 2000;
    private static final int THREADS = 100;

    private final ExecutorService pool = Executors.newFixedThreadPool(THREADS);

    @Autowired
    TestRestTemplate rest;

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    private void addItem(int n) {
        rest.postForEntity("/api/items", new NewItem("load-" + n, 100), Item.class);
    }

    @Test
    @Order(1)
    @DisplayName("Пропускная способность ставок на один лот: 100 потоков")
    void bidThroughput() throws Exception {
        long itemId = rest.postForEntity("/api/items", new NewItem("load-лот", 1000), Item.class)
                .getBody().id();

        int operations = 1000;
        CountDownLatch done = new CountDownLatch(operations);
        List<Integer> accepted = Collections.synchronizedList(new ArrayList<>());
        long start = System.nanoTime();
        for (int i = 0; i < operations; i++) {
            final int n = i;
            pool.execute(() -> {
                try {
                    var response = rest.postForEntity("/api/items/" + itemId + "/bids",
                            new NewBid("bidder-" + n, 1001L + n), Bid.class);
                    if (response.getStatusCode().is2xxSuccessful()) {
                        accepted.add(n);
                    }
                } catch (Exception ignored) {
                    // на битом main часть ставок может отваливаться с 500 — это нормально
                } finally {
                    done.countDown();
                }
            });
        }
        done.await(120, TimeUnit.SECONDS);
        long nanos = System.nanoTime() - start;

        System.out.printf("=== LoadTest addBid: %d потоков, %d ставок за %.2f с (~%.0f шт/сек), принято %d%n",
                THREADS, operations, nanos / 1e9, operations * 1e9 / nanos, accepted.size());
    }

    @Test
    @Order(2)
    @DisplayName("Пропускная способность добавления товаров: последовательно и в 100 потоков")
    void addItemThroughput() throws Exception {
        // прогрев, чтобы JIT не портил первую цифру
        for (int i = 0; i < 100; i++) {
            addItem(i);
        }

        List<Long> latencies = new ArrayList<>();
        long sequentialStart = System.nanoTime();
        for (int i = 0; i < SEQUENTIAL_OPS; i++) {
            long one = System.nanoTime();
            addItem(SEQUENTIAL_OPS + i);
            latencies.add(System.nanoTime() - one);
        }
        long sequentialNanos = System.nanoTime() - sequentialStart;

        long median = latencies.stream().mapToLong(Long::longValue).sorted()
                .skip(SEQUENTIAL_OPS / 2).findFirst().orElse(0);

        System.out.printf("=== LoadTest addItem: последовательно %d запросов за %.2f с (~%.0f шт/сек), медиана %.2f мс%n",
                SEQUENTIAL_OPS, sequentialNanos / 1e9, SEQUENTIAL_OPS * 1e9 / sequentialNanos, median / 1e6);

        CountDownLatch done = new CountDownLatch(CONCURRENT_OPS);
        long concurrentStart = System.nanoTime();
        for (int i = 0; i < CONCURRENT_OPS; i++) {
            final int n = i;
            pool.execute(() -> {
                try {
                    addItem(100_000 + n);
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        if (!done.await(120, TimeUnit.SECONDS)) {
            System.out.println("=== LoadTest addItem: часть потоков не завершилась за 120 секунд");
        }
        long concurrentNanos = System.nanoTime() - concurrentStart;

        System.out.printf("=== LoadTest addItem: %d потоков, %d запросов за %.2f с (~%.0f шт/сек)%n",
                THREADS, CONCURRENT_OPS, concurrentNanos / 1e9, CONCURRENT_OPS * 1e9 / concurrentNanos);
    }
}
