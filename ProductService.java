import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public class ProductService {
    // In-memory data store: Maps "id" -> Product object
    private static final ConcurrentHashMap<Integer, Product> productStore = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: java ProductService config.json");
            return;
        }

        String configFile = args[0];
        int port = loadConfigPort(configFile);

        // Create an HTTP server on the specified port
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        // Use a thread pool for performance/scalability
        server.setExecutor(Executors.newFixedThreadPool(10));

        // Set up the /product endpoint
        server.createContext("/product", new ProductHandler());

        // Start the server
        server.start();
        System.out.println("ProductService started on port " + port);
    }

    /**
     * Reads config.json to find the "port" for ProductService.
     * NOTE: This is a simple string-based parser that looks for the word "port".
     *       Adapt as needed for your assignment's config structure.
     */
    private static int loadConfigPort(String filename) {
        try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.contains("\"port\"")) {
                    // Extract numeric digits only
                    return Integer.parseInt(line.replaceAll("[^0-9]", ""));
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load configuration file.", e);
        }
        return 8080; // Fallback if not found
    }

    /**
     * Handler for the /product endpoint.
     * - POST -> handles create/update/delete
     * - GET /product/<id> -> retrieves product info
     */
    static class ProductHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            if ("POST".equalsIgnoreCase(method)) {
                handlePostRequest(exchange);
            }
            else if ("GET".equalsIgnoreCase(method) && path.matches("/product/\\d+")) {
                handleGetRequest(exchange);
            }
            else {
                // Invalid request or endpoint
                sendResponse(exchange, 400, "{\"error\":\"Invalid request.\"}");
            }
        }

        private void handlePostRequest(HttpExchange exchange) throws IOException {
            String requestBody = getRequestBody(exchange);

            // Extract the "command" and "id" fields from the JSON
            String command = extractJsonValue(requestBody, "command");
            String idStr   = extractJsonValue(requestBody, "id");

            // Basic validation
            if (command == null || idStr == null) {
                sendResponse(exchange, 400, "{\"error\":\"Missing required fields (command, id).\"}");
                return;
            }

            int id;
            try {
                id = Integer.parseInt(idStr);
            } catch (NumberFormatException e) {
                sendResponse(exchange, 400, "{\"error\":\"Invalid id value.\"}");
                return;
            }

            // Dispatch to the correct command
            switch (command.toLowerCase()) {
                case "create":
                    createProduct(exchange, requestBody, id);
                    break;
                case "update":
                    updateProduct(exchange, requestBody, id);
                    break;
                case "delete":
                    deleteProduct(exchange, requestBody, id);
                    break;
                default:
                    sendResponse(exchange, 400, "{\"error\":\"Invalid command.\"}");
            }
        }

        private void createProduct(HttpExchange exchange, String requestBody, int id) throws IOException {
            // Required fields
            String productName = extractJsonValue(requestBody, "productname");
            String priceStr    = extractJsonValue(requestBody, "price");
            String quantityStr = extractJsonValue(requestBody, "quantity");

            // Minimal checks
            if (productName == null || priceStr == null || quantityStr == null) {
                sendResponse(exchange, 400, "{\"error\":\"Missing product fields.\"}");
                return;
            }

            // Convert numeric fields
            double price;
            int quantity;
            try {
                price = Double.parseDouble(priceStr);
                quantity = Integer.parseInt(quantityStr);
            } catch (NumberFormatException e) {
                sendResponse(exchange, 400, "{\"error\":\"Invalid numeric field(s).\"}");
                return;
            }

            // Check for conflict
            if (productStore.containsKey(id)) {
                sendResponse(exchange, 409, "{\"error\":\"Product ID already exists.\"}");
                return;
            }

            Product newProduct = new Product(id, productName, price, quantity);
            productStore.put(id, newProduct);
            sendResponse(exchange, 200, "{\"message\":\"Product created.\"}");
        }

        private void updateProduct(HttpExchange exchange, String requestBody, int id) throws IOException {
            if (!productStore.containsKey(id)) {
                sendResponse(exchange, 404, "{\"error\":\"Product not found.\"}");
                return;
            }

            Product existing = productStore.get(id);

            String productName = extractJsonValue(requestBody, "productname");
            String priceStr    = extractJsonValue(requestBody, "price");
            String quantityStr = extractJsonValue(requestBody, "quantity");

            // If any field is missing, keep the existing product's value
            if (productName == null) productName = existing.productName;

            double price = existing.price;
            if (priceStr != null) {
                try {
                    price = Double.parseDouble(priceStr);
                } catch (NumberFormatException e) {
                    sendResponse(exchange, 400, "{\"error\":\"Invalid price value.\"}");
                    return;
                }
            }

            int quantity = existing.quantity;
            if (quantityStr != null) {
                try {
                    quantity = Integer.parseInt(quantityStr);
                } catch (NumberFormatException e) {
                    sendResponse(exchange, 400, "{\"error\":\"Invalid quantity value.\"}");
                    return;
                }
            }

            // Update the product
            Product updated = new Product(id, productName, price, quantity);
            productStore.put(id, updated);

            sendResponse(exchange, 200, "{\"message\":\"Product updated.\"}");
        }

        private void deleteProduct(HttpExchange exchange, String requestBody, int id) throws IOException {
            if (!productStore.containsKey(id)) {
                sendResponse(exchange, 404, "{\"error\":\"Product not found.\"}");
                return;
            }

            // For consistency with the assignment's user-service-like approach,
            // you can require that all fields must match if you want to delete.
            // Alternatively, just delete by ID.
            // We'll match the user-service style and check some fields:

            Product existing = productStore.get(id);

            String productName = extractJsonValue(requestBody, "productname");
            String priceStr    = extractJsonValue(requestBody, "price");
            String quantityStr = extractJsonValue(requestBody, "quantity");

            // Convert the numeric fields if present
            Double price = null;
            Integer quantity = null;
            try {
                if (priceStr != null) price = Double.parseDouble(priceStr);
                if (quantityStr != null) quantity = Integer.parseInt(quantityStr);
            } catch (NumberFormatException e) {
                // If numeric parse fails, treat it as mismatch
                sendResponse(exchange, 401, "{\"error\":\"One or more fields invalid.\"}");
                return;
            }

            // Compare the existing product’s fields with those provided
            // If any field is missing in the request, we treat that as "doesn't match"
            if (productName == null
                    || price == null
                    || quantity == null
                    || !existing.productName.equals(productName)
                    || existing.price != price
                    || existing.quantity != quantity) {

                sendResponse(exchange, 401, "{\"error\":\"Product fields do not match; delete failed.\"}");
            } else {
                productStore.remove(id);
                sendResponse(exchange, 200, "{\"message\":\"Product deleted.\"}");
            }
        }

        private void handleGetRequest(HttpExchange exchange) throws IOException {
            // Path pattern: /product/<id>
            String path = exchange.getRequestURI().getPath();
            String idStr = path.replace("/product/", "");

            int productId;
            try {
                productId = Integer.parseInt(idStr);
            } catch (NumberFormatException e) {
                sendResponse(exchange, 400, "{\"error\":\"Invalid product ID.\"}");
                return;
            }

            if (!productStore.containsKey(productId)) {
                sendResponse(exchange, 404, "{\"error\":\"Product not found.\"}");
                return;
            }

            Product product = productStore.get(productId);
            // Build JSON response
            String responseJson = String.format(
                    "{\"id\":%d,\"productname\":\"%s\",\"price\":%.2f,\"quantity\":%d}",
                    product.id, product.productName, product.price, product.quantity
            );

            sendResponse(exchange, 200, responseJson);
        }

        // Helper: read entire request body into a String
        private String getRequestBody(HttpExchange exchange) throws IOException {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                StringBuilder requestBody = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    requestBody.append(line);
                }
                return requestBody.toString();
            }
        }

        // Helper: send HTTP response
        private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
            exchange.sendResponseHeaders(statusCode, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes(StandardCharsets.UTF_8));
            }
        }

        /**
         * Manual JSON extraction (like the approach used for UserService).
         * Looks for "key": "value" or "key": number
         */
        private String extractJsonValue(String json, String key) {
            // We handle both string values and numeric values:
            //   "key": "someString"
            //   "key":  123
            String pattern = "\"" + key + "\":\\s*\"([^\"]*)\"|\"" + key + "\":\\s*(\\-?\\d+(\\.\\d+)?)";
            java.util.regex.Pattern regex = java.util.regex.Pattern.compile(pattern);
            java.util.regex.Matcher matcher = regex.matcher(json);

            if (matcher.find()) {
                // Group(1) => if it's a string
                // Group(2) => if it's numeric (int or float)
                return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            }
            return null; // Not found
        }
    }

    /**
     * Basic Product object
     */
    static class Product {
        int id;
        String productName;
        double price;
        int quantity;

        public Product(int id, String productName, double price, int quantity) {
            this.id = id;
            this.productName = productName;
            this.price = price;
            this.quantity = quantity;
        }
    }
}
