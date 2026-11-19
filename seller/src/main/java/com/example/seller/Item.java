package com.example.seller;

/** Товар, полученный с аукциона: {"id": 1, "name": "Велосипед", "startPrice": 1000} */
public record Item(long id, String name, long startPrice) {
}
