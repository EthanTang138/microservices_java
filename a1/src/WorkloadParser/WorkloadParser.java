import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class WorkloadParser {
    private static String orderHost = "127.0.0.1";
    private static int    orderPort = 14000;

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: java WorkloadParser config.json workloadfile");
            return;
        }
        Map<String, String> cfg = loadServiceConfig(args[0], "OrderService");
        orderHost = cfg.get("ip");
        orderPort = Integer.parseInt(cfg.get("port"));

        try (BufferedReader br = new BufferedReader(new FileReader(args[1]))) {
            String line;
            while ((line = br.readLine()) != null) {
                // Strip inline comments
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
                    default: System.out.println("Unknown token: " + tokens[0]);
                }
            }
        } catch (IOException e) { e.printStackTrace(); }
    }

    private static void handleUser(String[] tokens) {
        String action = tokens[1].toLowerCase();
        try {
            switch (action) {
                case "create":
                    // USER create <id> <username> <email> <password>
                    if (tokens.length < 6) return;
                    doPost("/user", String.format(
                        "{\"command\":\"create\",\"id\":%s,\"username\":\"%s\",\"email\":\"%s\",\"password\":\"%s\"}",
                        tokens[2], tokens[3], tokens[4], tokens[5]));
                    break;
                case "get":
                    if (tokens.length < 3) return;
                    doGet("/user/" + tokens[2]);
                    break;
                case "update":
                    // USER update <id> key:val key:val ...
                    if (tokens.length < 3) return;
                    StringBuilder upJson = new StringBuilder(
                        "{\"command\":\"update\",\"id\":").append(tokens[2]);
                    for (int i = 3; i < tokens.length; i++) {
                        if (tokens[i].startsWith("username:"))
                            upJson.append(",\"username\":\"").append(tokens[i].substring(9)).append("\"");
                        else if (tokens[i].startsWith("email:"))
                            upJson.append(",\"email\":\"").append(tokens[i].substring(6)).append("\"");
                        else if (tokens[i].startsWith("password:"))
                            upJson.append(",\"password\":\"").append(tokens[i].substring(9)).append("\"");
                    }
                    upJson.append("}");
                    doPost("/user", upJson.toString());
                    break;
                case "delete":
                    // USER delete <id> <username> <email> <password>
                    if (tokens.length < 6) return;
                    doPost("/user", String.format(
                        "{\"command\":\"delete\",\"id\":%s,\"username\":\"%s\",\"email\":\"%s\",\"password\":\"%s\"}",
                        tokens[2], tokens[3], tokens[4], tokens[5]));
                    break;
            }
        } catch (Exception e) { System.out.println("USER error: " + e.getMessage()); }
    }

    private static void handleProduct(String[] tokens) {
        String action = tokens[1].toLowerCase();
        try {
            switch (action) {
                case "create":
                    // PRODUCT create <id> <name> <description> <price> <quantity>
                    if (tokens.length < 7) return;
                    doPost("/product", String.format(
                        "{\"command\":\"create\",\"id\":%s,\"productname\":\"%s\",\"description\":\"%s\",\"price\":%s,\"quantity\":%s}",
                        tokens[2], tokens[3], tokens[4], tokens[5], tokens[6]));
                    break;
                case "info":
                    if (tokens.length < 3) return;
                    doGet("/product/" + tokens[2]);
                    break;
                case "update":
                    // PRODUCT update <id> name:<val> price:<val> quantity:<val>
                    if (tokens.length < 3) return;
                    StringBuilder upJson = new StringBuilder(
                        "{\"command\":\"update\",\"id\":").append(tokens[2]);
                    for (int i = 3; i < tokens.length; i++) {
                        if (tokens[i].startsWith("name:"))
                            upJson.append(",\"productname\":\"").append(tokens[i].substring(5)).append("\"");
                        else if (tokens[i].startsWith("description:"))
                            upJson.append(",\"description\":\"").append(tokens[i].substring(12)).append("\"");
                        else if (tokens[i].startsWith("price:"))
                            upJson.append(",\"price\":").append(tokens[i].substring(6));
                        else if (tokens[i].startsWith("quantity:"))
                            upJson.append(",\"quantity\":").append(tokens[i].substring(9));
                    }
                    upJson.append("}");
                    doPost("/product", upJson.toString());
                    break;
                case "delete":
                    // PRODUCT delete <id> <name> <price> <quantity>
                    if (tokens.length < 6) return;
                    doPost("/product", String.format(
                        "{\"command\":\"delete\",\"id\":%s,\"productname\":\"%s\",\"price\":%s,\"quantity\":%s}",
                        tokens[2], tokens[3], tokens[4], tokens[5]));
                    break;
            }
        } catch (Exception e) { System.out.println("PRODUCT error: " + e.getMessage()); }
    }

    private static void handleOrder(String[] tokens) {
        if (tokens.length < 4 || !tokens[1].equalsIgnoreCase("place")) return;
        try {
            // ORDER place <product_id> <user_id> [<quantity>]
            int productId = Integer.parseInt(tokens[2]);
            int userId    = Integer.parseInt(tokens[3]);
            int quantity  = (tokens.length >= 5) ? Integer.parseInt(tokens[4]) : 1;
            doPost("/order", String.format(
                "{\"command\":\"place order\",\"user_id\":%d,\"product_id\":%d,\"quantity\":%d}",
                userId, productId, quantity));
        } catch (Exception e) { System.out.println("ORDER error: " + e.getMessage()); }
    }

    private static void doPost(String endpoint, String body) {
        try {
            URL url = new URL("http://" + orderHost + ":" + orderPort + endpoint);
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
            URL url = new URL("http://" + orderHost + ":" + orderPort + endpoint);
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
