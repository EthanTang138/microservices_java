import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class ProductService {
    private static final ConcurrentHashMap<Integer, Product> productStore = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        if (args.length != 1) { System.err.println("Usage: java ProductService config.json"); return; }
        Map<String, String> cfg = loadServiceConfig(args[0], "ProductService");
        int port = Integer.parseInt(cfg.get("port"));

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(10));
        server.createContext("/product", new ProductHandler());
        server.start();
        System.out.println("ProductService started on port " + port);
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

    static class ProductHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String method = ex.getRequestMethod();
            String path   = ex.getRequestURI().getPath();
            if ("POST".equalsIgnoreCase(method)) {
                handlePost(ex);
            } else if ("GET".equalsIgnoreCase(method) && path.matches("/product/\\d+")) {
                handleGet(ex);
            } else {
                sendResponse(ex, 400, "{}");
            }
        }

        private void handlePost(HttpExchange ex) throws IOException {
            String body    = readBody(ex);
            String command = extractValue(body, "command");
            String idStr   = extractValue(body, "id");
            if (command == null || idStr == null) { sendResponse(ex, 400, "{}"); return; }
            int id;
            try { id = Integer.parseInt(idStr); } catch (NumberFormatException e) { sendResponse(ex, 400, "{}"); return; }
            switch (command.toLowerCase()) {
                case "create": createProduct(ex, body, id); break;
                case "update": updateProduct(ex, body, id); break;
                case "delete": deleteProduct(ex, body, id); break;
                default: sendResponse(ex, 400, "{}");
            }
        }

        private void createProduct(HttpExchange ex, String body, int id) throws IOException {
            String name     = extractValue(body, "productname");
            String desc     = extractValue(body, "description");
            String priceStr = extractValue(body, "price");
            String qtyStr   = extractValue(body, "quantity");
            if (name == null || priceStr == null || qtyStr == null ||
                name.isEmpty() || priceStr.isEmpty() || qtyStr.isEmpty()) {
                sendResponse(ex, 400, "{}"); return;
            }
            double price; int qty;
            try { price = Double.parseDouble(priceStr); qty = Integer.parseInt(qtyStr); }
            catch (NumberFormatException e) { sendResponse(ex, 400, "{}"); return; }
            if (productStore.containsKey(id)) { sendResponse(ex, 409, "{}"); return; }
            productStore.put(id, new Product(id, name, desc != null ? desc : "", price, qty));
            sendResponse(ex, 200, "{}");
        }

        private void updateProduct(HttpExchange ex, String body, int id) throws IOException {
            if (!productStore.containsKey(id)) { sendResponse(ex, 404, "{}"); return; }
            Product existing = productStore.get(id);
            String name     = extractValue(body, "productname");
            String desc     = extractValue(body, "description");
            String priceStr = extractValue(body, "price");
            String qtyStr   = extractValue(body, "quantity");
            if (name == null || name.isEmpty()) name = existing.productName;
            if (desc == null || desc.isEmpty()) desc = existing.description;
            double price = existing.price;
            if (priceStr != null && !priceStr.isEmpty()) {
                try { price = Double.parseDouble(priceStr); }
                catch (NumberFormatException e) { sendResponse(ex, 400, "{}"); return; }
            }
            int qty = existing.quantity;
            if (qtyStr != null && !qtyStr.isEmpty()) {
                try { qty = Integer.parseInt(qtyStr); }
                catch (NumberFormatException e) { sendResponse(ex, 400, "{}"); return; }
            }
            productStore.put(id, new Product(id, name, desc, price, qty));
            sendResponse(ex, 200, "{}");
        }

        private void deleteProduct(HttpExchange ex, String body, int id) throws IOException {
            String name     = extractValue(body, "productname");
            String priceStr = extractValue(body, "price");
            String qtyStr   = extractValue(body, "quantity");
            if (name == null || priceStr == null || qtyStr == null) { sendResponse(ex, 400, "{}"); return; }
            if (!productStore.containsKey(id)) { sendResponse(ex, 404, "{}"); return; }
            Product existing = productStore.get(id);
            double price; int qty;
            try { price = Double.parseDouble(priceStr); qty = Integer.parseInt(qtyStr); }
            catch (NumberFormatException e) { sendResponse(ex, 401, "{}"); return; }
            if (existing.productName.equals(name) && existing.price == price && existing.quantity == qty) {
                productStore.remove(id);
                sendResponse(ex, 200, "{}");
            } else {
                sendResponse(ex, 401, "{}");
            }
        }

        private void handleGet(HttpExchange ex) throws IOException {
            int id = Integer.parseInt(ex.getRequestURI().getPath().replace("/product/", ""));
            if (!productStore.containsKey(id)) { sendResponse(ex, 404, "{}"); return; }
            Product p = productStore.get(id);
            String resp = String.format(
                "{\"id\":%d,\"productname\":\"%s\",\"price\":%.2f,\"quantity\":%d}",
                p.id, p.productName, p.price, p.quantity);
            sendResponse(ex, 200, resp);
        }

        private String readBody(HttpExchange ex) throws IOException {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder(); String line;
                while ((line = br.readLine()) != null) sb.append(line);
                return sb.toString();
            }
        }

        private void sendResponse(HttpExchange ex, int code, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        }

        private String extractValue(String json, String key) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "\"" + key + "\"\\s*:\\s*\"([^\"]*)\"|\"" + key + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
            java.util.regex.Matcher m = p.matcher(json);
            if (m.find()) return m.group(1) != null ? m.group(1) : m.group(2);
            return null;
        }
    }

    static class Product {
        int id, quantity; String productName, description; double price;
        Product(int id, String productName, String description, double price, int quantity) {
            this.id = id; this.productName = productName; this.description = description;
            this.price = price; this.quantity = quantity;
        }
    }
}
