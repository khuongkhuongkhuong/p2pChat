package com.p2pchat.web;

import com.p2pchat.auth.GroupStore;
import com.p2pchat.auth.UserStore;
import com.p2pchat.node.P2PNode;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class App {

    private static P2PNode node;
    private static String bootstrapHost;
    private static ScheduledExecutorService heartbeat;

    public static void main(String[] args) throws Exception {
        int webPort = findFreePort(7000);

        if (args.length > 0 && !args[0].isBlank()) {
            bootstrapHost = args[0];
        } else {
            String env = System.getenv("BOOTSTRAP_HOST");
            if (env != null && !env.isBlank()) {
                bootstrapHost = env;
            } else {
                bootstrapHost = discoverBootstrap();
                if (bootstrapHost == null)
                    bootstrapHost = "localhost";
            }
        }

        Javalin app = Javalin.create(config -> {
            config.staticFiles.add("/public", Location.CLASSPATH);
            config.bundledPlugins.enableCors(cors -> cors.addRule(r -> r.anyHost()));
        }).start(webPort);

        app.post("/api/register", ctx -> {
            String username = ctx.queryParam("username");
            String password = ctx.queryParam("password");

            switch (UserStore.register(username, password)) {
                case "OK" -> ctx.status(200).result("OK");
                case "USERNAME_TAKEN" -> ctx.status(409).result("Tên đăng nhập đã tồn tại");
                case "INVALID" -> ctx.status(400).result("Tên chỉ dùng chữ/số/gạch dưới, mật khẩu tối thiểu 6 ký tự");
                default -> ctx.status(500).result("Lỗi server");
            }
        });

        app.post("/api/auth", ctx -> {
            String username = ctx.queryParam("username");
            String password = ctx.queryParam("password");

            switch (UserStore.login(username, password)) {
                case "OK" -> ctx.status(200).result("OK");
                case "WRONG_PASSWORD" -> ctx.status(401).result("Sai mật khẩu");
                case "NOT_FOUND" -> ctx.status(404).result("Tài khoản không tồn tại");
                default -> ctx.status(500).result("Lỗi server");
            }
        });

        app.get("/api/bootstrap-host", ctx -> ctx.result(bootstrapHost));

        app.get("/api/discover", ctx -> {
            String found = discoverBootstrap();
            if (found != null)
                ctx.result(found);
            else
                ctx.status(404).result("NOT_FOUND");
        });

        app.post("/api/login", ctx -> {
            String name = ctx.queryParam("name");
            String password = ctx.queryParam("password");

            if (name == null || name.isBlank() || password == null || password.isBlank()) {
                ctx.status(400).result("Thiếu tên đăng nhập hoặc mật khẩu");
                return;
            }

            String username = name.trim();

            switch (UserStore.login(username, password)) {
                case "OK" -> {
                    stopHeartbeat();

                    if (node != null) {
                        try {
                            sendToBootstrap("LEAVE|" + node.getUsername());
                            node.stop();
                        } catch (Exception ignored) {
                        }
                        node = null;
                    }

                    node = new P2PNode(username, bootstrapHost);
                    node.startListening();

                    for (int i = 0; i < 40 && node.getMyPort() == 0; i++) {
                        Thread.sleep(50);
                    }

                    startHeartbeat();
                    ctx.status(200).result("OK");
                }

                case "WRONG_PASSWORD" -> ctx.status(401).result("Sai mật khẩu");
                case "NOT_FOUND" -> ctx.status(404).result("Tài khoản không tồn tại");
                default -> ctx.status(500).result("Lỗi server");
            }
        });

        app.post("/api/logout", ctx -> {
            if (node != null) {
                try {
                    sendToBootstrap("LEAVE|" + node.getUsername());
                } catch (Exception ignored) {
                }

                stopHeartbeat();
                node.stop();
                node = null;
            }

            ctx.status(200).result("OK");
        });

        app.get("/api/info", ctx -> {
            if (node == null) {
                ctx.status(401);
                return;
            }

            ctx.json(Map.of(
                    "name", node.getUsername(),
                    "port", node.getMyPort(),
                    "ip", node.getMyIp(),
                    "addr", node.getMyAddr()));
        });

        app.get("/api/peers", ctx -> {
            ctx.json(node != null ? node.getOnlinePeers() : List.of());
        });

        app.get("/api/messages", ctx -> {
            if (node == null) {
                ctx.json(List.of());
                return;
            }

            List<String> msgs = new ArrayList<>(node.getMessages());
            node.getMessages().clear();
            ctx.json(msgs);
        });

        app.get("/api/conversations", ctx -> {
            if (node == null) {
                ctx.json(List.of());
                return;
            }

            ctx.json(com.p2pchat.auth.ChatHistoryStore.getConversationKeys(node.getUsername()));
        });

        app.get("/api/history", ctx -> {
            if (node == null) {
                ctx.json(List.of());
                return;
            }

            String key = ctx.queryParam("key");
            ctx.json(key != null ? node.getHistory(key) : List.of());
        });

        app.post("/api/send", ctx -> {
            if (node == null) {
                ctx.status(401);
                return;
            }

            String target = ctx.queryParam("target");
            String targetName = ctx.queryParam("targetName");
            String msg = ctx.queryParam("msg");

            if (target == null || msg == null) {
                ctx.status(400);
                return;
            }

            if ("broadcast".equals(target)) {
                node.sendBroadcast(msg);
                ctx.result("OK");
                return;
            }

            if ("OFFLINE".equalsIgnoreCase(target)) {
                boolean stored = node.storeForward(targetName, msg);
                ctx.status(503).result(stored ? "STORED" : "FAILED");
                return;
            }

            boolean ok = node.sendDirect(target, targetName, msg);

            if (ok) {
                ctx.result("OK");
            } else {
                boolean stored = node.storeForward(targetName, msg);
                ctx.status(503).result(stored ? "STORED" : "FAILED");
            }
        });

        app.post("/api/sendgroup", ctx -> {
            if (node == null) {
                ctx.status(401).result("Chưa đăng nhập");
                return;
            }

            String groupId = ctx.queryParam("groupId");
            String msg = ctx.queryParam("msg");

            if (groupId == null || groupId.isBlank() || msg == null || msg.isBlank()) {
                ctx.status(400).result("Thiếu groupId hoặc nội dung");
                return;
            }

            List<String> members = GroupStore.getMembers(groupId);

            if (members == null || members.isEmpty()) {
                ctx.status(404).result("Nhóm không tồn tại");
                return;
            }

            if (!members.contains(node.getUsername())) {
                ctx.status(403).result("Bạn không thuộc nhóm này");
                return;
            }

            node.sendGroupSelected(groupId, members, msg);

            ctx.status(200).result("OK");
        });

        app.post("/api/creategroup", ctx -> {
            if (node == null) {
                ctx.status(401);
                return;
            }

            String groupId = ctx.queryParam("groupId");
            String groupName = ctx.queryParam("groupName");
            String membersStr = ctx.queryParam("members");

            if (groupId == null || groupName == null || membersStr == null || membersStr.isBlank()) {
                ctx.status(400);
                return;
            }

            List<String> members = Arrays.asList(membersStr.split(","));

            GroupStore.createGroup(
                    groupId,
                    groupName,
                    node.getUsername(),
                    members);

            List<String> memberAddrs = resolveUsernamesToOnlineAddrs(
                    members,
                    node.getUsername(),
                    node.getOnlinePeers());

            node.sendGroupInvite(groupId, groupName, members, memberAddrs);

            ctx.result("OK");
        });

        app.post("/api/deletegroup", ctx -> {
            if (node == null) {
                ctx.status(401).result("Chưa đăng nhập");
                return;
            }

            String groupId = ctx.queryParam("groupId");

            if (groupId == null || groupId.isBlank()) {
                ctx.status(400).result("Thiếu groupId");
                return;
            }

            List<String> members = GroupStore.getMembers(groupId);

            if (members == null || members.isEmpty()) {
                ctx.status(404).result("Nhóm không tồn tại");
                return;
            }

            String owner = GroupStore.getOwner(groupId);
            String groupName = GroupStore.getGroupName(groupId);

            if (!node.getUsername().equals(owner)) {
                ctx.status(403).result("Chỉ chủ nhóm mới được xóa nhóm");
                return;
            }

            node.sendGroupDelete(groupId, groupName, members);

            GroupStore.deleteGroup(groupId, node.getUsername());

            ctx.status(200).result("OK");
        });

        app.get("/api/groups", ctx -> {
            if (node == null) {
                ctx.json(List.of());
                return;
            }

            ctx.json(GroupStore.getGroupsOfUser(node.getUsername()));
        });

        app.get("/api/group-members", ctx -> {
            if (node == null) {
                ctx.json(List.of());
                return;
            }

            String groupId = ctx.queryParam("groupId");

            if (groupId == null) {
                ctx.json(List.of());
                return;
            }

            ctx.json(GroupStore.getMembers(groupId));
        });

        app.get("/api/download", ctx -> {
            if (node == null) {
                ctx.status(401);
                return;
            }

            String fileId = ctx.queryParam("fileId");

            if (fileId == null) {
                ctx.status(400);
                return;
            }

            byte[] data = node.getReceivedFile(fileId);
            String name = node.getReceivedFileName(fileId);

            if (data == null) {
                ctx.status(404).result("File không tồn tại");
                return;
            }

            ctx.header("Content-Disposition", "attachment; filename=\"" + name + "\"");
            ctx.header("Content-Type", "application/octet-stream");
            ctx.result(new ByteArrayInputStream(data));
        });

        app.post("/api/sendfile", ctx -> {
            if (node == null) {
                ctx.status(401);
                return;
            }

            String target = ctx.queryParam("target");

            if (target == null || target.isBlank()) {
                ctx.status(400).result("Thiếu target");
                return;
            }

            var uploaded = ctx.uploadedFile("file");

            if (uploaded == null) {
                ctx.status(400).result("Không có file");
                return;
            }

            try (InputStream is = uploaded.content()) {
                byte[] data = is.readAllBytes();
                ctx.result(node.sendFile(target, uploaded.filename(), data) ? "OK" : "FAILED");
            }
        });

        System.out.println("\n===========================================");
        System.out.println(">>> Giao diện chat: http://localhost:" + webPort);
        System.out.println("===========================================\n");
    }

    private static List<String> resolveUsernamesToOnlineAddrs(
            List<String> usernames,
            String currentUsername,
            List<String> onlinePeers) {
        List<String> addrs = new ArrayList<>();

        for (String peer : onlinePeers) {
            if (!peer.contains("-"))
                continue;

            int dash = peer.lastIndexOf("-");
            String name = peer.substring(0, dash);
            String addr = peer.substring(dash + 1);

            if (name.equals(currentUsername))
                continue;
            if ("OFFLINE".equalsIgnoreCase(addr))
                continue;

            if (usernames.contains(name)) {
                addrs.add(addr);
            }
        }

        return addrs;
    }

    private static void startHeartbeat() {
        heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat");
            t.setDaemon(true);
            return t;
        });

        heartbeat.scheduleAtFixedRate(() -> {
            if (node == null)
                return;

            try {
                String resp = sendToBootstrap("REGISTER|" + node.getUsername() + "-" + node.getMyAddr());

                if (resp != null && resp.startsWith("LIST|")) {
                    parsePeerList(resp);
                }
            } catch (Exception ignored) {
            }
        }, 0, 3, TimeUnit.SECONDS);
    }

    private static void stopHeartbeat() {
        if (heartbeat != null && !heartbeat.isShutdown()) {
            heartbeat.shutdownNow();
        }
    }

    private static void parsePeerList(String resp) {
        if (node == null)
            return;

        String[] sections = resp.split("\\|PENDING\\|", 2);
        String listPart = sections[0].substring(5);

        List<String> peers = new ArrayList<>();

        for (String p : listPart.split(",")) {
            if (!p.isBlank()) {
                peers.add(p.trim());
            }
        }

        node.setOnlinePeers(peers);

        if (sections.length > 1 && !sections[1].isBlank()) {
            String[] pending = sections[1].split("~~");
            int count = 0;

            for (String pm : pending) {
                if (!pm.isBlank()) {
                    node.receivePendingMessage(pm);
                    count++;
                }
            }

            if (count > 0) {
                node.getMessages().add("[PENDING_NOTICE]|" + count);
            }
        }
    }

    private static String sendToBootstrap(String message) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(bootstrapHost, 8888), 3000);

            PrintWriter out = new PrintWriter(s.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));

            out.println(message);
            return in.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    private static String discoverBootstrap() {
        try (DatagramSocket udp = new DatagramSocket()) {
            udp.setBroadcast(true);
            udp.setSoTimeout(2000);

            byte[] msg = "DISCOVER_BOOTSTRAP".getBytes();

            for (String b : new String[] {
                    "255.255.255.255",
                    "192.168.1.255",
                    "192.168.0.255",
                    "10.0.0.255"
            }) {
                try {
                    udp.send(new DatagramPacket(
                            msg,
                            msg.length,
                            InetAddress.getByName(b),
                            8889));
                } catch (Exception ignored) {
                }
            }

            byte[] buf = new byte[256];
            DatagramPacket reply = new DatagramPacket(buf, buf.length);

            udp.receive(reply);

            String res = new String(reply.getData(), 0, reply.getLength());

            if (res.startsWith("BOOTSTRAP_HERE|")) {
                return res.split("\\|")[1];
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    private static int findFreePort(int startPort) {
        for (int port = startPort; port < startPort + 100; port++) {
            try (ServerSocket ignored = new ServerSocket(port)) {
                return port;
            } catch (IOException ignored) {
            }
        }

        return 0;
    }
}