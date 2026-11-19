package com.example.auction;

/** Тело запроса на добавление товара: {"name": "Велосипед", "startPrice": 1000} */
public record NewItem(String name, long startPrice) {
}
