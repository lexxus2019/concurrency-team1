package com.example.auction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Весь аукцион живёт в памяти процесса.
 *
 * ВНИМАНИЕ: HashMap, ArrayList и nextItemId++ НЕ потокобезопасны.
 * Это сделано нарочно: Spring обрабатывает каждый запрос в своём потоке,
 * поэтому при параллельных запросах сервис может сломаться или испортить данные.
 * Исправлять это будем в следующих ветках.
 */
@Service
public class Auction {

    private final Map<Long, Item> items = new HashMap<>();
    private final Map<Long, List<Bid>> bids = new HashMap<>();

    private long nextItemId = 1;
    private long nextBidId = 1;

    /** Выставить товар на аукцион. */
    public Item addItem(String name, long startPrice) {
        Item item = new Item(nextItemId++, name, startPrice);
        items.put(item.id(), item);
        return item;
    }

    /** Снять товар с продажи (вместе со ставками). */
    public void removeItem(long id) {
        if (items.remove(id) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Товар " + id + " не найден");
        }
        bids.remove(id);
    }

    /** Список всех товаров. */
    public List<Item> listItems() {
        return new ArrayList<>(items.values());
    }

    /**
     * Сделать ставку. Простое правило: ставка должна быть выше
     * текущей лучшей (или стартовой цены, если ставок ещё нет).
     */
    public Bid addBid(long itemId, String bidder, long amount) {
        Item item = items.get(itemId);
        if (item == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Товар " + itemId + " не найден");
        }

        List<Bid> itemBids = bids.getOrDefault(itemId, List.of());
        long best = item.startPrice();
        for (Bid bid : itemBids) {
            best = Math.max(best, bid.amount());
        }
        if (amount <= best) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Ставка должна быть выше " + best);
        }

        Bid bid = new Bid(nextBidId++, itemId, bidder, amount);
        bids.computeIfAbsent(itemId, key -> new ArrayList<>()).add(bid);
        return bid;
    }

    /** Список ставок на товар. */
    public List<Bid> listBids(long itemId) {
        if (!items.containsKey(itemId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Товар " + itemId + " не найден");
        }
        return bids.getOrDefault(itemId, List.of());
    }
}
