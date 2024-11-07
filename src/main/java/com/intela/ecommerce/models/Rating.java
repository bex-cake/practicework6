package com.intela.ecommerce.models;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Rating {
    private User user;
    private Product product;

    private Double score;  // Rating score given by the user to the product
}
