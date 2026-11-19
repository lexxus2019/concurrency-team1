package com.example.buyer;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Сервис покупателя. Сам ничего не хранит, только пересылает запросы
 * в сервис аукциона.
 */
@RestController
@RequestMapping("/api/buyer")
public class BuyerController {

    private final RestClient auction;

    public BuyerController(@Value("${auction.url}") String auctionUrl) {
        this.auction = RestClient.builder().baseUrl(auctionUrl).build();
    }

    /** GET /api/buyer/items */
    @GetMapping("/items")
    public List<Item> listItems() {
        return auction.get()
                .uri("/api/items")
                .retrieve()
                .body(new ParameterizedTypeReference<List<Item>>() {
                });
    }

    /** GET /api/buyer/items/1/bids */
    @GetMapping("/items/{id}/bids")
    public List<Bid> listBids(@PathVariable long id) {
        return auction.get()
                .uri("/api/items/{id}/bids", id)
                .retrieve()
                .body(new ParameterizedTypeReference<List<Bid>>() {
                });
    }

    /** POST /api/buyer/items/1/bids, тело: {"bidder": "Иван", "amount": 1500} */
    @PostMapping("/items/{id}/bids")
    public Bid makeBid(@PathVariable long id, @RequestBody NewBid request) {
        return auction.post()
                .uri("/api/items/{id}/bids", id)
                .body(request)
                .retrieve()
                .body(Bid.class);
    }
}
