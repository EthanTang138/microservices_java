# Microservices A1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete and package a 4-service Java microservices system (UserService, ProductService, OrderService, ISCS) with WorkloadParser, runme.sh, and the required a1/ submission directory structure.

**Architecture:** OrderService is the public-facing entry point; it proxies /user and /product traffic through ISCS, which routes to UserService or ProductService. Services talk via HTTP using the JDK-built-in `com.sun.net.httpserver`. In-memory `ConcurrentHashMap` for A1 storage; SHA-256 hashing for passwords in UserService.

**Tech Stack:** Java (`com.sun.net.httpserver`, `java.security.MessageDigest`), Bash (runme.sh)

---

## Current State Assessment

The repo has no service implementations — all five services must be written from scratch. The workload files and architecture diagram are already present.

All source files go under `a1/src/<ServiceName>/`. Compiled output goes to `a1/compiled/<ServiceName>/`.

---

## File Map

```
a1/
├── config.json
├── runme.sh
├── src/
│   ├── UserService/UserService.java       (rewrite from root prototype)
│   ├── ProductService/ProductService.java (rewrite from root prototype)
│   ├── OrderService/OrderService.java     (rewrite from root prototype)
│   ├── ISCS/ISCS.java                     (new)
│   └── WorkloadParser/WorkloadParser.java (rewrite from root prototype)
├── compiled/
│   ├── UserService/   (javac output + config.json copy)
│   ├── ProductService/ (javac output + config.json copy)
│   ├── OrderService/   (javac output + config.json copy)
│   ├── ISCS/           (javac output + config.json copy)
│   └── WorkloadParser/ (javac output + config.json copy)
├── tests/
│   ├── workload3u20c.txt
│   ├── combinedWorkload.txt
│   └── workloadOrder.txt
└── docs/
    └── writeup.pdf  (placeholder — fill in manually)
```

---

## Shared Helper: Config Parser

Every service needs to find its own section in `config.json`. Use this pattern (copy into each service's main class — no shared jar needed):

```java
// Reads one service's block: {"ip": "...", "port": N}
private static Map<String, String> loadServiceConfig(String filename, String section) {
    try {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(filename))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append("\n");
        }
        String content = sb.toString();
        int sectionIdx = content.indexOf("\"" + section + "\"");
        if (sectionIdx < 0) throw new RuntimeException("Section not found: " + section);
        int blockStart = content.indexOf("{", sectionIdx);
        int blockEnd   = content.indexOf("}", blockStart);
        String block   = content.substring(blockStart, blockEnd + 1);
        Map<String, String> cfg = new HashMap<>();
        java.util.regex.Matcher pm = java.util.regex.Pattern.compile("\"port\"\\s*:\\s*(\\d+)").matcher(block);
        if (pm.find()) cfg.put("port", pm.group(1));
        java.util.regex.Matcher im = java.util.regex.Pattern.compile("\"ip\"\\s*:\\s*\"([^\"]+)\"").matcher(block);
        if (im.find()) cfg.put("ip", im.group(1));
        return cfg;
    } catch (IOException e) { throw new RuntimeException("Failed to load config", e); }
}
```

**Imports required for all services:**
```java
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
```

---

## Task 1: Directory Structure + config.json

**Files:**
- Create: `a1/` directory tree
- Create: `a1/config.json`

- [ ] **Step 1: Create all directories**

```bash
mkdir -p a1/src/UserService a1/src/ProductService a1/src/OrderService \
         a1/src/ISCS a1/src/WorkloadParser \
         a1/compiled/UserService a1/compiled/ProductService \
         a1/compiled/OrderService a1/compiled/ISCS a1/compiled/WorkloadParser \
         a1/tests a1/docs
```

- [ ] **Step 2: Write a1/config.json**

```json
{
    "UserService": {
        "port": 14001,
        "ip": "127.0.0.1"
    },
    "ProductService": {
        "port": 15000,
        "ip": "127.0.0.1"
    },
    "OrderService": {
        "port": 14000,
        "ip": "127.0.0.1"
    },
    "InterServiceCommunication": {
        "port": 16000,
        "ip": "127.0.0.1"
    }
}
```

- [ ] **Step 3: Copy test workloads into tests/**

```bash
cp workload3u20c.txt combinedWorkload.txt workloadOrder.txt ANNOTATEDworkload3u20c.txt a1/tests/
```

- [ ] **Step 4: Commit**

```bash
git add a1/
git commit -m "feat: add a1 directory structure and config.json"
```

---

## Task 2: UserService — SHA-256 hashing + fixed config parser

**Files:**
- Create: `a1/src/UserService/UserService.java`

Key behaviors:
- Passwords stored as lowercase SHA-256 hex digest
- Delete compares `sha256(providedPassword)` against stored hash
- Config parser reads the `"UserService"` block specifically
- `sendResponse` uses `body.getBytes(UTF_8).length` (not `body.length()`)

- [ ] **Step 1: Write the failing manual test**

Start a test UserService on port 14001 (temp — skip for now; verify behavior via curl in Step 4 after writing the code).

- [ ] **Step 2: Write a1/src/UserService/UserService.java**

```java
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

public class UserService {
    private static final ConcurrentHashMap<Integer, User> userStore = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        if (args.length != 1) { System.err.println("Usage: java UserService config.json"); return; }
        Map<String, String> cfg = loadServiceConfig(args[0], "UserService");
        int port = Integer.parseInt(cfg.get("port"));

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(10));
        server.createContext("/user", new UserHandler());
        server.start();
        System.out.println("UserService started on port " + port);
    }

    private static Map<String, String> loadServiceConfig(String filename, String section) {
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new FileReader(filename))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append("\n");
            }
            String content = sb.toString();
            int sectionIdx = content.indexOf("\"" + section + "\"");
            if (sectionIdx < 0) throw new RuntimeException("Section not found: " + section);
            int blockStart = content.indexOf("{", sectionIdx);
            int blockEnd   = content.indexOf("}", blockStart);
            String block   = content.substring(blockStart, blockEnd + 1);
            Map<String, String> cfg = new HashMap<>();
            java.util.regex.Matcher pm = java.util.regex.Pattern.compile("\"port\"\\s*:\\s*(\\d+)").matcher(block);
            if (pm.find()) cfg.put("port", pm.group(1));
            java.util.regex.Matcher im = java.util.regex.Pattern.compile("\"ip\"\\s*:\\s*\"([^\"]+)\"").matcher(block);
            if (im.find()) cfg.put("ip", im.group(1));
            return cfg;
        } catch (IOException e) { throw new RuntimeException("Failed to load config", e); }
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) { throw new RuntimeException(e); }
    }

    static class UserHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String method = ex.getRequestMethod();
            String path   = ex.getRequestURI().getPath();
            if ("POST".equalsIgnoreCase(method)) {
                handlePost(ex);
            } else if ("GET".equalsIgnoreCase(method) && path.matches("/user/\\d+")) {
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
                case "create": createUser(ex, body, id); break;
                case "update": updateUser(ex, body, id); break;
                case "delete": deleteUser(ex, body, id); break;
                default: sendResponse(ex, 400, "{}");
            }
        }

        private void createUser(HttpExchange ex, String body, int id) throws IOException {
            String username = extractValue(body, "username");
            String email    = extractValue(body, "email");
            String password = extractValue(body, "password");
            if (username == null || email == null || password == null ||
                username.isEmpty() || email.isEmpty() || password.isEmpty()) {
                sendResponse(ex, 400, "{}"); return;
            }
            if (userStore.containsKey(id)) { sendResponse(ex, 409, "{}"); return; }
            userStore.put(id, new User(id, username, email, sha256(password)));
            sendResponse(ex, 200, "{}");
        }

        private void updateUser(HttpExchange ex, String body, int id) throws IOException {
            if (!userStore.containsKey(id)) { sendResponse(ex, 404, "{}"); return; }
            User existing = userStore.get(id);
            String username = extractValue(body, "username");
            String email    = extractValue(body, "email");
            String password = extractValue(body, "password");
            // keep existing if field absent or empty
            if (username == null || username.isEmpty()) username = existing.username;
            if (email    == null || email.isEmpty())    email    = existing.email;
            String storedPassword;
            if (password == null || password.isEmpty()) {
                storedPassword = existing.password;
            } else {
                storedPassword = sha256(password);
            }
            userStore.put(id, new User(id, username, email, storedPassword));
            sendResponse(ex, 200, "{}");
        }

        private void deleteUser(HttpExchange ex, String body, int id) throws IOException {
            String username = extractValue(body, "username");
            String email    = extractValue(body, "email");
            String password = extractValue(body, "password");
            if (username == null || email == null || password == null) { sendResponse(ex, 400, "{}"); return; }
            if (!userStore.containsKey(id)) { sendResponse(ex, 404, "{}"); return; }
            User existing = userStore.get(id);
            if (existing.username.equals(username) &&
                existing.email.equals(email) &&
                existing.password.equals(sha256(password))) {
                userStore.remove(id);
                sendResponse(ex, 200, "{}");
            } else {
                sendResponse(ex, 401, "{}");
            }
        }

        private void handleGet(HttpExchange ex) throws IOException {
            int id = Integer.parseInt(ex.getRequestURI().getPath().replace("/user/", ""));
            if (!userStore.containsKey(id)) { sendResponse(ex, 404, "{}"); return; }
            User u = userStore.get(id);
            String resp = String.format("{\"id\":%d,\"username\":\"%s\",\"email\":\"%s\"}", u.id, u.username, u.email);
            sendResponse(ex, 200, resp);
        }

        private String readBody(HttpExchange ex) throws IOException {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
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

    static class User {
        int id; String username, email, password;
        User(int id, String username, String email, String password) {
            this.id = id; this.username = username; this.email = email; this.password = password;
        }
    }
}
```

- [ ] **Step 3: Compile and verify it compiles cleanly (0 errors, 0 warnings)**

```bash
javac -d a1/compiled/UserService a1/src/UserService/UserService.java
```

Expected: exits 0, class files appear in `a1/compiled/UserService/`

- [ ] **Step 4: Copy config and smoke-test manually**

```bash
cp a1/config.json a1/compiled/UserService/
cd a1/compiled/UserService && java UserService config.json &
sleep 1

# Create
curl -s -X POST http://localhost:14001/user \
  -d '{"command":"create","id":1,"username":"alice","email":"a@b.com","password":"secret"}'
# Expected: HTTP 200

# Get
curl -s http://localhost:14001/user/1
# Expected: {"id":1,"username":"alice","email":"a@b.com"}

# Delete with wrong password
curl -s -X POST http://localhost:14001/user \
  -d '{"command":"delete","id":1,"username":"alice","email":"a@b.com","password":"WRONG"}'
# Expected: HTTP 401

# Delete with correct password
curl -s -X POST http://localhost:14001/user \
  -d '{"command":"delete","id":1,"username":"alice","email":"a@b.com","password":"secret"}'
# Expected: HTTP 200

# Confirm gone
curl -s http://localhost:14001/user/1
# Expected: HTTP 404

kill %1   # stop background server
cd -
```

- [ ] **Step 5: Commit**

```bash
git add a1/src/UserService/UserService.java
git commit -m "feat: add UserService with SHA-256 hashing and correct config parser"
```

---

## Task 3: ProductService — description field + fixed config parser

**Files:**
- Create: `a1/src/ProductService/ProductService.java`

Key behaviors:
- Product has `id, productName, description, price, quantity`
- GET response does NOT include description (matches spec)
- Delete checks: productName, price, quantity must match (not description)
- Config parser reads the `"ProductService"` block

- [ ] **Step 1: Write a1/src/ProductService/ProductService.java**

```java
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
```

- [ ] **Step 2: Compile**

```bash
javac -d a1/compiled/ProductService a1/src/ProductService/ProductService.java
```

Expected: exits 0, class files in `a1/compiled/ProductService/`

- [ ] **Step 3: Smoke test**

```bash
cp a1/config.json a1/compiled/ProductService/
cd a1/compiled/ProductService && java ProductService config.json &
sleep 1

# Create
curl -s -X POST http://localhost:15000/product \
  -d '{"command":"create","id":10,"productname":"widget","description":"a widget","price":3.99,"quantity":5}'
# Expected: HTTP 200

# Get — description NOT in response
curl -s http://localhost:15000/product/10
# Expected: {"id":10,"productname":"widget","price":3.99,"quantity":5}

# Delete — all fields match
curl -s -X POST http://localhost:15000/product \
  -d '{"command":"delete","id":10,"productname":"widget","price":3.99,"quantity":5}'
# Expected: HTTP 200

kill %1
cd -
```

- [ ] **Step 4: Commit**

```bash
git add a1/src/ProductService/ProductService.java
git commit -m "feat: add ProductService with description field and correct config parser"
```

---

## Task 4: ISCS — routing and load-balancing scaffold

**Files:**
- Create: `a1/src/ISCS/ISCS.java`

The ISCS receives requests from OrderService and forwards them to the correct backend. It reads its own port from the `"InterServiceCommunication"` config section and reads UserService/ProductService locations. For A2 extensibility, the backend targets are stored in lists so multiple instances can be added later (round-robin index).

- [ ] **Step 1: Write a1/src/ISCS/ISCS.java**

```java
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class ISCS {
    private static String userIp   = "127.0.0.1";
    private static int    userPort = 14001;
    private static String productIp   = "127.0.0.1";
    private static int    productPort = 15000;

    // AtomicIntegers ready for A2 round-robin (unused for A1 single-instance)
    private static final AtomicInteger userIdx    = new AtomicInteger(0);
    private static final AtomicInteger productIdx = new AtomicInteger(0);

    public static void main(String[] args) throws IOException {
        if (args.length != 1) { System.err.println("Usage: java ISCS config.json"); return; }
        String cfg = args[0];
        Map<String, String> own  = loadServiceConfig(cfg, "InterServiceCommunication");
        Map<String, String> usr  = loadServiceConfig(cfg, "UserService");
        Map<String, String> prod = loadServiceConfig(cfg, "ProductService");

        int port = Integer.parseInt(own.get("port"));
        userIp      = usr.get("ip");
        userPort    = Integer.parseInt(usr.get("port"));
        productIp   = prod.get("ip");
        productPort = Integer.parseInt(prod.get("port"));

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(20));
        server.createContext("/user",    new ForwardHandler("user"));
        server.createContext("/product", new ForwardHandler("product"));
        server.start();
        System.out.println("ISCS started on port " + port);
        System.out.println("  -> UserService    at " + userIp    + ":" + userPort);
        System.out.println("  -> ProductService at " + productIp + ":" + productPort);
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

    static class ForwardHandler implements HttpHandler {
        private final String service; // "user" or "product"

        ForwardHandler(String service) { this.service = service; }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            String method  = ex.getRequestMethod();
            String path    = ex.getRequestURI().getPath();
            String query   = ex.getRequestURI().getQuery();

            String backendBase = "user".equals(service)
                ? "http://" + userIp    + ":" + userPort
                : "http://" + productIp + ":" + productPort;

            String targetUrl = backendBase + path + (query != null ? "?" + query : "");

            String reqBody = null;
            if ("POST".equalsIgnoreCase(method)) {
                reqBody = readBody(ex);
            }

            HttpResp resp;
            if ("GET".equalsIgnoreCase(method)) {
                resp = doGet(targetUrl);
            } else if ("POST".equalsIgnoreCase(method)) {
                resp = doPost(targetUrl, reqBody);
            } else {
                sendResponse(ex, 405, "{}");
                return;
            }

            sendResponse(ex, resp.code, resp.body);
        }

        private HttpResp doGet(String url) {
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

        private HttpResp doPost(String url, String body) {
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

        private String readStream(InputStream is) throws IOException {
            if (is == null) return "{}";
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder(); String line;
                while ((line = br.readLine()) != null) sb.append(line);
                return sb.toString();
            }
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
    }

    static class HttpResp { int code; String body; }
}
```

- [ ] **Step 2: Compile**

```bash
javac -d a1/compiled/ISCS a1/src/ISCS/ISCS.java
```

Expected: exits 0

- [ ] **Step 3: Smoke test ISCS routing**

```bash
cp a1/config.json a1/compiled/ISCS/
# Start UserService and ProductService first (from Tasks 2 & 3)
cp a1/config.json a1/compiled/UserService/ && (cd a1/compiled/UserService && java UserService config.json) &
cp a1/config.json a1/compiled/ProductService/ && (cd a1/compiled/ProductService && java ProductService config.json) &
sleep 1
# Start ISCS
(cd a1/compiled/ISCS && java ISCS config.json) &
sleep 1

# Create user via ISCS (/user path)
curl -s -X POST http://localhost:16000/user \
  -d '{"command":"create","id":99,"username":"bob","email":"bob@x.com","password":"pass"}'
# Expected: HTTP 200

# Get user via ISCS
curl -s http://localhost:16000/user/99
# Expected: {"id":99,"username":"bob","email":"bob@x.com"}

# Create product via ISCS
curl -s -X POST http://localhost:16000/product \
  -d '{"command":"create","id":99,"productname":"gadget","description":"desc","price":9.99,"quantity":10}'
# Expected: HTTP 200

kill %1 %2 %3
```

- [ ] **Step 4: Commit**

```bash
git add a1/src/ISCS/ISCS.java
git commit -m "feat: add ISCS routing service with round-robin scaffold"
```

---

## Task 5: OrderService — fixed config parser

**Files:**
- Create: `a1/src/OrderService/OrderService.java`

OrderService is the public-facing endpoint. It:
- Reads its own port and the ISCS address from config
- Serves `/order` POST (place order: verify user exists, verify product exists + has stock, deduct stock, record order)
- Serves `/user` and `/product` as pass-through proxies to ISCS
- All inter-service calls go to ISCS (never directly to UserService/ProductService)

- [ ] **Step 1: Write a1/src/OrderService/OrderService.java**

```java
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

    private static int orderServicePort = 14000;
    private static String iscsIp = "127.0.0.1";
    private static int iscsPort = 16000;

    public static void main(String[] args) throws IOException {
        if (args.length != 1) { System.err.println("Usage: java OrderService config.json"); return; }
        Map<String, String> order = loadServiceConfig(args[0], "OrderService");
        Map<String, String> iscs  = loadServiceConfig(args[0], "InterServiceCommunication");
        orderServicePort = Integer.parseInt(order.get("port"));
        iscsIp   = iscs.get("ip");
        iscsPort = Integer.parseInt(iscs.get("port"));

        HttpServer server = HttpServer.create(new InetSocketAddress(orderServicePort), 0);
        server.setExecutor(Executors.newFixedThreadPool(10));
        server.createContext("/order",   new OrderHandler());
        server.createContext("/user",    new PassThroughHandler());
        server.createContext("/product", new PassThroughHandler());
        server.start();
        System.out.println("OrderService started on port " + orderServicePort);
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

- [ ] **Step 2: Compile**

```bash
javac -d a1/compiled/OrderService a1/src/OrderService/OrderService.java
```

Expected: exits 0

- [ ] **Step 3: Integration test — full chain (UserService → ISCS → OrderService)**

```bash
cp a1/config.json a1/compiled/OrderService/
# Start all 4 services (UserService, ProductService, ISCS already running from above, or restart)
(cd a1/compiled/UserService    && java UserService    config.json) &
(cd a1/compiled/ProductService && java ProductService config.json) &
(cd a1/compiled/ISCS           && java ISCS           config.json) &
sleep 1
(cd a1/compiled/OrderService   && java OrderService   config.json) &
sleep 1

# Create user via OrderService (goes: OrderService -> ISCS -> UserService)
curl -s -X POST http://localhost:14000/user \
  -d '{"command":"create","id":1,"username":"alice","email":"a@b.com","password":"pass"}'
# Expected: HTTP 200

# Create product via OrderService
curl -s -X POST http://localhost:14000/product \
  -d '{"command":"create","id":1,"productname":"widget","description":"x","price":5.00,"quantity":10}'
# Expected: HTTP 200

# Place order
curl -s -X POST http://localhost:14000/order \
  -d '{"command":"place order","user_id":1,"product_id":1,"quantity":3}'
# Expected: HTTP 200

# Confirm stock reduced
curl -s http://localhost:14000/product/1
# Expected: quantity is 7

kill %1 %2 %3 %4
```

- [ ] **Step 4: Commit**

```bash
git add a1/src/OrderService/OrderService.java
git commit -m "feat: add OrderService with corrected config parser"
```

---

## Task 6: WorkloadParser — fix ORDER tokens, description, comments, config

**Files:**
- Create: `a1/src/WorkloadParser/WorkloadParser.java`

Bugs fixed vs prototype:
1. Inline comments (`# ...`) stripped before token split
2. `ORDER place` tokens: `product_id` is tokens[2], `user_id` is tokens[3], `quantity` is tokens[4] (optional, default 1)
3. `PRODUCT create` includes `description` as tokens[4]; price=tokens[5], quantity=tokens[6]
4. WorkloadParser accepts `config.json` as first arg, workload file as second arg, reads OrderService ip/port from config

- [ ] **Step 1: Write a1/src/WorkloadParser/WorkloadParser.java**

```java
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class WorkloadParser {
    private static String orderHost = "http://127.0.0.1";
    private static int    orderPort = 14000;

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: java WorkloadParser config.json workloadfile");
            return;
        }
        Map<String, String> cfg = loadServiceConfig(args[0], "OrderService");
        orderHost = "http://" + cfg.get("ip");
        orderPort = Integer.parseInt(cfg.get("port"));

        try (BufferedReader br = new BufferedReader(new FileReader(args[1]))) {
            String line;
            while ((line = br.readLine()) != null) {
                // strip inline comments
                int commentIdx = line.indexOf('#');
                if (commentIdx >= 0) line = line.substring(0, commentIdx);
                line = line.trim();
                if (line.isEmpty()) continue;

                String[] tokens = line.split("\\s+");
                if (tokens.length < 2) continue;
                switch (tokens[0].toUpperCase()) {
                    case "USER":    handleUser(tokens);    break;
                    case "PRODUCT": handleProduct(tokens); break;
                    case "ORDER":   handleOrder(tokens);   break;
                    default: System.out.println("Skipping: " + tokens[0]);
                }
            }
        } catch (IOException e) { e.printStackTrace(); }
    }

    private static void handleUser(String[] tokens) {
        if (tokens.length < 2) return;
        String action = tokens[1].toLowerCase();
        try {
            switch (action) {
                case "create":
                    if (tokens.length < 6) return;
                    doPost("/user", String.format(
                        "{\"command\":\"create\",\"id\":%d,\"username\":\"%s\",\"email\":\"%s\",\"password\":\"%s\"}",
                        Integer.parseInt(tokens[2]), tokens[3], tokens[4], tokens[5]));
                    break;
                case "update":
                    if (tokens.length < 3) return;
                    int updateId = Integer.parseInt(tokens[2]);
                    String uname = extractField(tokens, "username:");
                    String uemail = extractField(tokens, "email:");
                    String upass  = extractField(tokens, "password:");
                    // Only include non-empty fields
                    StringBuilder upJson = new StringBuilder("{\"command\":\"update\",\"id\":").append(updateId);
                    if (!uname.isEmpty())  upJson.append(",\"username\":\"").append(uname).append("\"");
                    if (!uemail.isEmpty()) upJson.append(",\"email\":\"").append(uemail).append("\"");
                    if (!upass.isEmpty())  upJson.append(",\"password\":\"").append(upass).append("\"");
                    upJson.append("}");
                    doPost("/user", upJson.toString());
                    break;
                case "delete":
                    if (tokens.length < 6) return;
                    doPost("/user", String.format(
                        "{\"command\":\"delete\",\"id\":%d,\"username\":\"%s\",\"email\":\"%s\",\"password\":\"%s\"}",
                        Integer.parseInt(tokens[2]), tokens[3], tokens[4], tokens[5]));
                    break;
                case "get":
                    if (tokens.length < 3) return;
                    doGet("/user/" + tokens[2]);
                    break;
            }
        } catch (Exception e) { System.out.println("USER error: " + e.getMessage()); }
    }

    private static void handleProduct(String[] tokens) {
        if (tokens.length < 2) return;
        String action = tokens[1].toLowerCase();
        try {
            switch (action) {
                case "create":
                    // PRODUCT create <id> <name> <description> <price> <quantity>
                    if (tokens.length < 7) return;
                    doPost("/product", String.format(
                        "{\"command\":\"create\",\"id\":%d,\"productname\":\"%s\",\"description\":\"%s\",\"price\":%s,\"quantity\":%s}",
                        Integer.parseInt(tokens[2]), tokens[3], tokens[4], tokens[5], tokens[6]));
                    break;
                case "update":
                    if (tokens.length < 3) return;
                    int updateId = Integer.parseInt(tokens[2]);
                    String pname = extractField(tokens, "name:");
                    String pdesc = extractField(tokens, "description:");
                    String pprice = extractField(tokens, "price:");
                    String pqty  = extractField(tokens, "quantity:");
                    StringBuilder upJson = new StringBuilder("{\"command\":\"update\",\"id\":").append(updateId);
                    if (!pname.isEmpty())  upJson.append(",\"productname\":\"").append(pname).append("\"");
                    if (!pdesc.isEmpty())  upJson.append(",\"description\":\"").append(pdesc).append("\"");
                    if (!pprice.isEmpty()) upJson.append(",\"price\":").append(pprice);
                    if (!pqty.isEmpty())   upJson.append(",\"quantity\":").append(pqty);
                    upJson.append("}");
                    doPost("/product", upJson.toString());
                    break;
                case "delete":
                    // PRODUCT delete <id> <name> <price> <quantity>
                    if (tokens.length < 6) return;
                    doPost("/product", String.format(
                        "{\"command\":\"delete\",\"id\":%d,\"productname\":\"%s\",\"price\":%s,\"quantity\":%s}",
                        Integer.parseInt(tokens[2]), tokens[3], tokens[4], tokens[5]));
                    break;
                case "info":
                    if (tokens.length < 3) return;
                    doGet("/product/" + tokens[2]);
                    break;
            }
        } catch (Exception e) { System.out.println("PRODUCT error: " + e.getMessage()); }
    }

    private static void handleOrder(String[] tokens) {
        if (tokens.length < 2) return;
        String action = tokens[1].toLowerCase();
        try {
            if ("place".equals(action)) {
                // ORDER place <product_id> <user_id> [<quantity>]
                if (tokens.length < 4) return;
                int productId = Integer.parseInt(tokens[2]);
                int userId    = Integer.parseInt(tokens[3]);
                int quantity  = (tokens.length >= 5) ? Integer.parseInt(tokens[4]) : 1;
                doPost("/order", String.format(
                    "{\"command\":\"place order\",\"user_id\":%d,\"product_id\":%d,\"quantity\":%d}",
                    userId, productId, quantity));
            }
        } catch (Exception e) { System.out.println("ORDER error: " + e.getMessage()); }
    }

    private static String extractField(String[] tokens, String prefix) {
        for (String t : tokens) {
            if (t.startsWith(prefix)) return t.substring(prefix.length());
        }
        return "";
    }

    private static void doPost(String endpoint, String body) {
        try {
            URL url = new URL(orderHost + ":" + orderPort + endpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            conn.getOutputStream().close();
            int code = conn.getResponseCode();
            String resp = readResponse(conn);
            System.out.println(endpoint + " -> " + code + " : " + resp);
            conn.disconnect();
        } catch (IOException e) { System.out.println("POST failed: " + e.getMessage()); }
    }

    private static void doGet(String endpoint) {
        try {
            URL url = new URL(orderHost + ":" + orderPort + endpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            String resp = readResponse(conn);
            System.out.println(endpoint + " -> " + code + " : " + resp);
            conn.disconnect();
        } catch (IOException e) { System.out.println("GET failed: " + e.getMessage()); }
    }

    private static String readResponse(HttpURLConnection conn) throws IOException {
        InputStream is = (conn.getResponseCode() < 400) ? conn.getInputStream() : conn.getErrorStream();
        if (is == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder(); String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
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
}
```

- [ ] **Step 2: Compile**

```bash
javac -d a1/compiled/WorkloadParser a1/src/WorkloadParser/WorkloadParser.java
```

Expected: exits 0

- [ ] **Step 3: Commit**

```bash
git add a1/src/WorkloadParser/WorkloadParser.java
git commit -m "feat: add WorkloadParser with fixed ORDER tokens, description, comment stripping"
```

---

## Task 7: runme.sh

**Files:**
- Create: `a1/runme.sh`

- [ ] **Step 1: Write a1/runme.sh**

```bash
#!/bin/bash
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

usage() {
    echo "Usage: $0 [-c|-u|-p|-i|-o|-w workloadfile]"
    echo "  -c              Compile all services"
    echo "  -u              Start UserService"
    echo "  -p              Start ProductService"
    echo "  -i              Start ISCS"
    echo "  -o              Start OrderService"
    echo "  -w workloadfile Run WorkloadParser on workloadfile"
    exit 1
}

case "$1" in
    -c)
        echo "=== Compiling all services ==="
        javac -d "$SCRIPT_DIR/compiled/UserService"    "$SCRIPT_DIR/src/UserService/UserService.java"
        javac -d "$SCRIPT_DIR/compiled/ProductService" "$SCRIPT_DIR/src/ProductService/ProductService.java"
        javac -d "$SCRIPT_DIR/compiled/OrderService"   "$SCRIPT_DIR/src/OrderService/OrderService.java"
        javac -d "$SCRIPT_DIR/compiled/ISCS"           "$SCRIPT_DIR/src/ISCS/ISCS.java"
        javac -d "$SCRIPT_DIR/compiled/WorkloadParser" "$SCRIPT_DIR/src/WorkloadParser/WorkloadParser.java"
        # Copy config.json into each compiled directory
        for svc in UserService ProductService OrderService ISCS WorkloadParser; do
            cp "$SCRIPT_DIR/config.json" "$SCRIPT_DIR/compiled/$svc/config.json"
        done
        echo "=== Compilation complete ==="
        ;;
    -u)
        cd "$SCRIPT_DIR/compiled/UserService" && java UserService config.json
        ;;
    -p)
        cd "$SCRIPT_DIR/compiled/ProductService" && java ProductService config.json
        ;;
    -i)
        cd "$SCRIPT_DIR/compiled/ISCS" && java ISCS config.json
        ;;
    -o)
        cd "$SCRIPT_DIR/compiled/OrderService" && java OrderService config.json
        ;;
    -w)
        if [ -z "$2" ]; then usage; fi
        WORKLOAD="$2"
        # Resolve relative path before cd
        [[ "$WORKLOAD" != /* ]] && WORKLOAD="$(pwd)/$WORKLOAD"
        cd "$SCRIPT_DIR/compiled/WorkloadParser" && java WorkloadParser config.json "$WORKLOAD"
        ;;
    *)
        usage
        ;;
esac
```

- [ ] **Step 2: Make executable**

```bash
chmod +x a1/runme.sh
```

- [ ] **Step 3: Test compilation via runme.sh**

```bash
./a1/runme.sh -c
```

Expected: no errors, class files appear in each `a1/compiled/<ServiceName>/` directory.

- [ ] **Step 4: Commit**

```bash
git add a1/runme.sh
git commit -m "feat: add runme.sh with compile and start commands"
```

---

## Task 8: Full Integration Test with Provided Workloads

This task verifies the whole system end-to-end using the provided workload files.

- [ ] **Step 1: Compile fresh**

```bash
./a1/runme.sh -c
```

- [ ] **Step 2: Start all 4 services in 4 separate terminal tabs**

Tab 1: `./a1/runme.sh -i`  (start ISCS first — others connect to it)
Tab 2: `./a1/runme.sh -u`
Tab 3: `./a1/runme.sh -p`
Tab 4: `./a1/runme.sh -o`

- [ ] **Step 3: Run the user-only workload**

```bash
./a1/runme.sh -w a1/tests/workload3u20c.txt
```

Verify against `ANNOTATEDworkload3u20c.txt`. Expected behavior:
- `USER create 2/3/4` → 200 each
- `USER get 2` → 200 with user data
- `USER update 2 username:un-4Vofkp8EyolNJYJ ...` → 200
- `USER delete 2 4Vofkp8EyolNJYJ ...` → **401** (username doesn't match updated "un-4Vofkp8EyolNJYJ")
- Subsequent `USER update 2 / delete 2` → 404 is only if delete succeeded; since it failed → 200 (user 2 still exists)
- `USER get 3` → 200

- [ ] **Step 4: Run the combined workload**

```bash
./a1/runme.sh -w a1/tests/combinedWorkload.txt
```

Verify key scenarios:
- Product create/update/delete round-trips work
- `ORDER place` with valid user + product reduces quantity
- `ORDER place` with nonexistent product_id 9993 → 404
- `ORDER place 3 2000` → 400 (insufficient quantity)

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "test: verify integration with provided workload files"
```

---

## Self-Review

**Spec coverage check:**

| Requirement | Covered in |
|------------|-----------|
| UserService create/update/delete/get with SHA-256 | Task 2 |
| ProductService create/update/delete/get with description | Task 3 |
| ISCS routing /user → UserService, /product → ProductService | Task 4 |
| OrderService public endpoint with /user, /product, /order | Task 5 |
| OrderService places order: checks stock, reduces quantity | Task 5 (logic from prototype, preserved) |
| WorkloadParser reads workload and sends to OrderService | Task 6 |
| runme.sh -c/-u/-p/-i/-o/-w | Task 7 |
| config.json with all 4 service sections | Task 1 |
| Submission directory structure a1/src, a1/compiled, a1/tests, a1/docs | Tasks 1-7 |
| Persistence (shutdown/restart requirement) | **NOT YET — coming 1 week before due date** |

**Placeholder scan:** None. All code blocks are complete.

**Type consistency:** `User(id, username, email, password)` matches across Task 2. `Product(id, productName, description, price, quantity)` matches across Task 3. ISCS `HttpResp` is local only. `loadServiceConfig` signature identical across all 5 files.

**Known limitation (write up):** SHA-256 delete comparison: the workload's `USER delete 2 4Vofkp8EyolNJYJ ...` will return 401 (username mismatch with stored "un-4Vofkp8EyolNJYJ") — this may be intentional test behavior, not a bug.

---

## Persistence Preparation Note

The instructions hint that persistence (shutdown/restart) will be added 1 week before the due date. Design decision for now:

- All storage is in `ConcurrentHashMap` (easy to serialize)
- When the requirement arrives, add a `shutdown` hook that writes JSON snapshots to disk, and on startup, load from disk if snapshots exist
- Do NOT add this now — YAGNI

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-15-microservices-a1.md`.

**Two execution options:**

**1. Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks

**2. Inline Execution** — execute tasks in this session using executing-plans, with checkpoints

**Which approach?**
