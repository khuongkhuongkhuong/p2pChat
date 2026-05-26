package com.p2pchat.node;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.p2pchat.auth.GroupStore;
import com.p2pchat.auth.ChatHistoryStore;

public class P2PNode {
    private final String username;
    private int myPort;
    private final String myIp;
    private final String bootstrapHost;

    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private Thread listenerThread;

    private List<String> onlinePeers = new CopyOnWriteArrayList<>();
    private List<String> messageQueue = new CopyOnWriteArrayList<>();
    private Map<String, List<String>> chatHistory = new ConcurrentHashMap<>();

    // File nhận được lưu trong RAM: fileId → data / fileName
    private final Map<String, byte[]> receivedFiles = new ConcurrentHashMap<>();
    private final Map<String, String> receivedFileNames = new ConcurrentHashMap<>();

    private static final String AES_KEY = "P2PChatSecretKey"; // 16 bytes = AES-128

    // ── Constructor duy nhất ─────────────────────────────────────────────────
    public P2PNode(String username, String bootstrapHost) {
        this.username = username;
        this.bootstrapHost = bootstrapHost;
        this.myIp = detectLocalIp();
        System.out.println(">>> IP của máy này: " + myIp);
    }

    // ── Tự phát hiện IP thật (không phải 127.0.0.1) ─────────────────────────
    private static String detectLocalIp() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("8.8.8.8", 80), 1000);
            return s.getLocalAddress().getHostAddress();
        } catch (Exception e) {
            try {
                return InetAddress.getLocalHost().getHostAddress();
            } catch (Exception ex) {
                return "127.0.0.1";
            }
        }
    }

    // ── Địa chỉ đầy đủ ip:port ──────────────────────────────────────────────
    public String getMyAddr() {
        return myIp + ":" + myPort;
    }

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
        } catch (Exception e) {
            return plain;
        }
    }

    private String decrypt(String cipherText) {
        try {
            String[] parts = cipherText.split(":", 2);
            if (parts.length != 2)
                return cipherText;
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] data = Base64.getDecoder().decode(parts[1]);
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(AES_KEY.getBytes("UTF-8"), "AES"),
                    new IvParameterSpec(iv));
            return new String(c.doFinal(data), "UTF-8");
        } catch (Exception e) {
            return cipherText;
        }
    }

    // ===================== LISTENING =====================
    private boolean canReceiveGroupMessage(String msg) {
        try {
            String[] p = msg.split("\\|", 4);

            if (p.length < 4) {
                return false;
            }

            String groupId = p[1];

            List<String> members = GroupStore.getMembers(groupId);

            if (members == null || members.isEmpty()) {
                return false;
            }

            return members.contains(username);
        } catch (Exception e) {
            System.err.println("Lỗi kiểm tra quyền nhận group message: " + e.getMessage());
            return false;
        }
    }

    public void startListening() {
        if (running)
            return;

        running = true;

        listenerThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(0);
                myPort = serverSocket.getLocalPort();
                System.out.println(">>> Đang lắng nghe tại " + getMyAddr());

                while (running) {
                    try (Socket s = serverSocket.accept();
                            BufferedReader in = new BufferedReader(
                                    new InputStreamReader(s.getInputStream(), "UTF-8"));
                            PrintWriter out = new PrintWriter(s.getOutputStream(), true)) {

                        String raw = in.readLine();
                        if (raw == null)
                            continue;

                        if (raw.startsWith("[FILE]|")) {
                            handleIncomingFile(raw);
                        } else {
                            String msg = decryptMessage(raw);

                            if (msg.startsWith("[GROUP_INVITE]|")) {
                                messageQueue.add(msg);
                                handleGroupInvite(msg);
                            } else if (msg.startsWith("[Group]|")) {
                                if (canReceiveGroupMessage(msg)) {
                                    messageQueue.add(msg);
                                    saveToHistory(msg);
                                } else {
                                    System.out.println(">>> Bỏ qua tin nhóm không hợp lệ cho user: " + username);
                                }
                            } else if (msg.startsWith("[GROUP_DELETE]|")) {
                                messageQueue.add(msg);
                                handleGroupDelete(msg);
                            } else {
                                messageQueue.add(msg);
                                saveToHistory(msg);
                            }
                        }

                        out.println("ACK");

                    } catch (Exception e) {
                        if (running) {
                            System.err.println("Lỗi xử lý kết nối peer: " + e.getMessage());
                        }
                    }
                }

            } catch (Exception e) {
                if (running) {
                    System.err.println("Lỗi lắng nghe: " + e.getMessage());
                }
            } finally {
                running = false;
                myPort = 0;
                serverSocket = null;
                System.out.println(">>> Peer listener đã dừng: " + username);
            }
        });

        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    public void stop() {
        running = false;

        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (Exception ignored) {
        }

        try {
            if (listenerThread != null && listenerThread.isAlive()) {
                listenerThread.interrupt();
            }
        } catch (Exception ignored) {
        }

        myPort = 0;
    }

    private void handleGroupInvite(String msg) {
        try {
            String[] p = msg.split("\\|", 5);

            if (p.length < 5)
                return;

            String groupId = p[1];
            String groupName = p[2];
            String membersStr = p[3];
            String ownerInfo = p[4];

            String ownerName = ownerInfo.contains("-")
                    ? ownerInfo.substring(0, ownerInfo.lastIndexOf("-"))
                    : ownerInfo;

            List<String> members = new ArrayList<>();

            if (!membersStr.isBlank()) {
                for (String m : membersStr.split(",")) {
                    String name = m.trim();
                    if (!name.isBlank()) {
                        members.add(name);
                    }
                }
            }

            if (!members.contains(ownerName)) {
                members.add(ownerName);
            }

            if (!members.contains(username)) {
                members.add(username);
            }

            GroupStore.createGroup(groupId, groupName, ownerName, members);

            System.out.println(">>> Đã lưu group invite: " + groupName + " cho user " + username);
        } catch (Exception e) {
            System.err.println("Lỗi xử lý GROUP_INVITE: " + e.getMessage());
        }
    }

    private String decryptMessage(String raw) {
        try {
            if (raw.startsWith("[GROUP_INVITE]|") || raw.startsWith("[GROUP_DELETE]|"))
                return raw;
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
        } catch (Exception ignored) {
        }
        return raw;
    }

    // ===================== SEND DIRECT =====================
    private void saveMyMessageToHistory(String histKey, String msg) {
        String record = "ME|" + msg;
        chatHistory.computeIfAbsent(histKey, k -> new CopyOnWriteArrayList<>()).add(record);
        ChatHistoryStore.append(username, histKey, record);
    }

    public boolean sendDirect(String targetAddr, String targetName, String msg) {
        String formatted = "[Private]|" + username + "-" + getMyAddr() + "|" + encrypt(msg);
        String[] parts = targetAddr.split(":");

        for (int attempt = 1; attempt <= 2; attempt++) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(parts[0], Integer.parseInt(parts[1])), 2000);

                PrintWriter out = new PrintWriter(s.getOutputStream(), true);
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(s.getInputStream(), "UTF-8"));

                out.println(formatted);

                String response = in.readLine();
                if ("ACK".equals(response)) {
                    saveMyMessageToHistory("peer:" + targetName, msg);
                    return true;
                }
            } catch (Exception e) {
                System.err.println(">>> Thử gửi tới " + targetAddr
                        + " lần " + attempt + " thất bại: " + e.getMessage());
            }

            try {
                Thread.sleep(250);
            } catch (InterruptedException ignored) {
            }
        }

        System.err.println(">>> Peer " + targetAddr + " không phản hồi sau 2 lần thử.");
        return false;
    }

    private void handleGroupDelete(String msg) {
        try {
            String[] p = msg.split("\\|", 4);

            if (p.length < 3)
                return;

            String groupId = p[1];

            GroupStore.deleteGroupLocal(groupId);

            System.out.println(">>> Đã xóa group theo thông báo: " + groupId);
        } catch (Exception e) {
            System.err.println("Lỗi xử lý GROUP_DELETE: " + e.getMessage());
        }
    }

    public void receivePendingMessage(String raw) {
        String msg = decryptMessage(raw);

        if (msg.startsWith("[Group]|")) {
            if (canReceiveGroupMessage(msg)) {
                messageQueue.add(msg);
                saveToHistory(msg);
            }
        } else if (msg.startsWith("[GROUP_INVITE]|")) {
            messageQueue.add(msg);
            handleGroupInvite(msg);
        } else if (msg.startsWith("[GROUP_DELETE]|")) {
            messageQueue.add(msg);
            handleGroupDelete(msg);
        } else {
            messageQueue.add(msg);
            saveToHistory(msg);
        }
    }

    // ===================== STORE-AND-FORWARD =====================
    public boolean storeForward(String targetName, String msg) {
        String storeMsg = "[Private]|" + username + "-" + getMyAddr() + "|" + encrypt(msg);

        try (Socket s = new Socket(bootstrapHost, 8888);
                PrintWriter out = new PrintWriter(s.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()))) {

            out.println("STORE|" + targetName + "|" + storeMsg);
            boolean stored = "STORED".equals(in.readLine());

            if (stored) {
                saveMyMessageToHistory("peer:" + targetName, msg);
            }

            return stored;
        } catch (Exception e) {
            return false;
        }
    }

    // ===================== GROUP INVITE =====================
    private boolean sendPendingGroupMessage(String targetUser, String formattedMessage) {
        try (Socket s = new Socket(bootstrapHost, 8888);
                PrintWriter out = new PrintWriter(s.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"))) {

            out.println("STORE|" + targetUser + "|" + formattedMessage);
            return "STORED".equals(in.readLine());

        } catch (Exception e) {
            System.err.println("Không lưu được pending group message cho "
                    + targetUser + ": " + e.getMessage());
            return false;
        }
    }

    private String findPeerAddrByName(String peerName) {
        for (String peer : onlinePeers) {
            int dash = peer.lastIndexOf('-');
            if (dash < 0)
                continue;

            String name = peer.substring(0, dash);
            String addr = peer.substring(dash + 1);

            if (name.equals(peerName) && !"OFFLINE".equalsIgnoreCase(addr)) {
                return addr;
            }
        }

        return null;
    }

    private boolean sendToAddr(String addr, String formatted) {
        if (addr == null || addr.isBlank() || addr.equals(getMyAddr())) {
            return false;
        }

        String[] p = addr.split(":");

        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(p[0], Integer.parseInt(p[1])), 2000);

            PrintWriter out = new PrintWriter(s.getOutputStream(), true);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), "UTF-8"));

            out.println(formatted);

            return "ACK".equals(in.readLine());
        } catch (Exception e) {
            System.err.println(">>> Không gửi được đến: " + addr);
            return false;
        }
    }

    public void sendGroupInvite(
            String groupId,
            String groupName,
            List<String> memberNames,
            List<String> memberAddrs) {
        String formatted = "[GROUP_INVITE]|" + groupId + "|" + groupName + "|"
                + String.join(",", memberNames) + "|" + username + "-" + getMyAddr();

        for (String member : memberNames) {
            if (member.equals(username))
                continue;

            String addr = findPeerAddrByName(member);

            if (addr != null) {
                boolean ok = sendToAddr(addr, formatted);

                if (!ok) {
                    sendPendingGroupMessage(member, formatted);
                }
            } else {
                sendPendingGroupMessage(member, formatted);
            }
        }
    }

    // ===================== GROUP DELETE =====================
    public void sendGroupDelete(String groupId, String groupName, List<String> memberNames) {
        String formatted = "[GROUP_DELETE]|" + groupId + "|" + groupName + "|"
                + username + "-" + getMyAddr();

        for (String member : memberNames) {
            if (member.equals(username))
                continue;

            String addr = findPeerAddrByName(member);

            if (addr != null) {
                boolean ok = sendToAddr(addr, formatted);

                if (!ok) {
                    sendPendingGroupMessage(member, formatted);
                }
            } else {
                sendPendingGroupMessage(member, formatted);
            }
        }
    }

    // ===================== GROUP CHAT =====================
    public void sendGroupSelected(String groupId, List<String> memberNames, String msg) {
        String formatted = "[Group]|" + groupId + "|" + username + "-" + getMyAddr()
                + "|" + encrypt(": " + msg);

        String histKey = "group:" + groupId;
        String record = "ME|" + msg;

        chatHistory.computeIfAbsent(histKey, k -> new CopyOnWriteArrayList<>()).add(record);
        ChatHistoryStore.append(username, histKey, record);

        for (String member : memberNames) {
            if (member.equals(username))
                continue;

            String addr = findPeerAddrByName(member);

            if (addr != null) {
                boolean ok = sendToAddr(addr, formatted);

                if (!ok) {
                    sendPendingGroupMessage(member, formatted);
                }
            } else {
                sendPendingGroupMessage(member, formatted);
            }
        }
    }

    // ===================== BROADCAST =====================
    public void sendBroadcast(String msg) {
        String formatted = "[Broadcast]|" + encrypt(username + ": " + msg);

        saveMyMessageToHistory("broadcast", "📢 " + msg);

        List<String> addrs = new ArrayList<>();
        for (String peer : onlinePeers) {
            if (peer.contains("-")) {
                String addr = peer.substring(peer.lastIndexOf('-') + 1);
                if (!"OFFLINE".equalsIgnoreCase(addr)) {
                    addrs.add(addr);
                }
            }
        }

        sendToAddrs(addrs, formatted);
    }

    // ── Gửi 1 message đến danh sách địa chỉ (bỏ qua addr của bản thân) ──────
    private void sendToAddrs(List<String> addrs, String formatted) {
        String myAddr = getMyAddr();

        for (String addr : addrs) {
            if (addr == null || addr.isBlank())
                continue;
            if (addr.equals(myAddr))
                continue;
            if ("OFFLINE".equalsIgnoreCase(addr))
                continue;

            boolean ok = false;

            for (int attempt = 1; attempt <= 2; attempt++) {
                ok = sendToAddr(addr, formatted);

                if (ok)
                    break;

                try {
                    Thread.sleep(250);
                } catch (InterruptedException ignored) {
                }
            }

            if (!ok) {
                System.err.println(">>> Gửi thất bại sau retry tới: " + addr);
            }
        }
    }

    // ===================== FILE TRANSFER =====================
    public boolean sendFile(String targetAddr, String fileName, byte[] fileData) {
        final int MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB

        if (fileData == null || fileData.length == 0) {
            System.err.println("File rỗng");
            return false;
        }

        if (fileData.length > MAX_FILE_SIZE) {
            System.err.println("File quá lớn, tối đa 5MB");
            return false;
        }

        String formatted = "[FILE]|" + username + "-" + getMyAddr() + "|"
                + fileName + "|" + Base64.getEncoder().encodeToString(fileData);

        String[] parts = targetAddr.split(":");

        for (int attempt = 1; attempt <= 2; attempt++) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(parts[0], Integer.parseInt(parts[1])), 5000);

                BufferedWriter out = new BufferedWriter(
                        new OutputStreamWriter(s.getOutputStream(), "UTF-8"));

                BufferedReader in = new BufferedReader(
                        new InputStreamReader(s.getInputStream(), "UTF-8"));

                out.write(formatted);
                out.newLine();
                out.flush();

                String response = in.readLine();

                if ("ACK".equals(response)) {
                    return true;
                }

            } catch (Exception e) {
                System.err.println("Lỗi gửi file lần " + attempt + ": " + e.getMessage());
            }

            try {
                Thread.sleep(250);
            } catch (InterruptedException ignored) {
            }
        }

        return false;
    }

    private void handleIncomingFile(String raw) {
        // [FILE]|senderName-senderAddr|fileName|base64data
        String[] parts = raw.split("\\|", 4);
        if (parts.length < 4)
            return;
        String senderInfo = parts[1];
        String fileName = parts[2];
        String encoded = parts[3];
        int dash = senderInfo.lastIndexOf('-');
        String senderAddr = dash >= 0 ? senderInfo.substring(dash + 1) : senderInfo;
        String senderName = dash >= 0 ? senderInfo.substring(0, dash) : "unknown";

        try {
            byte[] data = Base64.getDecoder().decode(encoded);

            if (data.length > 5 * 1024 * 1024) {
                System.err.println("Từ chối file quá lớn: " + fileName);
                return;
            }
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
            String record = "THEM|" + content;
            chatHistory.computeIfAbsent("broadcast", k -> new CopyOnWriteArrayList<>()).add(record);
            ChatHistoryStore.append(username, "broadcast", record);
        }
    }

    public List<String> getHistory(String key) {
        List<String> fromFile = ChatHistoryStore.get(username, key);
        return fromFile.isEmpty() ? chatHistory.getOrDefault(key, new ArrayList<>()) : fromFile;
    }

    // ===================== GETTERS / SETTERS =====================
    public int getMyPort() {
        return myPort;
    }

    public String getMyIp() {
        return myIp;
    }

    public String getUsername() {
        return username;
    }

    public List<String> getOnlinePeers() {
        return onlinePeers;
    }

    public void setOnlinePeers(List<String> list) {
        this.onlinePeers = new CopyOnWriteArrayList<>(list);
    }

    public List<String> getMessages() {
        return messageQueue;
    }

    public byte[] getReceivedFile(String fileId) {
        return receivedFiles.get(fileId);
    }

    public String getReceivedFileName(String fileId) {
        return receivedFileNames.get(fileId);
    }
}