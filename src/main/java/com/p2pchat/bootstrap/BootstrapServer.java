package com.p2pchat.bootstrap;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BootstrapServer {
    private static Map<String, String> onlinePeers = new ConcurrentHashMap<>();
    private static Map<String, long[]> peerTimestamps = new ConcurrentHashMap<>();
    // Store-and-forward: lưu tin nhắn chờ cho peer offline
    // key = tên người nhận, value = danh sách tin chờ
    private static Map<String, List<String>> pendingMessages = new ConcurrentHashMap<>();
    private static final long TIMEOUT_MS = 10_000;

    public static void main(String[] args) {
        // Luồng dọn dẹp peer timeout
        new Thread(() -> {
            while (true) {
                long now = System.currentTimeMillis();
                peerTimestamps.forEach((name, ts) -> {
                    if (now - ts[0] > TIMEOUT_MS) {
                        onlinePeers.remove(name);
                        peerTimestamps.remove(name);
                        System.out.println(">>> Peer timeout, đã xóa: " + name);
                    }
                });
                try { Thread.sleep(3000); } catch (Exception e) {}
            }
        }).start();

        try (ServerSocket server = new ServerSocket(8888)) {
            System.out.println(">>> Bootstrap Server started on port 8888...");
            while (true) {
                Socket s = server.accept();
                new Thread(() -> handlePeer(s)).start();
            }
        } catch (IOException e) { e.printStackTrace(); }
    }

    private static void handlePeer(Socket s) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
             PrintWriter out = new PrintWriter(s.getOutputStream(), true)) {

            String line = in.readLine();
            if (line == null) return;

            if (line.startsWith("REGISTER")) {
                // REGISTER|Alice-127.0.0.1:52341
                String data = line.split("\\|")[1];
                String name = data.split("-")[0];
                String addr = data.split("-")[1];
                onlinePeers.put(name, addr);
                peerTimestamps.put(name, new long[]{ System.currentTimeMillis() });

                // Trả về danh sách peer + tin nhắn đang chờ
                StringBuilder list = new StringBuilder("LIST|");
                onlinePeers.forEach((n, a) -> list.append(n).append("-").append(a).append(","));

                // Lấy tin nhắn chờ cho peer này
                List<String> waiting = pendingMessages.getOrDefault(name, new ArrayList<>());
                String pendingStr = String.join("~~", waiting);
                pendingMessages.remove(name);

                out.println(list.toString() + "|PENDING|" + pendingStr);
                System.out.println("Peer online: " + name + " tại " + addr +
                    (waiting.isEmpty() ? "" : " | Giao " + waiting.size() + " tin chờ"));

            } else if (line.startsWith("STORE")) {
                // STORE|TênNgườiNhận|nội dung tin
                String[] parts = line.split("\\|", 3);
                if (parts.length == 3) {
                    String recipient = parts[1];
                    String msg = parts[2];
                    pendingMessages.computeIfAbsent(recipient, k -> new ArrayList<>()).add(msg);
                    System.out.println(">>> Lưu tin chờ cho: " + recipient);
                    out.println("STORED");
                }
            } else if (line.startsWith("LEAVE")) {
                String name = line.split("\\|")[1];
                onlinePeers.remove(name);
                peerTimestamps.remove(name);
                System.out.println("Peer rời mạng: " + name);
                out.println("OK");
            }

        } catch (IOException e) { /* bình thường */ }
    }
}
