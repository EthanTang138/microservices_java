import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public class UserService {
    private static final ConcurrentHashMap<Integer, User> userStore = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: java UserService config.json");
            return;
        }

        String configFile = args[0];
        int port = loadConfigPort(configFile);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(10)); // Thread pool

        server.createContext("/user", new UserHandler());

        server.start();
        System.out.println("UserService started on port " + port);
    }

    private static int loadConfigPort(String filename) {
        try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.contains("\"port\"")) {
                    return Integer.parseInt(line.replaceAll("[^0-9]", ""));
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load configuration file.", e);
        }
        return 8080; // Default fallback
    }

    static class UserHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            if ("POST".equalsIgnoreCase(method)) {
                handlePostRequest(exchange);
            } else if ("GET".equalsIgnoreCase(method) && path.matches("/user/\\d+")) {
                handleGetRequest(exchange);
            } else {
                sendResponse(exchange, 400, "{\"error\":\"Invalid request.\"}");
            }
        }

        private void handlePostRequest(HttpExchange exchange) throws IOException {
            String requestBody = getRequestBody(exchange);

            String command = extractJsonValue(requestBody, "command");
            String idStr = extractJsonValue(requestBody, "id");

            if (command == null || idStr == null) {
                sendResponse(exchange, 400, "{\"error\":\"Missing required fields.\"}");
                return;
            }

            int id = Integer.parseInt(idStr);

            switch (command.toLowerCase()) {
                case "create":
                    createUser(exchange, requestBody, id);
                    break;
                case "update":
                    updateUser(exchange, requestBody, id);
                    break;
                case "delete":
                    deleteUser(exchange, requestBody, id);
                    break;
                default:
                    sendResponse(exchange, 400, "{\"error\":\"Invalid command.\"}");
            }
        }

        private void createUser(HttpExchange exchange, String requestBody, int id) throws IOException {
            String username = extractJsonValue(requestBody, "username");
            String email = extractJsonValue(requestBody, "email");
            String password = extractJsonValue(requestBody, "password");

            if (username == null || email == null || password == null) {
                sendResponse(exchange, 400, "{\"error\":\"Missing user fields.\"}");
                return;
            }

            if (userStore.containsKey(id)) {
                sendResponse(exchange, 409, "{\"error\":\"User ID already exists.\"}");
                return;
            }

            User newUser = new User(id, username, email, password);
            userStore.put(id, newUser);
            sendResponse(exchange, 200, "{\"message\":\"User created.\"}");
        }

        private void updateUser(HttpExchange exchange, String requestBody, int id) throws IOException {
            if (!userStore.containsKey(id)) {
                sendResponse(exchange, 404, "{\"error\":\"User not found.\"}");
                return;
            }

            User existingUser = userStore.get(id);
            String username = extractJsonValue(requestBody, "username");
            String email = extractJsonValue(requestBody, "email");
            String password = extractJsonValue(requestBody, "password");

            if (username == null) username = existingUser.username;
            if (email == null) email = existingUser.email;
            if (password == null) password = existingUser.password;

            userStore.put(id, new User(id, username, email, password));
            sendResponse(exchange, 200, "{\"message\":\"User updated.\"}");
        }

        private void deleteUser(HttpExchange exchange, String requestBody, int id) throws IOException {
            if (!userStore.containsKey(id)) {
                sendResponse(exchange, 404, "{\"error\":\"User not found.\"}");
                return;
            }

            User existingUser = userStore.get(id);
            String username = extractJsonValue(requestBody, "username");
            String email = extractJsonValue(requestBody, "email");
            String password = extractJsonValue(requestBody, "password");

            if (existingUser.username.equals(username) &&
                    existingUser.email.equals(email) &&
                    existingUser.password.equals(password)) {

                userStore.remove(id);
                sendResponse(exchange, 200, "{\"message\":\"User deleted.\"}");
            } else {
                sendResponse(exchange, 401, "{\"error\":\"User credentials do not match.\"}");
            }
        }

        private void handleGetRequest(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            int userId = Integer.parseInt(path.replace("/user/", ""));

            if (!userStore.containsKey(userId)) {
                sendResponse(exchange, 404, "{\"error\":\"User not found.\"}");
                return;
            }

            User user = userStore.get(userId);
            String responseJson = String.format(
                    "{\"id\":%d,\"username\":\"%s\",\"email\":\"%s\"}",
                    user.id, user.username, user.email
            );

            sendResponse(exchange, 200, responseJson);
        }

        private String getRequestBody(HttpExchange exchange) throws IOException {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                StringBuilder requestBody = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    requestBody.append(line);
                }
                return requestBody.toString();
            }
        }

        private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
            exchange.sendResponseHeaders(statusCode, response.length());
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes(StandardCharsets.UTF_8));
            os.close();
        }

        private String extractJsonValue(String json, String key) {
            String pattern = "\"" + key + "\":\\s*\"([^\"]*)\"|\"" + key + "\":\\s*(\\d+)";
            java.util.regex.Pattern regex = java.util.regex.Pattern.compile(pattern);
            java.util.regex.Matcher matcher = regex.matcher(json);

            if (matcher.find()) {
                return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            }
            return null;
        }
    }

    static class User {
        int id;
        String username;
        String email;
        String password;

        public User(int id, String username, String email, String password) {
            this.id = id;
            this.username = username;
            this.email = email;
            this.password = password;
        }
    }
}
