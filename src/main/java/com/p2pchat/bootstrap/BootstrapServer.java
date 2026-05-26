package com.p2pchat.bootstrap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.p2pchat.auth.UserStore;

public class BootstrapServer {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String PENDING_FILE = "pending_messages.json";

    private static final Map<String, String> onlinePeers = new ConcurrentHashMap<>();
    private static final Map<String, long[]> peerTimestamps = new ConcurrentHashMap<>();
    private static final Map<String, List<String>> pendingMessages = new ConcurrentHashMap<>();

    private static final long TIMEOUT_MS = 4_000;

    static {
        pendingMessages.putAll(loadPendingMessages());
    }

    private static Map<String, List<String>> loadPendingMessages() {
        Path path = Paths.get(PENDING_FILE);
        if (!Files.exists(path)) {
            return new ConcurrentHashMap<>();
        }

        try {
            ObjectNode root = (ObjectNode) mapper.readTree(path.toFile());
            Map<String, List<String>> map = new ConcurrentHashMap<>();

            root.fieldNames().forEachRemaining(name -> {
                List<String> list = new CopyOnWriteArrayList<>();
                root.get(name).forEach(node -> list.add(node.asText()));
                map.put(name, list);
            });

            return map;
        } catch (Exception e) {
            System.err.println("Không đọc được pending messages: " + e.getMessage());
            return new ConcurrentHashMap<>();
        }
    }

    private static void persistPendingMessages() {
        synchronized (PENDING_FILE.intern()) {
            try {
                mapper.writerWithDefaultPrettyPrinter()
                        .writeValue(Paths.get(PENDING_FILE).toFile(), pendingMessages);
            } catch (IOException e) {
                System.err.println("Không ghi được pending messages: " + e.getMessage());
            }
        }
    }

    public static void main(String[] args) {
        startUdpDiscovery();
        startTimeoutCleaner();

        try (ServerSocket server = new ServerSocket(8888)) {
            System.out.println(">>> Bootstrap Server started on port 8888...");

            while (true) {
                Socket s = server.accept();
                new Thread(() -> handlePeer(s)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

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

                        udp.send(new DatagramPacket(
                                reply,
                                reply.length,
                                req.getAddress(),
                                req.getPort()));

                        System.out.println(">>> Trả lời discover từ: "
                                + req.getAddress().getHostAddress());
                    }
                }
            } catch (Exception e) {
                System.err.println("UDP discovery lỗi: " + e.getMessage());
            }
        }, "udp-discovery").start();
    }

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

                try {
                    Thread.sleep(1000);
                } catch (Exception ignored) {
                }
            }
        }, "timeout-cleaner").start();
    }

    private static void handlePeer(Socket s) {
        try (
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
                PrintWriter out = new PrintWriter(s.getOutputStream(), true)) {
            String line = in.readLine();

            if (line == null) {
                return;
            }

            if (line.startsWith("REGISTER|")) {
                handleRegister(line, out);
            } else if (line.startsWith("STORE|")) {
                handleStore(line, out);
            } else if (line.startsWith("LEAVE|")) {
                handleLeave(line, out);
            }

        } catch (IOException ignored) {
        }
    }

    private static void handleRegister(String line, PrintWriter out) {
        String data = line.substring(9);
        int dash = data.lastIndexOf('-');

        if (dash < 0) {
            out.println("ERROR|Định dạng sai");
            return;
        }

        String name = data.substring(0, dash);
        String addr = data.substring(dash + 1);

        if (!"EXISTS".equals(UserStore.exists(name))) {
            out.println("ERROR|Tài khoản không hợp lệ");
            return;
        }

        /*
         * Chống lỗi nhiều username bị dính cùng một địa chỉ.
         * Nếu cùng addr đã từng gắn với user khác thì xóa user cũ.
         */
        onlinePeers.entrySet().removeIf(e -> !e.getKey().equals(name) && e.getValue().equals(addr));

        peerTimestamps.entrySet().removeIf(e -> !e.getKey().equals(name) && !onlinePeers.containsKey(e.getKey()));

        onlinePeers.put(name, addr);
        peerTimestamps.put(name, new long[] { System.currentTimeMillis() });

        StringBuilder list = new StringBuilder("LIST|");

        for (String user : UserStore.getAllUsernames()) {
            if (user.equals(name)) {
                continue;
            }

            String peerAddr = onlinePeers.get(user);

            if (peerAddr != null && peerTimestamps.containsKey(user)) {
                list.append(user).append("-").append(peerAddr).append(",");
            } else {
                list.append(user).append("-").append("OFFLINE").append(",");
            }
        }

        List<String> waiting = pendingMessages.getOrDefault(name, new ArrayList<>());
        String pendingStr = String.join("~~", waiting);

        if (!waiting.isEmpty()) {
            pendingMessages.remove(name);
            persistPendingMessages();
        }

        out.println(list + "|PENDING|" + pendingStr);

        System.out.println("Peer online: " + name + " @ " + addr
                + (waiting.isEmpty() ? "" : " | giao " + waiting.size() + " tin chờ"));
    }

    private static void handleStore(String line, PrintWriter out) {
        String[] parts = line.split("\\|", 3);

        if (parts.length == 3) {
            String targetName = parts[1];
            String message = parts[2];

            pendingMessages
                    .computeIfAbsent(targetName, k -> new CopyOnWriteArrayList<>())
                    .add(message);

            persistPendingMessages();

            System.out.println(">>> Lưu tin chờ cho: " + targetName);
            out.println("STORED");
        } else {
            out.println("ERROR");
        }
    }

    private static void handleLeave(String line, PrintWriter out) {
        String name = line.substring(6);

        onlinePeers.remove(name);
        peerTimestamps.remove(name);

        System.out.println("Peer rời mạng: " + name);
        out.println("OK");
    }
}