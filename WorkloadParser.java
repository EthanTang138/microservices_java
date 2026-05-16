import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * WorkloadParser: Reads a workload file and executes HTTP requests to the OrderService.
 * Supports USER, PRODUCT, and ORDER commands.
 */
public class WorkloadParser {

    private static final String ORDER_SERVICE_HOST = "http://localhost";
    private static final int ORDER_SERVICE_PORT = 14000;

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java WorkloadParser <workload_file>");
            return;
        }

        String workloadFile = args[0];

        try (BufferedReader br = new BufferedReader(new FileReader(workloadFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();

                // Skip empty lines or comments
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                // Split by whitespace
                String[] tokens = line.split("\\s+");
                if (tokens.length < 2) {
                    System.out.println("Skipping malformed line: " + line);
                    continue;
                }

                String entityType = tokens[0]; // USER, PRODUCT, ORDER
                String action = tokens[1]; // create, update, delete, get, place

                switch (entityType.toUpperCase()) {
                    case "USER":
                        handleUserCommand(action, tokens);
                        break;
                    case "PRODUCT":
                        handleProductCommand(action, tokens);
                        break;
                    case "ORDER":
                        handleOrderCommand(action, tokens);
                        break;
                    default:
                        System.out.println("Skipping unrecognized entity: " + entityType);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void handleUserCommand(String action, String[] tokens) {
        try {
            switch (action.toLowerCase()) {
                case "create":
                    if (tokens.length < 6) return;
                    int createId = Integer.parseInt(tokens[2]);
                    doPost("/user",
                            String.format("{\"command\":\"create\",\"id\":%d,\"username\":\"%s\",\"email\":\"%s\",\"password\":\"%s\"}",
                                    createId, tokens[3], tokens[4], tokens[5])
                    );
                    break;

                case "update":
                    if (tokens.length < 3) return;
                    int updateId = Integer.parseInt(tokens[2]);
                    String username = extractField(tokens, "username:");
                    String email = extractField(tokens, "email:");
                    String password = extractField(tokens, "password:");
                    doPost("/user",
                            String.format("{\"command\":\"update\",\"id\":%d,\"username\":\"%s\",\"email\":\"%s\",\"password\":\"%s\"}",
                                    updateId, username, email, password)
                    );
                    break;

                case "delete":
                    if (tokens.length < 6) return;
                    int deleteId = Integer.parseInt(tokens[2]);
                    doPost("/user",
                            String.format("{\"command\":\"delete\",\"id\":%d,\"username\":\"%s\",\"email\":\"%s\",\"password\":\"%s\"}",
                                    deleteId, tokens[3], tokens[4], tokens[5])
                    );
                    break;

                case "get":
                    if (tokens.length < 3) return;
                    doGet("/user/" + tokens[2]);
                    break;
            }
        } catch (Exception e) {
            System.out.println("Error processing USER command: " + e.getMessage());
        }
    }

    private static void handleProductCommand(String action, String[] tokens) {
        try {
            switch (action.toLowerCase()) {
                case "create":
                    if (tokens.length < 5) return;
                    int productId = Integer.parseInt(tokens[2]);
                    doPost("/product",
                            String.format("{\"command\":\"create\",\"id\":%d,\"productname\":\"%s\",\"price\":%s,\"quantity\":%s}",
                                    productId, tokens[3], tokens[4], tokens[5])
                    );
                    break;

                case "update":
                    if (tokens.length < 3) return;
                    int updateId = Integer.parseInt(tokens[2]);
                    String name = extractField(tokens, "name:");
                    String price = extractField(tokens, "price:");
                    String quantity = extractField(tokens, "quantity:");
                    doPost("/product",
                            String.format("{\"command\":\"update\",\"id\":%d,\"productname\":\"%s\",\"price\":%s,\"quantity\":%s}",
                                    updateId, name, price, quantity)
                    );
                    break;

                case "delete":
                    if (tokens.length < 6) return;
                    int deleteId = Integer.parseInt(tokens[2]);
                    doPost("/product",
                            String.format("{\"command\":\"delete\",\"id\":%d,\"productname\":\"%s\",\"price\":%s,\"quantity\":%s}",
                                    deleteId, tokens[3], tokens[4], tokens[5])
                    );
                    break;

                case "info":
                    if (tokens.length < 3) return;
                    doGet("/product/" + tokens[2]);
                    break;
            }
        } catch (Exception e) {
            System.out.println("Error processing PRODUCT command: " + e.getMessage());
        }
    }

    private static void handleOrderCommand(String action, String[] tokens) {
        try {
            switch (action.toLowerCase()) {
                case "place":
                    if (tokens.length < 4) return;
                    int userId = Integer.parseInt(tokens[2]);
                    int productId = Integer.parseInt(tokens[3]);
                    doPost("/order",
                            String.format("{\"command\":\"place order\",\"user_id\":%d,\"product_id\":%d,\"quantity\":1}",
                                    userId, productId)
                    );
                    break;
            }
        } catch (Exception e) {
            System.out.println("Error processing ORDER command: " + e.getMessage());
        }
    }

    private static String extractField(String[] tokens, String prefix) {
        for (String token : tokens) {
            if (token.startsWith(prefix)) {
                return token.substring(prefix.length());
            }
        }
        return "";
    }

    private static void doPost(String endpoint, String jsonBody) {
        try {
            URL url = new URL(ORDER_SERVICE_HOST + ":" + ORDER_SERVICE_PORT + endpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            String response = readResponse(conn);
            System.out.println(endpoint + " -> HTTP " + status + " : " + response);

            conn.disconnect();
        } catch (IOException e) {
            System.out.println("HTTP POST failed: " + e.getMessage());
        }
    }

    private static void doGet(String endpoint) {
        try {
            URL url = new URL(ORDER_SERVICE_HOST + ":" + ORDER_SERVICE_PORT + endpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");

            int status = conn.getResponseCode();
            String response = readResponse(conn);
            System.out.println(endpoint + " -> HTTP " + status + " : " + response);

            conn.disconnect();
        } catch (IOException e) {
            System.out.println("HTTP GET failed: " + e.getMessage());
        }
    }

    private static String readResponse(HttpURLConnection conn) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                (conn.getResponseCode() < 400) ? conn.getInputStream() : conn.getErrorStream(),
                StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }
}
