import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class OrderService {

    private static final ConcurrentHashMap<Integer, Order> orderStore = new ConcurrentHashMap<>();
    private static final AtomicInteger orderIdGenerator = new AtomicInteger(1);

    // Configuration for this OrderService (port) and the ISCS (ip, port)
    private static int orderServicePort = 14000;  // default
    private static String iscsIp = "127.0.0.1";   // default
    private static int iscsPort = 16000;         // default

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: java OrderService config.json");
            return;
        }

        // 1) Load the config to find "OrderService" port and "InterServiceCommunication" host/port
        String configFile = args[0];
        parseConfigFile(configFile);

        // 2) Create HTTP server for the OrderService
        HttpServer server = HttpServer.create(new InetSocketAddress(orderServicePort), 0);
        server.setExecutor(Executors.newFixedThreadPool(10));

        // 3) Create contexts:
        //    - /order => handles place/retrieve/cancel order
        //    - /user  => pass-through to ISCS
        //    - /product => pass-through to ISCS
        server.createContext("/order", new OrderHandler());
        server.createContext("/user", new PassThroughHandler("/user"));
        server.createContext("/product", new PassThroughHandler("/product"));

        // 4) Start the server
        server.start();
        System.out.println("OrderService started on port " + orderServicePort);
        System.out.println("Forwarding requests to ISCS at " + iscsIp + ":" + iscsPort);
    }

    /**
     * Parse the config file in a simple string manner
     * to find the relevant ports and IP addresses.
     * (You can adapt to your own JSON structure.)
     */
    private static void parseConfigFile(String filename) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(filename), StandardCharsets.UTF_8))) {
            String line;
            String currentSection = "";

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                // Simple logic to detect which service we are reading about:
                if (line.contains("OrderService")) {
                    currentSection = "OrderService";
                } else if (line.contains("InterServiceCommunication")) {
                    currentSection = "ISCS";
                }

                // Look for lines like: "port": 14000
                if (line.contains("\"port\"")) {
                    int port = extractInt(line);
                    if ("OrderService".equals(currentSection)) {
                        orderServicePort = port;
                    } else if ("ISCS".equals(currentSection)) {
                        iscsPort = port;
                    }
                }
                // Look for lines like: "ip": "127.0.0.1"
                if (line.contains("\"ip\"")) {
                    String ip = extractString(line);
                    if ("ISCS".equals(currentSection)) {
                        iscsIp = ip;
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load config file", e);
        }
    }

    private static int extractInt(String line) {
        String digits = line.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? 0 : Integer.parseInt(digits);
    }

    private static String extractString(String line) {
        // naive approach to find text between double quotes
        int firstQuote = line.indexOf('"');
        int secondQuote = line.indexOf('"', firstQuote + 1);
        int thirdQuote = line.indexOf('"', secondQuote + 1);
        int fourthQuote = line.indexOf('"', thirdQuote + 1);
        // This tries to parse something like: "ip": "127.0.0.1"
        // firstQuote = index of first "
        // secondQuote = index of second "
        // thirdQuote = index of third "
        // fourthQuote = index of fourth "
        // The substring we want is between the thirdQuote+1 and the fourthQuote
        if (thirdQuote >= 0 && fourthQuote > thirdQuote) {
            return line.substring(thirdQuote + 1, fourthQuote);
        }
        return "127.0.0.1";
    }

    /**
     * Handler for /order path
     */
    static class OrderHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            if ("POST".equalsIgnoreCase(method)) {
                // Possibly place order or cancel order
                handlePost(exchange);
            } else if ("GET".equalsIgnoreCase(method) && path.matches("/order/\\d+")) {
                // retrieve an order by ID
                handleGetOrder(exchange);
            } else {
                sendResponse(exchange, 400, "{\"error\":\"Invalid request.\"}");
            }
        }

        private void handlePost(HttpExchange exchange) throws IOException {
            String requestBody = readRequestBody(exchange);

            String command = extractJsonValue(requestBody, "command");
            if (command == null) {
                sendResponse(exchange, 400, "{\"error\":\"Missing command.\"}");
                return;
            }

            if ("place order".equalsIgnoreCase(command)) {
                placeOrder(exchange, requestBody);
            }
            else if ("cancel".equalsIgnoreCase(command)) {
                cancelOrder(exchange, requestBody);
            }
            else {
                sendResponse(exchange, 400, "{\"error\":\"Unknown command.\"}");
            }
        }

        /**
         * place order:
         * {
         *   "command": "place order",
         *   "user_id": 1,
         *   "product_id": 2,
         *   "quantity": 3
         * }
         * Steps:
         * 1) GET /user/<user_id> from ISCS => if 404 => user doesn't exist
         * 2) GET /product/<product_id> from ISCS => if 404 => product doesn't exist
         * 3) Check if product quantity >= requested. If not, 400 => insufficient stock
         * 4) POST /product => update the quantity
         * 5) Create a new order in memory
         * 6) Return success
         */
        private void placeOrder(HttpExchange exchange, String requestBody) throws IOException {
            // Parse fields
            String userIdStr    = extractJsonValue(requestBody, "user_id");
            String productIdStr = extractJsonValue(requestBody, "product_id");
            String quantityStr  = extractJsonValue(requestBody, "quantity");

            if (userIdStr == null || productIdStr == null || quantityStr == null) {
                sendResponse(exchange, 400, "{\"error\":\"Missing required fields.\"}");
                return;
            }

            int userId, productId, quantity;
            try {
                userId = Integer.parseInt(userIdStr);
                productId = Integer.parseInt(productIdStr);
                quantity = Integer.parseInt(quantityStr);
            } catch (NumberFormatException e) {
                sendResponse(exchange, 400, "{\"error\":\"Invalid numeric field.\"}");
                return;
            }

            // 1) GET user
            String userGetUrl = buildISCSUrl("/user/" + userId);
            HttpResponse userResp = doHttpGet(userGetUrl);
            if (userResp.statusCode == 404) {
                sendResponse(exchange, 404, "{\"error\":\"User not found.\"}");
                return;
            } else if (userResp.statusCode != 200) {
                // Some other error from user service
                sendResponse(exchange, userResp.statusCode, userResp.body);
                return;
            }

            // 2) GET product
            String productGetUrl = buildISCSUrl("/product/" + productId);
            HttpResponse productResp = doHttpGet(productGetUrl);
            if (productResp.statusCode == 404) {
                sendResponse(exchange, 404, "{\"error\":\"Product not found.\"}");
                return;
            } else if (productResp.statusCode != 200) {
                sendResponse(exchange, productResp.statusCode, productResp.body);
                return;
            }

            // 3) parse the product JSON => get "quantity"
            int currentStock = parseProductQuantity(productResp.body);
            if (currentStock < quantity) {
                sendResponse(exchange, 400, "{\"error\":\"Insufficient stock.\"}");
                return;
            }

            // 4) update product quantity => newQty = currentStock - quantity
            int newQty = currentStock - quantity;
            // Build the JSON to update:
            // {
            //   "command": "update",
            //   "id": <productId>,
            //   "quantity": <newQty>
            // }
            String updateJson = String.format(
                    "{\"command\":\"update\",\"id\":%d,\"quantity\":%d}",
                    productId, newQty
            );
            HttpResponse updateResp = doHttpPost(buildISCSUrl("/product"), updateJson);
            if (updateResp.statusCode != 200) {
                // Could not update the product
                sendResponse(exchange, updateResp.statusCode, updateResp.body);
                return;
            }

            // 5) Create new order in memory
            int newOrderId = orderIdGenerator.getAndIncrement();
            Order newOrder = new Order(newOrderId, userId, productId, quantity);
            orderStore.put(newOrderId, newOrder);

            // 6) Return success
            String successJson = String.format(
                    "{\"message\":\"Order placed.\", \"order_id\": %d}",
                    newOrderId
            );
            sendResponse(exchange, 200, successJson);
        }

        /**
         * Cancels an order with:
         * {
         *   "command": "cancel",
         *   "order_id": 123
         * }
         * A simple approach: remove from orderStore.
         * (You could also restore product quantity, etc., if your assignment requires it.)
         */
        private void cancelOrder(HttpExchange exchange, String requestBody) throws IOException {
            String orderIdStr = extractJsonValue(requestBody, "order_id");
            if (orderIdStr == null) {
                sendResponse(exchange, 400, "{\"error\":\"Missing order_id.\"}");
                return;
            }

            int orderId;
            try {
                orderId = Integer.parseInt(orderIdStr);
            } catch (NumberFormatException e) {
                sendResponse(exchange, 400, "{\"error\":\"Invalid order_id.\"}");
                return;
            }

            if (!orderStore.containsKey(orderId)) {
                sendResponse(exchange, 404, "{\"error\":\"Order not found.\"}");
                return;
            }

            // If needed, you might also restore product quantity here.
            // For now, just remove the order.
            orderStore.remove(orderId);

            sendResponse(exchange, 200, "{\"message\":\"Order canceled.\"}");
        }

        private void handleGetOrder(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            // path = /order/<id>
            String orderIdStr = path.replace("/order/", "");
            int orderId;
            try {
                orderId = Integer.parseInt(orderIdStr);
            } catch (NumberFormatException e) {
                sendResponse(exchange, 400, "{\"error\":\"Invalid order ID.\"}");
                return;
            }

            if (!orderStore.containsKey(orderId)) {
                sendResponse(exchange, 404, "{\"error\":\"Order not found.\"}");
                return;
            }

            Order order = orderStore.get(orderId);
            String respJson = String.format(
                    "{\"order_id\":%d,\"user_id\":%d,\"product_id\":%d,\"quantity\":%d}",
                    order.id, order.userId, order.productId, order.quantity
            );

            sendResponse(exchange, 200, respJson);
        }

        // ========== Helper Functions ==========

        private String readRequestBody(HttpExchange exchange) throws IOException {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
                return sb.toString();
            }
        }

        private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
            exchange.sendResponseHeaders(statusCode, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes(StandardCharsets.UTF_8));
            }
        }

        // Very naive JSON extraction method
        private String extractJsonValue(String json, String key) {
            // Accepts either "key": "string" or "key": number
            String pattern = "\"" + key + "\":\\s*\"([^\"]*)\"|\"" + key + "\":\\s*(\\-?\\d+(\\.\\d+)?)";
            java.util.regex.Pattern regex = java.util.regex.Pattern.compile(pattern);
            java.util.regex.Matcher matcher = regex.matcher(json);

            if (matcher.find()) {
                // group(1) => string, group(2) => numeric
                return (matcher.group(1) != null) ? matcher.group(1) : matcher.group(2);
            }
            return null;
        }

        /**
         * Attempt to parse "quantity" from a product JSON, e.g.:
         * {
         *   "id": 2,
         *   "productname": "...",
         *   "price": 3.99,
         *   "quantity": 9
         * }
         */
        private int parseProductQuantity(String productJson) {
            // parse "quantity"
            String quantityStr = extractJsonValue(productJson, "quantity");
            if (quantityStr == null) {
                return 0;
            }
            try {
                return Integer.parseInt(quantityStr);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        // ========== HTTP calls to ISCS ==========

        private String buildISCSUrl(String path) {
            // e.g. "http://127.0.0.1:16000/user/1"
            return String.format("http://%s:%d%s", iscsIp, iscsPort, path);
        }

        /** do GET request to the given URL and return HttpResponse(statusCode, body) */
        private HttpResponse doHttpGet(String urlStr) {
            HttpResponse result = new HttpResponse();
            HttpURLConnection conn = null;
            BufferedReader reader = null;

            try {
                URL url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");

                int status = conn.getResponseCode();
                result.statusCode = status;

                // read response body
                InputStream is = (status < 400) ? conn.getInputStream() : conn.getErrorStream();
                reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                result.body = sb.toString();

            } catch (IOException e) {
                result.statusCode = 500;
                result.body = "{\"error\":\"ISCS communication failed\"}";
            } finally {
                if (reader != null) {
                    try { reader.close(); } catch (IOException e) { /* ignored */ }
                }
                if (conn != null) {
                    conn.disconnect();
                }
            }
            return result;
        }

        /** do POST request to the given URL with the given JSON body */
        private HttpResponse doHttpPost(String urlStr, String jsonBody) {
            HttpResponse result = new HttpResponse();
            HttpURLConnection conn = null;
            BufferedReader reader = null;

            try {
                URL url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                // write the JSON body
                try (OutputStream os = conn.getOutputStream();
                     BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8))) {
                    bw.write(jsonBody);
                }

                int status = conn.getResponseCode();
                result.statusCode = status;

                InputStream is = (status < 400) ? conn.getInputStream() : conn.getErrorStream();
                reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                result.body = sb.toString();

            } catch (IOException e) {
                result.statusCode = 500;
                result.body = "{\"error\":\"ISCS communication failed\"}";
            } finally {
                if (reader != null) {
                    try { reader.close(); } catch (IOException e) { /* ignored */ }
                }
                if (conn != null) {
                    conn.disconnect();
                }
            }
            return result;
        }
    }

    /**
     * Handler for pass-through requests to ISCS:
     *   - /user
     *   - /product
     * The OrderService must not talk directly to user/product services,
     * so we forward the request to the ISCS (which then routes).
     */
    static class PassThroughHandler implements HttpHandler {
        private final String pathPrefix;

        public PassThroughHandler(String pathPrefix) {
            this.pathPrefix = pathPrefix;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Rebuild the path (e.g. /user/... or /product/...)
            String requestMethod = exchange.getRequestMethod();
            String reqPath = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery();
            if (query == null) query = "";

            // e.g. if the path is /user or /user/12, we forward that to:
            // http://ISCS_IP:ISCS_PORT/user or /user/12
            String forwardUrl = String.format("http://%s:%d%s",
                    iscsIp, iscsPort, reqPath);
            if (!query.isEmpty()) {
                forwardUrl += "?" + query;
            }

            // Build the request body if needed
            String requestBody = null;
            if ("POST".equalsIgnoreCase(requestMethod) || "PUT".equalsIgnoreCase(requestMethod)) {
                requestBody = readRequestBody(exchange);
            }

            // Send to ISCS
            HttpResponse resp;
            if ("GET".equalsIgnoreCase(requestMethod)) {
                resp = doForwardGet(forwardUrl);
            } else if ("POST".equalsIgnoreCase(requestMethod)) {
                resp = doForwardPost(forwardUrl, requestBody);
            } else if ("DELETE".equalsIgnoreCase(requestMethod)) {
                // optional, depending on usage
                resp = doForwardDelete(forwardUrl);
            } else {
                // For simplicity, let's only handle GET/POST
                sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed.\"}");
                return;
            }

            // Return the ISCS response to the client
            sendResponse(exchange, resp.statusCode, resp.body);
        }

        private String readRequestBody(HttpExchange exchange) throws IOException {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
                return sb.toString();
            }
        }

        private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
            exchange.sendResponseHeaders(statusCode, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes(StandardCharsets.UTF_8));
            }
        }

        // Forward GET request
        private HttpResponse doForwardGet(String urlStr) {
            HttpResponse result = new HttpResponse();
            HttpURLConnection conn = null;
            BufferedReader reader = null;

            try {
                URL url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");

                int status = conn.getResponseCode();
                result.statusCode = status;

                InputStream is = (status < 400) ? conn.getInputStream() : conn.getErrorStream();
                reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                result.body = sb.toString();

            } catch (IOException e) {
                result.statusCode = 500;
                result.body = "{\"error\":\"ISCS communication failed\"}";
            } finally {
                if (reader != null) {
                    try { reader.close(); } catch (IOException e) {}
                }
                if (conn != null) {
                    conn.disconnect();
                }
            }
            return result;
        }

        // Forward POST request
        private HttpResponse doForwardPost(String urlStr, String body) {
            HttpResponse result = new HttpResponse();
            HttpURLConnection conn = null;
            BufferedReader reader = null;

            try {
                URL url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                // Write body
                try (OutputStream os = conn.getOutputStream();
                     PrintWriter pw = new PrintWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8))) {
                    if (body != null) {
                        pw.write(body);
                    }
                }

                int status = conn.getResponseCode();
                result.statusCode = status;

                InputStream is = (status < 400) ? conn.getInputStream() : conn.getErrorStream();
                reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                result.body = sb.toString();

            } catch (IOException e) {
                result.statusCode = 500;
                result.body = "{\"error\":\"ISCS communication failed\"}";
            } finally {
                if (reader != null) {
                    try { reader.close(); } catch (IOException e) {}
                }
                if (conn != null) {
                    conn.disconnect();
                }
            }
            return result;
        }

        // Forward DELETE request (rarely used in the example)
        private HttpResponse doForwardDelete(String urlStr) {
            HttpResponse result = new HttpResponse();
            HttpURLConnection conn = null;
            BufferedReader reader = null;

            try {
                URL url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("DELETE");

                int status = conn.getResponseCode();
                result.statusCode = status;

                InputStream is = (status < 400) ? conn.getInputStream() : conn.getErrorStream();
                reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                result.body = sb.toString();

            } catch (IOException e) {
                result.statusCode = 500;
                result.body = "{\"error\":\"ISCS communication failed\"}";
            } finally {
                if (reader != null) {
                    try { reader.close(); } catch (IOException e) {}
                }
                if (conn != null) {
                    conn.disconnect();
                }
            }
            return result;
        }
    }

    // Basic container for order data
    static class Order {
        int id;
        int userId;
        int productId;
        int quantity;

        public Order(int id, int userId, int productId, int quantity) {
            this.id = id;
            this.userId = userId;
            this.productId = productId;
            this.quantity = quantity;
        }
    }

    // Helper class to store HTTP response data from calls to ISCS
    static class HttpResponse {
        int statusCode;
        String body;
    }
}
