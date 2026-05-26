package com.p2pchat.node;

import com.p2pchat.auth.ChatHistoryStore;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

public class P2PNode {
    private final String username;
    private int myPort;
    private final String myIp;
    private final String bootstrapHost;

    private List<String> onlinePeers  = new CopyOnWriteArrayList<>();
    private List<String> messageQueue = new CopyOnWriteArrayList<>();
    private Map<String, List<String>> chatHistory = new ConcurrentHashMap<>();

    // File nhận được lưu trong RAM: fileId → data / fileName
    private final Map<String, byte[]>  receivedFiles     = new ConcurrentHashMap<>();
    private final Map<String, String>  receivedFileNames = new ConcurrentHashMap<>();

    private static final String AES_KEY = "P2PChatSecretKey"; // 16 bytes = AES-128

    // ── Constructor duy nhất ─────────────────────────────────────────────────
    public P2PNode(String username, String bootstrapHost) {
        this.username      = username;
        this.bootstrapHost = bootstrapHost;
        this.myIp          = detectLocalIp();
        System.out.println(">>> IP của máy này: " + myIp);
    }

    // ── Tự phát hiện IP thật (không phải 127.0.0.1) ─────────────────────────
    private static String detectLocalIp() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("8.8.8.8", 80), 1000);
            return s.getLocalAddress().getHostAddress();
        } catch (Exception e) {
            try { return InetAddress.getLocalHost().getHostAddress(); }
            catch (Exception ex) { return "127.0.0.1"; }
        }
    }

    // ── Địa chỉ đầy đủ ip:port ──────────────────────────────────────────────
    public String getMyAddr() { return myIp + ":" + myPort; }

    // ===================== AES =====================
    private String encrypt(String plain) {
        try {
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            byte[] iv = new byte[16];
            SecureRandom random = new SecureRandom();
            random.nextBytes(iv);
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(AES_KEY.getBytes("UTF-8"), "AES"),
                    new IvParameterSpec(iv));
            String data = Base64.getEncoder().encodeToString(c.doFinal(plain.getBytes("UTF-8")));
            return Base64.getEncoder().encodeToString(iv) + ":" + data;
        } catch (Exception e) { return plain; }
    }

    private String decrypt(String cipherText) {
        try {
            String[] parts = cipherText.split(":", 2);
            if (parts.length != 2) return cipherText;
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] data = Base64.getDecoder().decode(parts[1]);
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(AES_KEY.getBytes("UTF-8"), "AES"),
                    new IvParameterSpec(iv));
            return new String(c.doFinal(data), "UTF-8");
        } catch (Exception e) { return cipherText; }
    }

    // ===================== LISTENING =====================
    public void startListening() {
        new Thread(() -> {
            try (ServerSocket ss = new ServerSocket(0)) {
                myPort = ss.getLocalPort();
                System.out.println(">>> Đang lắng nghe tại " + getMyAddr());
                while (true) {
                    try (Socket s = ss.accept();
                         BufferedReader in = new BufferedReader(
                                 new InputStreamReader(s.getInputStream(), "UTF-8"));
                         PrintWriter out = new PrintWriter(s.getOutputStream(), true)) {
                        String raw = in.readLine();
                        if (raw == null) continue;
                        if (raw.startsWith("[FILE]|")) {
                            handleIncomingFile(raw);
                        } else {
                            String msg = decryptMessage(raw);
                            messageQueue.add(msg);
                            saveToHistory(msg);
                        }
                        out.println("ACK");
                    }
                }
            } catch (Exception e) {
                System.err.println("Lỗi lắng nghe: " + e.getMessage());
            }
        }).start();
    }
    private String decryptMessage(String raw) {
        try {
            if (raw.startsWith("[GROUP_INVITE]|") || raw.startsWith("[GROUP_DELETE]|")) return raw;
            if (raw.startsWith("[Private]|")) {
                String[] p = raw.split("\\|", 3);
                return p.length == 3 ? p[0] + "|" + p[1] + "|" + decrypt(p[2]) : raw;
            }
            if (raw.startsWith("[Group]|")) {
                String[] p = raw.split("\\|", 4);
                return p.length == 4 ? p[0] + "|" + p[1] + "|" + p[2] + "|" + decrypt(p[3]) : raw;
            }
            if (raw.startsWith("[Broadcast]|")) {
                String[] p = raw.split("\\|", 2);
                return p.length == 2 ? p[0] + "|" + decrypt(p[1]) : raw;
            }
        } catch (Exception ignored) {}
        return raw;
    }

    // ===================== SEND DIRECT =====================
    public boolean sendDirect(String targetAddr, String targetName, String msg) {
        String formatted = "[Private]|" + username + "-" + getMyAddr() + "|" + encrypt(msg);
        String[] parts   = targetAddr.split(":");
        for (int attempt = 1; attempt <= 2; attempt++) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(parts[0], Integer.parseInt(parts[1])), 2000);
                PrintWriter out = new PrintWriter(s.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
                out.println(formatted);
                String response = in.readLine();
                if ("ACK".equals(response)) {
                    String histKey = "peer:" + targetName;
                    String record  = "ME|" + msg;
                    chatHistory.computeIfAbsent(histKey, k -> new CopyOnWriteArrayList<>()).add(record);
                    ChatHistoryStore.append(username, histKey, record);
                    return true;
                }
            } catch (Exception e) {
                System.err.println(">>> Thử gửi tới " + targetAddr + " lần " + attempt + " thất bại: " + e.getMessage());
            }
            try { Thread.sleep(250); } catch (InterruptedException ignored) {}
        }
        System.err.println(">>> Peer " + targetAddr + " không phản hồi sau 2 lần thử.");
        return false;
    }

    // ===================== STORE-AND-FORWARD =====================
    public boolean storeForward(String targetName, String msg) {
        String storeMsg = "[Private]|" + username + "-" + getMyAddr() + "|" + encrypt(msg);
        try (Socket s   = new Socket(bootstrapHost, 8888);
             PrintWriter out = new PrintWriter(s.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()))) {
            out.println("STORE|" + targetName + "|" + storeMsg);
            return "STORED".equals(in.readLine());
        } catch (Exception e) { return false; }
    }

    // ===================== GROUP INVITE =====================
    public void sendGroupInvite(String groupId, String groupName, List<String> memberAddrs) {
        String formatted = "[GROUP_INVITE]|" + groupId + "|" + groupName + "|"
                + String.join(",", memberAddrs) + "|" + username + "-" + getMyAddr();
        sendToAddrs(memberAddrs, formatted);
    }

    // ===================== GROUP DELETE =====================
    public void sendGroupDelete(String groupId, String groupName, List<String> memberAddrs) {
        String formatted = "[GROUP_DELETE]|" + groupId + "|" + groupName + "|"
                + username + "-" + getMyAddr();
        sendToAddrs(memberAddrs, formatted);
    }

    // ===================== GROUP CHAT =====================
    public void sendGroupSelected(String groupId, List<String> memberAddrs, String msg) {
        // String formatted = "[Group]|" + groupId + "|" + username + "-" + getMyAddr()
        //         + "|" + encrypt(username + ": " + msg);
        String formatted = "[Group]|" + groupId + "|" + username + "-" + getMyAddr()
        + "|" + encrypt(msg);
        String histKey = "group:" + groupId;
        String record  = "ME|" + msg;
        chatHistory.computeIfAbsent(histKey, k -> new CopyOnWriteArrayList<>()).add(record);
        ChatHistoryStore.append(username, histKey, record);
        sendToAddrs(memberAddrs, formatted);
    }

    // ===================== BROADCAST =====================
    public void sendBroadcast(String msg) {
        String formatted = "[Broadcast]|" + encrypt(username + ": " + msg);
        List<String> addrs = new ArrayList<>();
        for (String peer : onlinePeers) {
            if (peer.contains("-")) addrs.add(peer.substring(peer.lastIndexOf('-') + 1));
        }
        sendToAddrs(addrs, formatted);
    }

    // ── Gửi 1 message đến danh sách địa chỉ (bỏ qua addr của bản thân) ──────
    private void sendToAddrs(List<String> addrs, String formatted) {
        String myAddr = getMyAddr();
        for (String addr : addrs) {
            if (addr.equals(myAddr)) continue;
            String[] p = addr.split(":");
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(p[0], Integer.parseInt(p[1])), 2000);
                new PrintWriter(s.getOutputStream(), true).println(formatted);
            } catch (Exception e) {
                System.err.println(">>> Không gửi được đến: " + addr);
            }
        }
    }

    // ===================== FILE TRANSFER =====================
    public boolean sendFile(String targetAddr, String fileName, byte[] fileData) {
        String formatted = "[FILE]|" + username + "-" + getMyAddr() + "|"
                + fileName + "|" + Base64.getEncoder().encodeToString(fileData);
        String[] parts = targetAddr.split(":");
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(parts[0], Integer.parseInt(parts[1])), 5000);
            BufferedWriter out = new BufferedWriter(
                    new OutputStreamWriter(s.getOutputStream(), "UTF-8"));
            out.write(formatted); out.newLine(); out.flush();
            return true;
        } catch (Exception e) {
            System.err.println("Lỗi gửi file: " + e.getMessage());
            return false;
        }
    }

    private void handleIncomingFile(String raw) {
        // [FILE]|senderName-senderAddr|fileName|base64data
        String[] parts = raw.split("\\|", 4);
        if (parts.length < 4) return;
        String senderInfo = parts[1];
        String fileName   = parts[2];
        String encoded    = parts[3];
        int dash = senderInfo.lastIndexOf('-');
        String senderAddr = dash >= 0 ? senderInfo.substring(dash + 1) : senderInfo;
        String senderName = dash >= 0 ? senderInfo.substring(0, dash) : "unknown";
        try {
            byte[] data   = Base64.getDecoder().decode(encoded);
            String fileId = String.valueOf(System.currentTimeMillis());
            receivedFiles.put(fileId, data);
            receivedFileNames.put(fileId, fileName);
            messageQueue.add("[FILE_RECEIVED]|" + senderAddr + "|" + senderName
                    + "|" + fileName + "|" + fileId);
        } catch (Exception e) {
            System.err.println("Lỗi nhận file: " + e.getMessage());
        }
    }

    // ===================== HISTORY =====================
    /**
     * Format lưu file: "THEM|nội dung" hoặc "ME|nội dung"
     * → nhất quán, không phụ thuộc tên người dùng khi render
     */
    private void saveToHistory(String msg) {
        if (msg.startsWith("[Private]|")) {
            String[] p = msg.split("\\|", 3);
            if (p.length == 3) {
                int dash = p[1].lastIndexOf('-');
                String senderName = dash >= 0 ? p[1].substring(0, dash) : p[1];
                String histKey = "peer:" + senderName;
                // "THEM|nội dung" → frontend biết đây là tin đến
                String record = "THEM|" + p[2];
                chatHistory.computeIfAbsent(histKey, k -> new CopyOnWriteArrayList<>()).add(record);
                ChatHistoryStore.append(username, histKey, record);
            }
        } else if (msg.startsWith("[Group]|")) {
            String[] p = msg.split("\\|", 4);
            if (p.length == 4) {
                int dash = p[2].lastIndexOf('-');
                String senderName = dash >= 0 ? p[2].substring(0, dash) : p[2];
                String histKey = "group:" + p[1];
                // group giữ tên để hiển thị ai nói
                String record = "THEM|" + senderName + ": " + p[3];
                chatHistory.computeIfAbsent(histKey, k -> new CopyOnWriteArrayList<>()).add(record);
                ChatHistoryStore.append(username, histKey, record);
            }
        } else if (msg.startsWith("[Broadcast]|")) {
            String content = msg.split("\\|", 2)[1];
            String record  = "THEM|" + content;
            chatHistory.computeIfAbsent("broadcast", k -> new CopyOnWriteArrayList<>()).add(record);
            ChatHistoryStore.append(username, "broadcast", record);
        }
    }

    public List<String> getHistory(String key) {
        List<String> fromFile = ChatHistoryStore.get(username, key);
        return fromFile.isEmpty() ? chatHistory.getOrDefault(key, new ArrayList<>()) : fromFile;
    }

    // ===================== GETTERS / SETTERS =====================
    public int    getMyPort()    { return myPort; }
    public String getMyIp()      { return myIp; }
    public String getUsername()  { return username; }
    public List<String> getOnlinePeers() { return onlinePeers; }
    public void   setOnlinePeers(List<String> list) {
        this.onlinePeers = new CopyOnWriteArrayList<>(list);
    }
    public List<String> getMessages() { return messageQueue; }
    public byte[] getReceivedFile(String fileId)     { return receivedFiles.get(fileId); }
    public String getReceivedFileName(String fileId) { return receivedFileNames.get(fileId); }
}
