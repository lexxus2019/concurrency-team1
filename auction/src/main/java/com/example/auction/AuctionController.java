package com.example.auction;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AuctionController {

    private final Auction auction;

    public AuctionController(Auction auction) {
        this.auction = auction;
    }

    /** POST /api/items, тело: {"name": "Велосипед", "startPrice": 1000} */
    @PostMapping("/items")
    public Item addItem(@RequestBody NewItem request) {
        return auction.addItem(request.name(), request.startPrice());
    }

    /** DELETE /api/items/1 */
    @DeleteMapping("/items/{id}")
    public void removeItem(@PathVariable long id) {
        auction.removeItem(id);
    }

    /** GET /api/items */
    @GetMapping("/items")
    public List<Item> listItems() {
        return auction.listItems();
    }

    /** GET /api/items/1/bids */
    @GetMapping("/items/{id}/bids")
    public List<Bid> listBids(@PathVariable long id) {
        return auction.listBids(id);
    }

    /** POST /api/items/1/bids, тело: {"bidder": "Иван", "amount": 1500} */
    @PostMapping("/items/{id}/bids")
    public Bid addBid(@PathVariable long id, @RequestBody NewBid request) {
        return auction.addBid(id, request.bidder(), request.amount());
    }
}
