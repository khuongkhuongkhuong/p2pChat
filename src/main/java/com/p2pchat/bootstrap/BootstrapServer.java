package com.p2pchat.bootstrap;

import com.p2pchat.auth.UserStore;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BootstrapServer {

    private static final Map<String, String>       onlinePeers      = new ConcurrentHashMap<>();
    private static final Map<String, long[]>       peerTimestamps   = new ConcurrentHashMap<>();
    private static final Map<String, List<String>> pendingMessages  = new ConcurrentHashMap<>();
    private static final long TIMEOUT_MS = 10_000;

    public static void main(String[] args) {
        startUdpDiscovery();
        startTimeoutCleaner();

        try (ServerSocket server = new ServerSocket(8888)) {
            System.out.println(">>> Bootstrap Server started on port 8888...");
            while (true) {
                Socket s = server.accept();
                new Thread(() -> handlePeer(s)).start();
            }
        } catch (IOException e) { e.printStackTrace(); }
    }

    // ── UDP Discovery: trả lời DISCOVER_BOOTSTRAP bằng IP thật ──────────────
    private static void startUdpDiscovery() {
        new Thread(() -> {
            try (DatagramSocket udp = new DatagramSocket(8889)) {
                byte[] buf = new byte[256];
                System.out.println(">>> UDP Discovery listening on port 8889...");
                while (true) {
                    DatagramPacket req = new DatagramPacket(buf, buf.length);
                    udp.receive(req);
                    String msg = new String(req.getData(), 0, req.getLength()).trim();
                    if ("DISCOVER_BOOTSTRAP".equals(msg)) {
                        String myIp;
                        try (Socket s = new Socket()) {
                            s.connect(new InetSocketAddress("8.8.8.8", 80), 1000);
                            myIp = s.getLocalAddress().getHostAddress();
                        } catch (Exception e) {
                            myIp = InetAddress.getLocalHost().getHostAddress();
                        }
                        byte[] reply = ("BOOTSTRAP_HERE|" + myIp).getBytes();
                        udp.send(new DatagramPacket(reply, reply.length,
                                req.getAddress(), req.getPort()));
                        System.out.println(">>> Trả lời discover từ: "
                                + req.getAddress().getHostAddress());
                    }
                }
            } catch (Exception e) {
                System.err.println("UDP discovery lỗi: " + e.getMessage());
            }
        }, "udp-discovery").start();
    }

    // ── Dọn peer timeout ────────────────────────────────────────────────────
    private static void startTimeoutCleaner() {
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
                try { Thread.sleep(3000); } catch (Exception ignored) {}
            }
        }, "timeout-cleaner").start();
    }

    // ── Xử lý từng kết nối TCP ─────────────────────────────────────────────
    private static void handlePeer(Socket s) {
        try (BufferedReader in  = new BufferedReader(new InputStreamReader(s.getInputStream()));
             PrintWriter    out = new PrintWriter(s.getOutputStream(), true)) {

            String line = in.readLine();
            if (line == null) return;

            // REGISTER|Alice-192.168.1.5:52341
            if (line.startsWith("REGISTER|")) {
                String data = line.substring(9); // bỏ "REGISTER|"
                int dash    = data.lastIndexOf('-');
                if (dash < 0) { out.println("ERROR|Định dạng sai"); return; }
                String name = data.substring(0, dash);
                String addr = data.substring(dash + 1);

                // Chỉ cho phép register nếu tài khoản đã tồn tại (đã đăng ký qua App)
                if (!"EXISTS".equals(UserStore.exists(name))) {
                    out.println("ERROR|Tài khoản không hợp lệ");
                    return;
                }

                onlinePeers.put(name, addr);
                peerTimestamps.put(name, new long[]{ System.currentTimeMillis() });

                StringBuilder list = new StringBuilder("LIST|");
                onlinePeers.forEach((n, a) -> list.append(n).append("-").append(a).append(","));

                List<String> waiting    = pendingMessages.getOrDefault(name, new ArrayList<>());
                String       pendingStr = String.join("~~", waiting);
                pendingMessages.remove(name);

                out.println(list + "|PENDING|" + pendingStr);
                System.out.println("Peer online: " + name + " @ " + addr
                        + (waiting.isEmpty() ? "" : " | giao " + waiting.size() + " tin chờ"));

            // STORE|recipient|message
            } else if (line.startsWith("STORE|")) {
                String[] parts = line.split("\\|", 3);
                if (parts.length == 3) {
                    pendingMessages.computeIfAbsent(parts[1], k -> new ArrayList<>()).add(parts[2]);
                    System.out.println(">>> Lưu tin chờ cho: " + parts[1]);
                    out.println("STORED");
                }

            // LEAVE|username
            } else if (line.startsWith("LEAVE|")) {
                String name = line.substring(6);
                onlinePeers.remove(name);
                peerTimestamps.remove(name);
                System.out.println("Peer rời mạng: " + name);
                out.println("OK");
            }

        } catch (IOException ignored) {}
    }
}
