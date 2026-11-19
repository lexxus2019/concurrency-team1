package com.example.buyer;

/** Ставка, полученная с аукциона: {"id": 1, "itemId": 1, "bidder": "Иван", "amount": 1500} */
public record Bid(long id, long itemId, String bidder, long amount) {
}
