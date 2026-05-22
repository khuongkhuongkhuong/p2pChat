package com.p2pchat.node;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.Base64;

public class P2PNode {
    private String username;
    private int myPort;
    private List<String> onlinePeers = new CopyOnWriteArrayList<>();
    private List<String> messageQueue = new CopyOnWriteArrayList<>();

    // Lịch sử chat: key = "addr" hoặc "groupId", value = danh sách tin
    private Map<String, List<String>> chatHistory = new ConcurrentHashMap<>();

    // AES key dùng chung (trong thực tế sẽ trao đổi qua RSA)
    private static final String AES_KEY = "P2PChatSecretKey"; // 16 bytes = 128-bit AES

    public P2PNode(String username) {
        this.username = username;
    }

    // ===================== AES ENCRYPTION =====================
    private String encrypt(String plainText) {
        try {
            SecretKeySpec key = new SecretKeySpec(AES_KEY.getBytes(), "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] encrypted = cipher.doFinal(plainText.getBytes("UTF-8"));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            return plainText; // fallback không mã hóa
        }
    }

    private String decrypt(String cipherText) {
        try {
            SecretKeySpec key = new SecretKeySpec(AES_KEY.getBytes(), "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, key);
            byte[] decoded = Base64.getDecoder().decode(cipherText);
            return new String(cipher.doFinal(decoded), "UTF-8");
        } catch (Exception e) {
            return cipherText; // fallback nếu không giải mã được
        }
    }

    // ===================== LISTENING =====================
    public void startListening() {
        new Thread(() -> {
            try (ServerSocket ss = new ServerSocket(0)) {
                this.myPort = ss.getLocalPort();
                while (true) {
                    try (Socket s = ss.accept();
                         BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()))) {
                        String raw = in.readLine();
                        if (raw == null) continue;

                        // Xử lý file transfer (binary qua base64)
                        if (raw.startsWith("[FILE]|")) {
                            handleIncomingFile(raw);
                        } else {
                            // Giải mã tin nhắn
                            String msg = decryptMessage(raw);
                            messageQueue.add(msg);
                            saveToHistory(msg);
                        }
                    } catch (Exception e) { System.err.println("Lỗi nhận tin: " + e.getMessage()); }
                }
            } catch (IOException e) { e.printStackTrace(); }
        }).start();
    }

    // Giải mã phần nội dung tin nhắn, giữ nguyên format header
    private String decryptMessage(String raw) {
        try {
            // GROUP_INVITE không mã hóa, giữ nguyên
            if (raw.startsWith("[GROUP_INVITE]|")) return raw;
            if (raw.startsWith("[Private]|")) {
                // [Private]|sender-addr|ENCRYPTED_CONTENT
                String[] parts = raw.split("\\|", 3);
                if (parts.length == 3) {
                    String decrypted = decrypt(parts[2]);
                    return parts[0] + "|" + parts[1] + "|" + decrypted;
                }
            } else if (raw.startsWith("[Group]|")) {
                // [Group]|groupId|sender-addr|ENCRYPTED_CONTENT
                String[] parts = raw.split("\\|", 4);
                if (parts.length == 4) {
                    String decrypted = decrypt(parts[3]);
                    return parts[0] + "|" + parts[1] + "|" + parts[2] + "|" + decrypted;
                }
            } else if (raw.startsWith("[Broadcast]|")) {
                String[] parts = raw.split("\\|", 2);
                if (parts.length == 2) {
                    return parts[0] + "|" + decrypt(parts[1]);
                }
            }
        } catch (Exception e) { /* giữ nguyên nếu lỗi */ }
        return raw;
    }

    // ===================== SEND DIRECT =====================
    public boolean sendDirect(String targetAddr, String targetName, String msg) {
        String content = username + ": " + msg;
        String encrypted = encrypt(content);
        String formatted = "[Private]|" + username + "-127.0.0.1:" + myPort + "|" + encrypted;

        String[] parts = targetAddr.split(":");
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(parts[0], Integer.parseInt(parts[1])), 2000);
            PrintWriter out = new PrintWriter(s.getOutputStream(), true);
            out.println(formatted);
            // Lưu lịch sử phía người gửi
            chatHistory.computeIfAbsent(targetAddr, k -> new CopyOnWriteArrayList<>())
                .add("Tôi: " + msg);
            return true;
        } catch (Exception e) {
            System.err.println(">>> Peer " + targetAddr + " không phản hồi.");
            return false;
        }
    }

    // ===================== STORE-AND-FORWARD =====================
    public boolean storeForward(String targetName, String msg) {
        String content = username + ": " + msg;
        String encrypted = encrypt(content);
        String storeMsg = "[Private]|" + username + "-127.0.0.1:" + myPort + "|" + encrypted;

        try (Socket s = new Socket("localhost", 8888);
             PrintWriter out = new PrintWriter(s.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()))) {
            out.println("STORE|" + targetName + "|" + storeMsg);
            String res = in.readLine();
            return "STORED".equals(res);
        } catch (Exception e) {
            return false;
        }
    }

    // ===================== GROUP INVITE =====================
    // Gửi thông báo tạo nhóm đến tất cả thành viên
    // Format: [GROUP_INVITE]|groupId|groupName|member1addr,member2addr,...
    public void sendGroupInvite(String groupId, String groupName, List<String> memberAddrs) {
        String myAddr = "127.0.0.1:" + myPort;
        String membersStr = String.join(",", memberAddrs);
        String formatted = "[GROUP_INVITE]|" + groupId + "|" + groupName + "|" + membersStr + "|" + username + "-" + myAddr;

        for (String addr : memberAddrs) {
            if (addr.equals(myAddr)) continue;
            String[] parts = addr.split(":");
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(parts[0], Integer.parseInt(parts[1])), 2000);
                PrintWriter out = new PrintWriter(s.getOutputStream(), true);
                out.println(formatted);
            } catch (Exception e) {
                System.err.println(">>> Không gửi invite đến: " + addr);
            }
        }
    }

    // ===================== GROUP CHAT (chọn nhiều người) =====================
    public void sendGroupSelected(String groupId, List<String> memberAddrs, String msg) {
        String content = username + ": " + msg;
        String encrypted = encrypt(content);
        String formatted = "[Group]|" + groupId + "|" + username + "-127.0.0.1:" + myPort + "|" + encrypted;

        // Lưu lịch sử nhóm
        chatHistory.computeIfAbsent("group:" + groupId, k -> new CopyOnWriteArrayList<>())
            .add("Tôi: " + msg);

        String myAddr = "127.0.0.1:" + myPort;
        for (String addr : memberAddrs) {
            if (addr.equals(myAddr)) continue;
            String[] parts = addr.split(":");
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(parts[0], Integer.parseInt(parts[1])), 2000);
                PrintWriter out = new PrintWriter(s.getOutputStream(), true);
                out.println(formatted);
            } catch (Exception e) {
                System.err.println(">>> Không gửi được đến: " + addr);
            }
        }
    }

    // ===================== BROADCAST (toàn mạng) =====================
    public void sendBroadcast(String msg) {
        String encrypted = encrypt(username + ": " + msg);
        String formatted = "[Broadcast]|" + encrypted;
        String myAddr = "127.0.0.1:" + myPort;
        for (String peer : onlinePeers) {
            if (!peer.contains("-")) continue;
            String addr = peer.split("-")[1];
            if (addr.equals(myAddr)) continue;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(addr.split(":")[0], Integer.parseInt(addr.split(":")[1])), 2000);
                PrintWriter out = new PrintWriter(s.getOutputStream(), true);
                out.println(formatted);
            } catch (Exception e) { }
        }
    }

    // ===================== FILE TRANSFER =====================
    public boolean sendFile(String targetAddr, String fileName, byte[] fileData) {
        String encoded = Base64.getEncoder().encodeToString(fileData);
        String formatted = "[FILE]|" + username + "-127.0.0.1:" + myPort + "|" + fileName + "|" + encoded;
        String[] parts = targetAddr.split(":");
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(parts[0], Integer.parseInt(parts[1])), 5000);
            PrintWriter out = new PrintWriter(s.getOutputStream(), true);
            out.println(formatted);
            // Thêm thông báo vào history
            chatHistory.computeIfAbsent(targetAddr, k -> new CopyOnWriteArrayList<>())
                .add("Tôi đã gửi file: " + fileName);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void handleIncomingFile(String raw) {
        // [FILE]|sender-addr|fileName|base64data
        String[] parts = raw.split("\\|", 4);
        if (parts.length < 4) return;
        String senderInfo = parts[1];
        String fileName = parts[2];
        String encoded = parts[3];
        String senderAddr = senderInfo.split("-")[1];
        String senderName = senderInfo.split("-")[0];

        try {
            byte[] data = Base64.getDecoder().decode(encoded);
            String savePath = "received_" + fileName;
            Files.write(Paths.get(savePath), data);
            String notification = "[FILE_RECEIVED]|" + senderAddr + "|" + senderName + " đã gửi file: " + fileName + " (đã lưu tại " + savePath + ")";
            messageQueue.add(notification);
            chatHistory.computeIfAbsent(senderAddr, k -> new CopyOnWriteArrayList<>())
                .add(senderName + " gửi file: " + fileName);
        } catch (Exception e) {
            System.err.println("Lỗi lưu file: " + e.getMessage());
        }
    }

    // ===================== HISTORY =====================
    private void saveToHistory(String msg) {
        if (msg.startsWith("[Private]|")) {
            String[] parts = msg.split("\\|", 3);
            if (parts.length == 3) {
                String addr = parts[1].split("-")[1];
                String content = parts[2];
                chatHistory.computeIfAbsent(addr, k -> new CopyOnWriteArrayList<>()).add(content);
            }
        } else if (msg.startsWith("[Group]|")) {
            String[] parts = msg.split("\\|", 4);
            if (parts.length == 4) {
                chatHistory.computeIfAbsent("group:" + parts[1], k -> new CopyOnWriteArrayList<>()).add(parts[3]);
            }
        } else if (msg.startsWith("[Broadcast]|")) {
            chatHistory.computeIfAbsent("broadcast", k -> new CopyOnWriteArrayList<>()).add(msg.split("\\|", 2)[1]);
        }
    }

    public List<String> getHistory(String key) {
        return chatHistory.getOrDefault(key, new ArrayList<>());
    }

    // ===================== GETTERS / SETTERS =====================
    public int getMyPort() { return myPort; }
    public String getUsername() { return username; }
    public List<String> getOnlinePeers() { return onlinePeers; }
    public void setOnlinePeers(List<String> list) { this.onlinePeers = new CopyOnWriteArrayList<>(list); }
    public List<String> getMessages() { return messageQueue; }
}
