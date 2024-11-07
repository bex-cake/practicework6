package com.intela.ecommerce.services;

import com.intela.ecommerce.models.*;
import com.intela.ecommerce.repositories.*;
import com.intela.ecommerce.requestResponse.CartResponse;
import com.intela.ecommerce.requestResponse.LoggedUserResponse;
import com.intela.ecommerce.requestResponse.OrderResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

import static com.intela.ecommerce.util.Util.getUserByToken;

@Service
@RequiredArgsConstructor
public class CustomerService {
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final JwtService jwtService;
    private static Map<Product, Map<Product, Double>> diff = new HashMap<>();
    private static Map<Product, Map<Product, Integer>> freq = new HashMap<>();
    private static Map<User, HashMap<Product, Double>> inputData;
    private static Map<User, HashMap<Product, Double>> outputData = new HashMap<>();
    private final CategoryRepository categoryRepository;

    /*
     * Products functions
     */
    public List<Product> fetchAllProducts(){
        return this.productRepository.findAll();
    }

    public List<Product> fetchAllProductsByCategory(String categoryName){
        Category category = categoryRepository.findByCategoryName(categoryName);
        if (category == null) {return Collections.emptyList();}
        return this.productRepository.findAllByCategory(category);
    }

    public Product fetchProductById(String productId){
        return this.productRepository.findById(productId)
                .orElseThrow(()-> new RuntimeException("Could not find product"));
    }

    public List<Product> recommend(HttpServletRequest request) {
        User user = getUserByToken(request, jwtService, this.userRepository);
        // Fetch all orders for the given user
        Cart cart = cartRepository.findByUserId(user.getId());
        CartResponse cartResponse = fetchCartByUserId(request);
        List<Order> userOrders = orderRepository.findAllByCartId(cart.getId());

        // Count occurrences of each product in the user's orders
        Map<String, Long> productFrequency = userOrders.stream()
                .flatMap(order -> order.getCart().getCartItems().stream()) // Access CartItems from each Cart
                .map(CartItem::getProduct) // Access Product from each CartItem
                .collect(Collectors.groupingBy(Product::getId, Collectors.counting()));

        // Sort products by frequency in descending order and get the top 5
        List<String> topProductIds = productFrequency.entrySet().stream()
                .sorted((e1, e2) -> Long.compare(e2.getValue(), e1.getValue()))
                .limit(5)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        // Retrieve Product entities based on the product IDs
        return topProductIds.stream()
                .map(productRepository::findById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }

    public List<Product> collaborativeFiltering(HttpServletRequest request) {
        User user = getUserByToken(request, jwtService, this.userRepository);
        // Step 1: Build user-product frequency matrix based on orders
        List<User> allUsers = userRepository.findAll();
        Map<String, Map<String, Double>> purchaseMatrix = buildPurchaseMatrix(allUsers);

        // Step 2: Calculate average differences and frequencies for each product pair
        Map<String, Map<String, Double>> avgDiffs = new HashMap<>();
        Map<String, Map<String, Integer>> frequencies = new HashMap<>();

        for (Map<String, Double> purchases : purchaseMatrix.values()) {
            for (Map.Entry<String, Double> entry : purchases.entrySet()) {
                String productId1 = entry.getKey();
                Double count1 = entry.getValue();

                avgDiffs.putIfAbsent(productId1, new HashMap<>());
                frequencies.putIfAbsent(productId1, new HashMap<>());

                for (Map.Entry<String, Double> entry2 : purchases.entrySet()) {
                    String productId2 = entry2.getKey();
                    Double count2 = entry2.getValue();

                    int count = frequencies.get(productId1).getOrDefault(productId2, 0);
                    double diff = avgDiffs.get(productId1).getOrDefault(productId2, 0.0);

                    // Update the average difference and frequency count
                    avgDiffs.get(productId1).put(productId2, diff + (count1 - count2));
                    frequencies.get(productId1).put(productId2, count + 1);
                }
            }
        }

        // Step 3: Normalize the differences by frequency
        for (String productId1 : avgDiffs.keySet()) {
            for (String productId2 : avgDiffs.get(productId1).keySet()) {
                double diff = avgDiffs.get(productId1).get(productId2);
                int count = frequencies.get(productId1).get(productId2);
                avgDiffs.get(productId1).put(productId2, diff / count);
            }
        }

        // Step 4: Predict scores for products the target user hasn’t purchased
        Map<String, Double> userPurchases = purchaseMatrix.get(user.getId());
        Map<String, Double> predictions = new HashMap<>();
        Map<String, Integer> predictionCounts = new HashMap<>();

        for (String productId : avgDiffs.keySet()) {
            if (userPurchases.containsKey(productId)) continue;

            double totalScore = 0.0;
            int count = 0;

            for (Map.Entry<String, Double> entry : userPurchases.entrySet()) {
                String purchasedProductId = entry.getKey();
                Double purchaseFrequency = entry.getValue();

                if (avgDiffs.containsKey(purchasedProductId) && avgDiffs.get(purchasedProductId).containsKey(productId)) {
                    double diff = avgDiffs.get(purchasedProductId).get(productId);
                    totalScore += (purchaseFrequency + diff) * frequencies.get(purchasedProductId).get(productId);
                    count += frequencies.get(purchasedProductId).get(productId);
                }
            }

            if (count > 0) {
                predictions.put(productId, totalScore / count);
                predictionCounts.put(productId, count);
            }
        }

        // Step 5: Sort predictions and return the top 5 recommended products
        return predictions.entrySet().stream()
                .sorted((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()))
                .limit(5)
                .map(entry -> productRepository.findById(entry.getKey()).orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private Map<String, Map<String, Double>> buildPurchaseMatrix(List<User> allUsers) {
        Map<String, Map<String, Double>> purchaseMatrix = new HashMap<>();

        for (User user : allUsers) {
            Cart cart = cartRepository.findByUserId(user.getId());
            if (cart == null) continue;
            List<Order> userOrders = orderRepository.findAllByCartId(cart.getId());

            // Count how often each product was ordered by the user
            Map<String, Double> productCounts = new HashMap<>();
            for (Order order : userOrders) {
                List<CartItem> cartItems = order.getCart().getCartItems();
                for (CartItem cartItem : cartItems) {
                    String productId = cartItem.getProduct().getId();
                    productCounts.put(productId, productCounts.getOrDefault(productId, 0.0) + 1.0);
                }
            }
            purchaseMatrix.put(user.getId(), productCounts);
        }
        System.out.println(purchaseMatrix);

        return purchaseMatrix;
    }

    /*
     * Cart functions
     */
    public CartResponse addProductInCart(
            String productId,
            HttpServletRequest request
    ){
        User user = getUserByToken(request, jwtService, this.userRepository);
        Cart cart = this.cartRepository.findByUserId(user.getId());
        Product product = this.productRepository.findById(productId).orElse(null);
        LoggedUserResponse userResponse = LoggedUserResponse.builder()
                .id(user.getId())
                .firstname(user.getFirstName())
                .lastname(user.getLastName())
                .email(user.getEmail())
                .mobileNumber(user.getMobileNumber())
                .build();

        //check if cart instance exists
        //if not create a new cart for the user
        if(cart == null){
            List<CartItem> cartItems = new ArrayList<>();
            //Create a new cart item and add it in a list
            CartItem savedCartItem = this.cartItemRepository.save(
                    CartItem.builder()
                            .product(product)
                            .quantity(1)
                            .build()
            );
            cartItems.add(savedCartItem);

            //create a new cart item and add them to the new cart
            //save the new cart in the database
            Cart savedCart = this.cartRepository.save(
                                  Cart.builder()
                                       .user(user)
                                       .cartItems(cartItems)
                                       .build()
                                );

            return CartResponse.builder()
                    .id(savedCart.getId())
                    .user(userResponse)
                    .cartItems(savedCart.getCartItems())
                    .build();
        }
        else {
            //get all as a list cart items from cart
            //check if product already exists in one of the cart items
            if(cart.getCartItems() != null){
                for (CartItem cartItem : cart.getCartItems()) {
                    assert product != null;
                    if (Objects.equals(cartItem.getProduct().getId(), product.getId())) {
                        //if product exist then add a 1 to the quantity of the product item
                        cartItem.setQuantity(cartItem.getQuantity() + 1);

                        //save,update and return the cart item
                        this.cartRepository.save(cart);

                        return CartResponse.builder()
                                .id(cart.getId())
                                .user(userResponse)
                                .cartItems(cart.getCartItems())
                                .build();
                    }
                }
            }

            //if product does not exist
            //create a new cart item with product and quantity as 1
            CartItem savedCartItem = this.cartItemRepository.save(
                    CartItem.builder()
                            .product(product)
                            .quantity(1)
                            .build()
            );
            //add the cart item in the cart
            if(cart.getCartItems() != null){
                cart.getCartItems().add(savedCartItem);
            }else{
                List<CartItem> cartItemList = new ArrayList<>();
                cartItemList.add(savedCartItem);
                cart.setCartItems(cartItemList);
            }

            //save,update and return the cart item
            this.cartRepository.save(cart);
            return CartResponse.builder()
                    .id(cart.getId())
                    .user(userResponse)
                    .cartItems(cart.getCartItems())
                    .build();
        }

    }

    public String removeProductFromCart(String cartItemId){
        try{
            this.cartItemRepository.deleteById(cartItemId);
        }catch (Exception e){
             throw new RuntimeException("Failed to remove product from cart");
        }
        return "Product successfully removed from cart";
    }

    public CartItem increaseDecreaseProductQuantity(String cartItemId, Integer value){
        CartItem cartItem = this.cartItemRepository.findById(cartItemId)
                .orElseThrow(() -> new RuntimeException("Could not find cart item"));

        if(value > 0 ){
            cartItem.setQuantity(cartItem.getQuantity() + value);
            return this.cartItemRepository.save(cartItem);
        }
        else{
            throw new RuntimeException("Product quantity should be above zero");
        }
    }

    /*
     * Order functions
     */
    //include filters for pending, fulfilled and rejected orders
    public List<Order> fetchAllOrdersByUserId(
            HttpServletRequest request
    ){
        User user = getUserByToken(request, jwtService, this.userRepository);
        Cart cart = this.cartRepository.findByUserId(user.getId());
        return this.orderRepository.findAllByCartId(cart.getId());
    }

    public OrderResponse createOrder(HttpServletRequest request){
        long total = 0;
        User user = getUserByToken(request, jwtService, this.userRepository);
        Cart cart = this.cartRepository.findByUserId(user.getId());
        Calendar calendar = Calendar.getInstance();

        if(cart.getCartItems() == null)
            throw new RuntimeException("Can not create order with empty cart");

        for(CartItem cartItem : cart.getCartItems()){
            total = (long) cartItem.getQuantity() * cartItem.getProduct().getPrice();
        }

        Order savedOrder = this.orderRepository.save(
                Order.builder()
                        .orderStatus(OrderStatus.PENDING)
                        .cart(cart)
                        .createdAt(calendar.getTime())
                        .orderStatus(OrderStatus.PENDING)
                        .processedAt(null)
                        .total(total)
                        .build()
        );


        CartResponse cartResponse = CartResponse.builder()
                .id(savedOrder.getCart().getId())
                .user(
                        LoggedUserResponse.builder()
                                .id(savedOrder.getCart().getUser().getId())
                                .firstname(savedOrder.getCart().getUser().getFirstName())
                                .lastname(savedOrder.getCart().getUser().getLastName())
                                .email(savedOrder.getCart().getUser().getEmail())
                                .mobileNumber(savedOrder.getCart().getUser().getMobileNumber())
                                .build()
                )
                .cartItems(savedOrder.getCart().getCartItems())
                .build();

        //clear cart once order is creates
        this.cartRepository.delete(cart);
        return OrderResponse.builder()
                .id(savedOrder.getId())
                .cart(cartResponse)
                .orderStatus(savedOrder.getOrderStatus())
                .createdAt(savedOrder.getCreatedAt())
                .total(savedOrder.getTotal())
                .processedAt(savedOrder.getProcessedAt())
                .build();
    }


    public CartResponse fetchCartByUserId(HttpServletRequest request) {
        User user = getUserByToken(request, jwtService, this.userRepository);
        Cart cart = this.cartRepository.findByUserId(user.getId());

        LoggedUserResponse userResponse = LoggedUserResponse.builder()
                .id(user.getId())
                .firstname(user.getFirstName())
                .lastname(user.getLastName())
                .mobileNumber(user.getMobileNumber())
                .email(user.getEmail())
                .build();

        if(cart == null){
            Cart savedCart = this.cartRepository.save(
                    Cart.builder()
                            .user(user)
                            .build()
            );

            return CartResponse.builder()
                    .id(savedCart.getId())
                    .user(userResponse)
                    .cartItems(savedCart.getCartItems())
                    .build();
        }

        return CartResponse.builder()
                .id(cart.getId())
                .user(userResponse)
                .cartItems(cart.getCartItems())
                .build();
    }
}
