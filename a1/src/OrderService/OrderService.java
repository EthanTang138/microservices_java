import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class OrderService {
    private static final ConcurrentHashMap<Integer, Order> orderStore = new ConcurrentHashMap<>();
    private static final AtomicInteger orderIdGen = new AtomicInteger(1);

    private static String iscsIp   = "127.0.0.1";
    private static int    iscsPort = 16000;

    public static void main(String[] args) throws IOException {
        if (args.length != 1) { System.err.println("Usage: java OrderService config.json"); return; }
        Map<String, String> order = loadServiceConfig(args[0], "OrderService");
        Map<String, String> iscs  = loadServiceConfig(args[0], "InterServiceCommunication");
        int port = Integer.parseInt(order.get("port"));
        iscsIp   = iscs.get("ip");
        iscsPort = Integer.parseInt(iscs.get("port"));

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(10));
        server.createContext("/order",   new OrderHandler());
        server.createContext("/user",    new PassThroughHandler());
        server.createContext("/product", new PassThroughHandler());
        server.start();
        System.out.println("OrderService started on port " + port);
        System.out.println("  -> ISCS at " + iscsIp + ":" + iscsPort);
    }

    private static Map<String, String> loadServiceConfig(String filename, String section) {
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new FileReader(filename))) {
                String line; while ((line = r.readLine()) != null) sb.append(line).append("\n");
            }
            String content = sb.toString();
            int idx = content.indexOf("\"" + section + "\"");
            if (idx < 0) throw new RuntimeException("Section not found: " + section);
            int bs = content.indexOf("{", idx), be = content.indexOf("}", bs);
            String block = content.substring(bs, be + 1);
            Map<String, String> cfg = new HashMap<>();
            java.util.regex.Matcher pm = java.util.regex.Pattern.compile("\"port\"\\s*:\\s*(\\d+)").matcher(block);
            if (pm.find()) cfg.put("port", pm.group(1));
            java.util.regex.Matcher im = java.util.regex.Pattern.compile("\"ip\"\\s*:\\s*\"([^\"]+)\"").matcher(block);
            if (im.find()) cfg.put("ip", im.group(1));
            return cfg;
        } catch (IOException e) { throw new RuntimeException("Failed to load config", e); }
    }

    static class OrderHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String method = ex.getRequestMethod();
            String path   = ex.getRequestURI().getPath();
            if ("POST".equalsIgnoreCase(method)) {
                handlePost(ex);
            } else if ("GET".equalsIgnoreCase(method) && path.matches("/order/\\d+")) {
                handleGetOrder(ex);
            } else {
                sendResponse(ex, 400, "{}");
            }
        }

        private void handlePost(HttpExchange ex) throws IOException {
            String body    = readBody(ex);
            String command = extractValue(body, "command");
            if (command == null) { sendResponse(ex, 400, "{}"); return; }
            if ("place order".equalsIgnoreCase(command)) {
                placeOrder(ex, body);
            } else {
                sendResponse(ex, 400, "{}");
            }
        }

        private void placeOrder(HttpExchange ex, String body) throws IOException {
            String userIdStr    = extractValue(body, "user_id");
            String productIdStr = extractValue(body, "product_id");
            String quantityStr  = extractValue(body, "quantity");
            if (userIdStr == null || productIdStr == null || quantityStr == null) {
                sendResponse(ex, 400, "{}"); return;
            }
            int userId, productId, quantity;
            try {
                userId    = Integer.parseInt(userIdStr);
                productId = Integer.parseInt(productIdStr);
                quantity  = Integer.parseInt(quantityStr);
            } catch (NumberFormatException e) { sendResponse(ex, 400, "{}"); return; }

            // Verify user exists
            HttpResp userResp = doGet(iscsUrl("/user/" + userId));
            if (userResp.code == 404) { sendResponse(ex, 404, "{}"); return; }
            if (userResp.code != 200) { sendResponse(ex, userResp.code, userResp.body); return; }

            // Verify product exists and has enough stock
            HttpResp productResp = doGet(iscsUrl("/product/" + productId));
            if (productResp.code == 404) { sendResponse(ex, 404, "{}"); return; }
            if (productResp.code != 200) { sendResponse(ex, productResp.code, productResp.body); return; }

            int stock = parseIntField(productResp.body, "quantity");
            if (stock < quantity) { sendResponse(ex, 400, "{}"); return; }

            // Deduct stock via ISCS -> ProductService
            String updateBody = String.format(
                "{\"command\":\"update\",\"id\":%d,\"quantity\":%d}", productId, stock - quantity);
            HttpResp updateResp = doPost(iscsUrl("/product"), updateBody);
            if (updateResp.code != 200) { sendResponse(ex, updateResp.code, updateResp.body); return; }

            // Record order
            int orderId = orderIdGen.getAndIncrement();
            orderStore.put(orderId, new Order(orderId, userId, productId, quantity));
            sendResponse(ex, 200, "{}");
        }

        private void handleGetOrder(HttpExchange ex) throws IOException {
            int id = Integer.parseInt(ex.getRequestURI().getPath().replace("/order/", ""));
            if (!orderStore.containsKey(id)) { sendResponse(ex, 404, "{}"); return; }
            Order o = orderStore.get(id);
            sendResponse(ex, 200, String.format(
                "{\"id\":%d,\"user_id\":%d,\"product_id\":%d,\"quantity\":%d}",
                o.id, o.userId, o.productId, o.quantity));
        }

        private int parseIntField(String json, String key) {
            String val = extractValue(json, key);
            if (val == null) return 0;
            try { return Integer.parseInt(val); } catch (NumberFormatException e) { return 0; }
        }
    }

    static class PassThroughHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String method = ex.getRequestMethod();
            String path   = ex.getRequestURI().getPath();
            String query  = ex.getRequestURI().getQuery();
            String target = iscsUrl(path) + (query != null ? "?" + query : "");

            HttpResp resp;
            if ("GET".equalsIgnoreCase(method)) {
                resp = doGet(target);
            } else if ("POST".equalsIgnoreCase(method)) {
                resp = doPost(target, readBody(ex));
            } else {
                sendResponse(ex, 405, "{}"); return;
            }
            sendResponse(ex, resp.code, resp.body);
        }
    }

    private static String iscsUrl(String path) {
        return "http://" + iscsIp + ":" + iscsPort + path;
    }

    private static HttpResp doGet(String url) {
        HttpResp r = new HttpResp();
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            r.code = conn.getResponseCode();
            r.body = readStream(r.code < 400 ? conn.getInputStream() : conn.getErrorStream());
            conn.disconnect();
        } catch (IOException e) { r.code = 500; r.body = "{}"; }
        return r;
    }

    private static HttpResp doPost(String url, String body) {
        HttpResp r = new HttpResp();
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            byte[] bytes = (body != null ? body : "").getBytes(StandardCharsets.UTF_8);
            conn.getOutputStream().write(bytes);
            conn.getOutputStream().close();
            r.code = conn.getResponseCode();
            r.body = readStream(r.code < 400 ? conn.getInputStream() : conn.getErrorStream());
            conn.disconnect();
        } catch (IOException e) { r.code = 500; r.body = "{}"; }
        return r;
    }

    private static String readStream(InputStream is) throws IOException {
        if (is == null) return "{}";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder(); String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder(); String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private static void sendResponse(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static String extractValue(String json, String key) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "\"" + key + "\"\\s*:\\s*\"([^\"]*)\"|\"" + key + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
        java.util.regex.Matcher m = p.matcher(json);
        if (m.find()) return m.group(1) != null ? m.group(1) : m.group(2);
        return null;
    }

    static class Order {
        int id, userId, productId, quantity;
        Order(int id, int userId, int productId, int quantity) {
            this.id = id; this.userId = userId; this.productId = productId; this.quantity = quantity;
        }
    }

    static class HttpResp { int code; String body; }
}
