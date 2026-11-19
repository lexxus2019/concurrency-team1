package com.example.auction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.assertj.core.api.SoftAssertions;
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
import org.springframework.web.client.RestClientException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Тесты на гонки данных.
 *
 * В ветке main аукцион намеренно НЕ потокобезопасен, поэтому эти тесты
 * в main КРАСНЫЕ — это и есть учебный результат: они показывают, какие
 * именно данные портятся под параллельной нагрузкой.
 *
 * Запуск: mvn -pl auction test
 * Цель следующих веток — починить код так, чтобы RaceTest стал зелёным.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RaceTest {

    private static final int THREADS = 200;
    // put'ы в HashMap теряются не в каждый запуск: берём больше потоков и раундов,
    // чтобы тест на main был красным надёжно, а не «иногда»
    private static final int ADD_THREADS = 500;
    private static final int ADD_ROUNDS = 3;

    private final ExecutorService pool = Executors.newFixedThreadPool(ADD_THREADS);

    @Autowired
    TestRestTemplate rest;

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    /** GET списка; null, если сервер не смог его отдать (хранилище уже повреждено). */
    private <T> List<T> getList(String url, Class<T[]> type) {
        try {
            var response = rest.getForEntity(url, type);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return Arrays.asList(response.getBody());
            }
            return null;
        } catch (RestClientException e) {
            return null;
        }
    }

    /** Повреждённая HashMap может отдать список с null-элементами — это тоже симптом гонки. */
    private static long countNulls(List<?> list) {
        return list.stream().filter(java.util.Objects::isNull).count();
    }

    /** Создать товар; при 500-ке от повреждённого хранилища — несколько повторов. */
    private long createItem(String name) {
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                var response = rest.postForEntity("/api/items", new NewItem(name, 1000), Item.class);
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    return response.getBody().id();
                }
            } catch (RestClientException ignored) {
            }
        }
        throw new IllegalStateException("Не удалось создать товар: хранилище повреждено предыдущим тестом");
    }

    /** Освобождаем стартовые ворота, пока основной поток ждёт результаты. */
    private void releaseAfter(CountDownLatch gate, long delayMillis) {
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            gate.countDown();
        });
    }

    @Test
    @Order(1)
    @DisplayName("Последовательные запросы работают всегда (для сравнения с параллельными)")
    void sequentialRequestsWork() {
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            Item item = rest.postForEntity("/api/items", new NewItem("seq-" + i, 100), Item.class).getBody();
            assertThat(item).isNotNull();
            ids.add(item.id());
        }
        assertThat(ids).hasSize(50);
    }

    @Test
    @Order(2)
    @DisplayName("200 покупателей одновременно перебивают ставку: каждый выигрывает ровно один раз, ставки не теряются")
    void concurrentBids() throws Exception {
        long itemId = createItem("лот");

        CountDownLatch gate = new CountDownLatch(1);
        List<Callable<Long>> tasks = IntStream.range(0, THREADS).mapToObj(i -> (Callable<Long>) () -> {
            gate.await();
            // упорный покупатель: повышаем ставку, пока её не примут
            for (long amount = 1001L + i; ; amount += THREADS) {
                var response = rest.postForEntity("/api/items/" + itemId + "/bids",
                        new NewBid("bidder-" + i, amount), Bid.class);
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    return response.getBody().id();
                }
                if (response.getStatusCode().value() != 400) {
                    throw new IllegalStateException("Неожиданный ответ: " + response.getStatusCode());
                }
            }
        }).toList();

        releaseAfter(gate, 200);
        var futures = pool.invokeAll(tasks, 60, TimeUnit.SECONDS);

        List<Long> wonBidIds = new ArrayList<>();
        for (var future : futures) {
            if (future.isCancelled()) {
                fail("Часть потоков не успела сделать ставку за 60 секунд");
            }
            try {
                wonBidIds.add(future.get());
            } catch (Exception e) {
                fail("Поток-покупатель упал с ошибкой (данные в хранилище уже испорчены): " + e.getMessage());
            }
        }

        List<Bid> storedBids = getList("/api/items/" + itemId + "/bids", Bid[].class);

        System.out.println("=== RaceTest.concurrentBids: победителей " + THREADS
                + ", id ставок в ответах уникальны: " + (wonBidIds.stream().distinct().count() == THREADS)
                + ", ставок в хранилище: " + (storedBids == null ? "хранилище не читается!" : storedBids.size()));

        SoftAssertions.assertSoftly(checks -> {
            checks.assertThat(wonBidIds)
                    .as("Каждый из %d покупателей выиграл ровно один раз", THREADS)
                    .hasSize(THREADS)
                    .doesNotHaveDuplicates();
            if (storedBids == null) {
                checks.fail("Список ставок на лот нельзя прочитать: HashMap повреждена настолько, что сервер падает на GET");
            } else {
                checks.assertThat(countNulls(storedBids))
                        .as("В списке ставок нет null-элементов: ArrayList повреждён изнутри")
                        .isZero();
                checks.assertThat(storedBids)
                        .as("В хранилище лежат все %d ставок: одновременный add в ArrayList теряет элементы", THREADS)
                        .hasSize(THREADS);
                checks.assertThat(storedBids.stream().filter(java.util.Objects::nonNull)
                        .map(Bid::amount).collect(Collectors.toSet()))
                        .as("Суммы всех принятых ставок уникальны")
                        .hasSize(THREADS);
                // инвариант atomicity проверки+вставки: ставки фиксируются по
                // возрастанию, каждая следующая обязана перебивать предыдущую
                boolean increasing = true;
                long previousAmount = 0;
                for (Bid bid : storedBids) {
                    if (bid == null) continue;
                    if (bid.amount() <= previousAmount) {
                        increasing = false;
                        break;
                    }
                    previousAmount = bid.amount();
                }
                checks.assertThat(increasing)
                        .as("Суммы ставок строго растут в списке: каждая ставка обязана была перебить "
                                + "ту, что уже лежит (check-then-act: проверка и вставка должны быть атомарными)")
                        .isTrue();
            }
        });
    }

    @Test
    @Order(3)
    @DisplayName("3 раунда по 500 параллельных добавлений товара: id уникальны, ни один товар не потерян")
    void concurrentAddItems() throws Exception {
        List<Item> itemsBefore = getList("/api/items", Item[].class);
        Set<Long> idsBefore = itemsBefore == null ? Set.of()
                : itemsBefore.stream().filter(java.util.Objects::nonNull).map(Item::id).collect(Collectors.toSet());

        int totalRequests = ADD_ROUNDS * ADD_THREADS;
        Set<Long> returnedIds = ConcurrentHashMap.newKeySet();
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        for (int round = 0; round < ADD_ROUNDS; round++) {
            final int currentRound = round;
            CountDownLatch gate = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(ADD_THREADS);

            for (int i = 0; i < ADD_THREADS; i++) {
                final int n = i;
                pool.execute(() -> {
                    try {
                        gate.await();
                        var response = rest.postForEntity("/api/items",
                                new NewItem("item-" + currentRound + "-" + n, 100), Item.class);
                        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                            returnedIds.add(response.getBody().id());
                        } else {
                            errors.add("HTTP " + response.getStatusCode());
                        }
                    } catch (Exception e) {
                        errors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                    } finally {
                        done.countDown();
                    }
                });
            }

            releaseAfter(gate, 100);
            if (!done.await(60, TimeUnit.SECONDS)) {
                fail("Раунд " + currentRound + ": часть потоков не завершилась за 60 секунд");
            }
        }

        List<Item> itemsAfter = getList("/api/items", Item[].class);

        System.out.println("=== RaceTest.concurrentAddItems: запросов " + totalRequests
                + ", уникальных id в ответах " + returnedIds.size()
                + ", товаров в хранилище стало " + (itemsAfter == null ? "? (не читается)" : itemsAfter.size())
                + ", HTTP-ошибок " + errors.size());

        SoftAssertions.assertSoftly(checks -> {
            checks.assertThat(errors)
                    .as("HTTP-ошибок нет (HashMap под конкурентной записью может бросать исключения)")
                    .isEmpty();
            checks.assertThat(returnedIds)
                    .as("Все %d id уникальны: nextItemId++ без синхронизации раздаёт одинаковые id", totalRequests)
                    .hasSize(totalRequests);
            if (itemsAfter == null) {
                checks.fail("Список товаров нельзя прочитать: HashMap повреждена настолько, что сервер падает на GET");
            } else {
                checks.assertThat(countNulls(itemsAfter))
                        .as("В списке товаров нет null-элементов: HashMap повреждена изнутри")
                        .isZero();
                long added = itemsAfter.stream().filter(java.util.Objects::nonNull)
                        .map(Item::id).filter(id -> !idsBefore.contains(id)).count();
                checks.assertThat(added)
                        .as("Все %d товаров остались в хранилище: одновременный put в HashMap теряет элементы", totalRequests)
                        .isEqualTo(totalRequests);
            }
        });
    }
}
