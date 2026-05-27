package com.p2pchat.auth;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Lưu lịch sử chat xuống file text, 1 file per (user, conversationKey).
 * Tên file: history_{username}_{safeKey}.txt
 * Key dùng tên peer (peer:Alice) hoặc nhóm (group:12345) → bền qua restart.
 */
public class ChatHistoryStore {

    private static final String DIR = "chat_history";
    private static final Map<String, ReentrantReadWriteLock> locks = new java.util.concurrent.ConcurrentHashMap<>();

    static {
        try {
            Files.createDirectories(Paths.get(DIR));
        } catch (IOException e) {
            System.err.println("Không tạo được thư mục lịch sử: " + e.getMessage());
        }
    }

    // ── Tên file an toàn ─────────────────────────────────────────────────────
    private static String fileName(String username, String key) {
        String safe = (username + "_" + key).replaceAll("[^a-zA-Z0-9_\\-]", "_");
        return DIR + "/history_" + safe + ".txt";
    }

    // ── Lấy lock cho từng file ────────────────────────────────────────────────
    private static ReentrantReadWriteLock getLock(String filePath) {
        return locks.computeIfAbsent(filePath, k -> new ReentrantReadWriteLock());
    }

    // ── Ghi 1 dòng vào lịch sử ───────────────────────────────────────────────
    public static void append(String username, String key, String line) {
        String path = fileName(username, key);
        ReentrantReadWriteLock lock = getLock(path);
        lock.writeLock().lock();
        try (BufferedWriter w = new BufferedWriter(
                new FileWriter(path, true))) { // true = append
            w.write(line.replace("\n", " ")); // giữ mỗi tin 1 dòng
            w.newLine();
        } catch (IOException e) {
            System.err.println("Lỗi ghi lịch sử: " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ── Đọc toàn bộ lịch sử ─────────────────────────────────────────────────
    public static List<String> get(String username, String key) {
        String path = fileName(username, key);
        ReentrantReadWriteLock lock = getLock(path);
        lock.readLock().lock();
        try {
            Path p = Paths.get(path);
            if (!Files.exists(p))
                return new ArrayList<>();
            return Files.readAllLines(p, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new ArrayList<>();
        } finally {
            lock.readLock().unlock();
        }
    }

    // ── Danh sách key (cuộc trò chuyện) của 1 user ───────────────────────────
    // Được dùng bởi App.java /api/conversations để load lịch sử sau khi login
    public static List<String> getConversationKeys(String username) {
        List<String> keys = new ArrayList<>();
        try {
            String prefix = "history_" + username.replaceAll("[^a-zA-Z0-9_\\-]", "_") + "_";
            File[] files = new File(DIR).listFiles(
                    f -> f.getName().startsWith(prefix) && f.getName().endsWith(".txt"));

            if (files == null)
                return keys;

            for (File f : files) {
                String safePart = f.getName().substring(
                        prefix.length(),
                        f.getName().length() - 4);

                String key;
                if (safePart.equals("broadcast")) {
                    key = "broadcast";
                } else {
                    key = safePart.replaceFirst("^(peer|group)_", "$1:");
                }

                if (!key.isBlank())
                    keys.add(key);
            }
        } catch (Exception e) {
            System.err.println("Lỗi đọc danh sách cuộc trò chuyện: " + e.getMessage());
        }
        return keys;
    }

    // ── Xóa lịch sử 1 cuộc trò chuyện ───────────────────────────────────────
    public static void clear(String username, String key) {
        try {
            Files.deleteIfExists(Paths.get(fileName(username, key)));
        } catch (IOException e) {
            System.err.println("Lỗi xóa lịch sử: " + e.getMessage());
        }
    }
}
