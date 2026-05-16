import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;

public class UserService {

    // In-memory user store: id -> User
    private static final ConcurrentHashMap<Integer, User> users = new ConcurrentHashMap<>();

    // Simple User record
    static class User {
        int id;
        String username;
        String email;
        String passwordHash; // lowercase SHA-256 hex

        User(int id, String username, String email, String passwordHash) {
            this.id = id;
            this.username = username;
            this.email = email;
            this.passwordHash = passwordHash;
        }
    }

    // ------------------------------------------------------------------ config

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

    // --------------------------------------------------------------- SHA-256

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // --------------------------------------------------------------- JSON helpers

    /**
     * Extract a value from a flat JSON object by key.
     * Handles both quoted string values and unquoted numeric values.
     * Returns null if the key is absent.
     */
    private static String extractValue(String json, String key) {
        // Try quoted string value first: "key"\s*:\s*"value"
        java.util.regex.Pattern strPat = java.util.regex.Pattern.compile(
                "\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        java.util.regex.Matcher sm = strPat.matcher(json);
        if (sm.find()) return sm.group(1);

        // Try numeric value: "key"\s*:\s*digits
        java.util.regex.Pattern numPat = java.util.regex.Pattern.compile(
                "\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*(\\d+)");
        java.util.regex.Matcher nm = numPat.matcher(json);
        if (nm.find()) return nm.group(1);

        return null;
    }

    // --------------------------------------------------------------- HTTP helpers

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void sendResponse(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // --------------------------------------------------------------- handlers

    private static void handlePost(HttpExchange ex) throws IOException {
        String body = readBody(ex);
        String command = extractValue(body, "command");
        if (command == null) {
            sendResponse(ex, 400, "{\"error\":\"missing command\"}");
            return;
        }
        switch (command) {
            case "create": createUser(ex, body); break;
            case "update": updateUser(ex, body); break;
            case "delete": deleteUser(ex, body); break;
            default: sendResponse(ex, 400, "{\"error\":\"unknown command\"}");
        }
    }

    private static void createUser(HttpExchange ex, String body) throws IOException {
        String idStr    = extractValue(body, "id");
        String username = extractValue(body, "username");
        String email    = extractValue(body, "email");
        String password = extractValue(body, "password");

        if (idStr == null || username == null || username.isEmpty()
                || email == null || email.isEmpty()
                || password == null || password.isEmpty()) {
            sendResponse(ex, 400, "{\"error\":\"missing required fields\"}");
            return;
        }

        int id;
        try { id = Integer.parseInt(idStr); } catch (NumberFormatException e) {
            sendResponse(ex, 400, "{\"error\":\"invalid id\"}");
            return;
        }

        User newUser = new User(id, username, email, sha256(password));
        if (users.putIfAbsent(id, newUser) != null) {
            sendResponse(ex, 409, "{\"error\":\"user already exists\"}");
            return;
        }
        sendResponse(ex, 200, "{\"id\":" + id + ",\"username\":\"" + username + "\",\"email\":\"" + email + "\"}");
    }

    private static void updateUser(HttpExchange ex, String body) throws IOException {
        String idStr    = extractValue(body, "id");
        String username = extractValue(body, "username");
        String email    = extractValue(body, "email");
        String password = extractValue(body, "password");

        if (idStr == null) {
            sendResponse(ex, 400, "{\"error\":\"missing id\"}");
            return;
        }

        int id;
        try { id = Integer.parseInt(idStr); } catch (NumberFormatException e) {
            sendResponse(ex, 400, "{\"error\":\"invalid id\"}");
            return;
        }

        User existing = users.get(id);
        if (existing == null) {
            sendResponse(ex, 404, "{\"error\":\"user not found\"}");
            return;
        }

        // Apply updates — keep existing value when incoming field is null/empty
        if (username != null && !username.isEmpty()) existing.username = username;
        if (email    != null && !email.isEmpty())    existing.email    = email;
        if (password != null && !password.isEmpty()) existing.passwordHash = sha256(password);

        sendResponse(ex, 200, "{\"id\":" + existing.id + ",\"username\":\"" + existing.username + "\",\"email\":\"" + existing.email + "\"}");
    }

    private static void deleteUser(HttpExchange ex, String body) throws IOException {
        String idStr    = extractValue(body, "id");
        String username = extractValue(body, "username");
        String email    = extractValue(body, "email");
        String password = extractValue(body, "password");

        if (idStr == null || username == null || email == null || password == null) {
            sendResponse(ex, 400, "{\"error\":\"missing required fields\"}");
            return;
        }

        int id;
        try { id = Integer.parseInt(idStr); } catch (NumberFormatException e) {
            sendResponse(ex, 400, "{\"error\":\"invalid id\"}");
            return;
        }

        User existing = users.get(id);
        if (existing == null) {
            sendResponse(ex, 404, "{\"error\":\"user not found\"}");
            return;
        }

        if (!sha256(password).equals(existing.passwordHash)) {
            sendResponse(ex, 401, "{\"error\":\"unauthorized\"}");
            return;
        }

        users.remove(id);
        sendResponse(ex, 200, "{\"message\":\"user deleted\"}");
    }

    private static void handleGet(HttpExchange ex) throws IOException {
        // Path: /user/<id>
        String path = ex.getRequestURI().getPath(); // e.g. /user/1
        String[] parts = path.split("/");
        // parts[0]="", parts[1]="user", parts[2]=id
        if (parts.length < 3) {
            sendResponse(ex, 400, "{\"error\":\"missing id in path\"}");
            return;
        }
        int id;
        try { id = Integer.parseInt(parts[2]); } catch (NumberFormatException e) {
            sendResponse(ex, 400, "{\"error\":\"invalid id\"}");
            return;
        }

        User u = users.get(id);
        if (u == null) {
            sendResponse(ex, 404, "{\"error\":\"user not found\"}");
            return;
        }
        sendResponse(ex, 200, "{\"id\":" + u.id + ",\"username\":\"" + u.username + "\",\"email\":\"" + u.email + "\"}");
    }

    // --------------------------------------------------------------- main

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java UserService <config.json>");
            System.exit(1);
        }

        Map<String, String> cfg = loadServiceConfig(args[0], "UserService");
        int port = Integer.parseInt(cfg.get("port"));

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/user", exchange -> {
            try {
                String method = exchange.getRequestMethod();
                if ("POST".equalsIgnoreCase(method)) {
                    handlePost(exchange);
                } else if ("GET".equalsIgnoreCase(method)) {
                    handleGet(exchange);
                } else {
                    sendResponse(exchange, 405, "{\"error\":\"method not allowed\"}");
                }
            } catch (Exception e) {
                e.printStackTrace();
                try { sendResponse(exchange, 500, "{\"error\":\"internal server error\"}"); } catch (IOException ignored) {}
            }
        });

        server.setExecutor(Executors.newFixedThreadPool(10));
        server.start();
        System.out.println("UserService started on port " + port);
    }
}
