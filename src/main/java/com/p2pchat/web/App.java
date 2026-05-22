package com.p2pchat.web;

import com.p2pchat.node.P2PNode;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import java.io.*;
import java.net.*;
import java.util.*;

public class App {
    private static P2PNode node;
    private static int webPort;

    public static void main(String[] args) {
        webPort = findFreePort(7000);

        var app = Javalin.create(config -> {
            config.staticFiles.add("/public", Location.CLASSPATH);
            config.bundledPlugins.enableCors(cors -> cors.addRule(it -> it.anyHost()));
        }).start(webPort);

        // ── Đăng nhập ──
        app.post("/api/login", ctx -> {
            String name = ctx.queryParam("name");
            if (name != null && !name.isEmpty()) {
                node = new P2PNode(name);
                node.startListening();
                while (node.getMyPort() == 0) { Thread.sleep(50); }
                startHeartbeat();
                ctx.status(200);
            } else ctx.status(400);
        });

        // ── Thông tin bản thân ──
        app.get("/api/info", ctx -> {
            if (node == null) ctx.status(401);
            else ctx.json(Map.of("name", node.getUsername(), "port", node.getMyPort()));
        });

        // ── Danh sách peer online ──
        app.get("/api/peers", ctx -> {
            if (node == null) ctx.json(new ArrayList<>());
            else ctx.json(node.getOnlinePeers());
        });

        // ── Nhận tin nhắn mới ──
        app.get("/api/messages", ctx -> {
            if (node == null) ctx.json(new ArrayList<>());
            else {
                List<String> msgs = new ArrayList<>(node.getMessages());
                node.getMessages().clear();
                ctx.json(msgs);
            }
        });

        // ── Lấy lịch sử chat ──
        app.get("/api/history", ctx -> {
            if (node == null) { ctx.json(new ArrayList<>()); return; }
            String key = ctx.queryParam("key");
            if (key == null) { ctx.json(new ArrayList<>()); return; }
            ctx.json(node.getHistory(key));
        });

        // ── Gửi tin nhắn (private / store-forward / broadcast) ──
        app.post("/api/send", ctx -> {
            if (node == null) { ctx.status(401); return; }
            String target = ctx.queryParam("target");       // addr hoặc "broadcast"
            String targetName = ctx.queryParam("targetName"); // tên peer (dùng store-forward)
            String msg = ctx.queryParam("msg");

            if ("broadcast".equals(target)) {
                node.sendBroadcast(msg);
                ctx.result("OK");
            } else {
                boolean success = node.sendDirect(target, targetName, msg);
                if (success) {
                    ctx.result("OK");
                } else {
                    // Store-and-forward: lưu tin ở Bootstrap chờ peer online
                    boolean stored = node.storeForward(targetName, msg);
                    ctx.status(503).result(stored ? "STORED" : "FAILED");
                }
            }
        });

        // ── Tạo nhóm và gửi invite đến thành viên ──
        app.post("/api/creategroup", ctx -> {
            if (node == null) { ctx.status(401); return; }
            String groupId = ctx.queryParam("groupId");
            String groupName = ctx.queryParam("groupName");
            String membersParam = ctx.queryParam("members"); // "addr1,addr2"
            if (membersParam == null || membersParam.isEmpty()) { ctx.status(400); return; }
            List<String> members = Arrays.asList(membersParam.split(","));
            node.sendGroupInvite(groupId, groupName, members);
            ctx.result("OK");
        });

        // ── Gửi group chat (chọn nhiều người) ──
        app.post("/api/sendgroup", ctx -> {
            if (node == null) { ctx.status(401); return; }
            String groupId = ctx.queryParam("groupId");
            String msg = ctx.queryParam("msg");
            String membersParam = ctx.queryParam("members"); // "addr1,addr2,addr3"
            if (membersParam == null || membersParam.isEmpty()) { ctx.status(400); return; }
            List<String> members = Arrays.asList(membersParam.split(","));
            node.sendGroupSelected(groupId, members, msg);
            ctx.result("OK");
        });

        // ── Upload file để gửi ──
        app.post("/api/sendfile", ctx -> {
            if (node == null) { ctx.status(401); return; }
            String target = ctx.queryParam("target");
            var uploadedFile = ctx.uploadedFile("file");
            if (uploadedFile == null) { ctx.status(400).result("Không có file"); return; }
            byte[] data = uploadedFile.content().readAllBytes();
            boolean success = node.sendFile(target, uploadedFile.filename(), data);
            ctx.result(success ? "OK" : "FAILED");
        });

        System.out.println("\n===========================================");
        System.out.println(">>> Giao diện chat: http://localhost:" + webPort);
        System.out.println("===========================================\n");
    }

    private static int findFreePort(int startPort) {
        int port = startPort;
        while (port < startPort + 100) {
            try (ServerSocket ignored = new ServerSocket(port)) { return port; }
            catch (IOException e) { port++; }
        }
        return 0;
    }

    private static void startHeartbeat() {
        new Thread(() -> {
            while (true) {
                try (Socket s = new Socket("localhost", 8888);
                     PrintWriter out = new PrintWriter(s.getOutputStream(), true);
                     BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()))) {

                    out.println("REGISTER|" + node.getUsername() + "-127.0.0.1:" + node.getMyPort());
                    String res = in.readLine();
                    if (res != null && res.startsWith("LIST|")) {
                        String[] sections = res.split("\\|PENDING\\|");
                        String listPart = sections[0].substring(5); // bỏ "LIST|"

                        // Cập nhật danh sách peer
                        if (!listPart.isEmpty()) {
                            node.setOnlinePeers(Arrays.asList(listPart.split(",")));
                        } else {
                            node.setOnlinePeers(new ArrayList<>());
                        }

                        // Xử lý tin nhắn chờ (store-and-forward)
                        if (sections.length > 1 && !sections[1].isEmpty()) {
                            String[] pending = sections[1].split("~~");
                            for (String pm : pending) {
                                if (!pm.isEmpty()) node.getMessages().add(pm);
                            }
                        }
                    }
                } catch (Exception e) { /* Bootstrap chưa bật */ }
                try { Thread.sleep(3000); } catch (Exception e) {}
            }
        }).start();
    }
}
