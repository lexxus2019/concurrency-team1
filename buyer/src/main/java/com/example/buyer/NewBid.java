package com.example.buyer;

/** Тело запроса на ставку: {"bidder": "Иван", "amount": 1500} */
public record NewBid(String bidder, long amount) {
}
