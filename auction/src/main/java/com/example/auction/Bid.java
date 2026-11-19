package com.example.auction;

/** Ставка на товар. */
public record Bid(long id, long itemId, String bidder, long amount) {
}
