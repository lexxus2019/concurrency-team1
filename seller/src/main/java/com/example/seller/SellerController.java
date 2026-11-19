package com.example.seller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Сервис продавца. Сам ничего не хранит, только пересылает запросы
 * в сервис аукциона.
 */
@RestController
@RequestMapping("/api/seller")
public class SellerController {

    private final RestClient auction;

    public SellerController(@Value("${auction.url}") String auctionUrl) {
        this.auction = RestClient.builder().baseUrl(auctionUrl).build();
    }

    /** POST /api/seller/items, тело: {"name": "Велосипед", "startPrice": 1000} */
    @PostMapping("/items")
    public Item putUp(@RequestBody NewItem request) {
        return auction.post()
                .uri("/api/items")
                .body(request)
                .retrieve()
                .body(Item.class);
    }

    /** DELETE /api/seller/items/1 */
    @DeleteMapping("/items/{id}")
    public void pullOut(@PathVariable long id) {
        auction.delete()
                .uri("/api/items/{id}", id)
                .retrieve()
                .toBodilessEntity();
    }
}
