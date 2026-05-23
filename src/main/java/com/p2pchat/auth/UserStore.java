package com.p2pchat.auth;

import at.favre.lib.crypto.bcrypt.BCrypt;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class UserStore {
    private static final String FILE   = "users.json";
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // ── Đọc toàn bộ users ────────────────────────────────────────────────────
    private static ObjectNode load() {
        try {
            Path p = Paths.get(FILE);
            if (!Files.exists(p)) return mapper.createObjectNode();
            return (ObjectNode) mapper.readTree(p.toFile());
        } catch (Exception e) { return mapper.createObjectNode(); }
    }

    // ── Ghi xuống file ───────────────────────────────────────────────────────
    private static void save(ObjectNode data) throws IOException {
        mapper.writerWithDefaultPrettyPrinter().writeValue(Paths.get(FILE).toFile(), data);
    }

    // ── Đăng ký → "OK" | "USERNAME_TAKEN" | "INVALID" ───────────────────────
    public static String register(String username, String password) {
        if (username == null || username.isBlank()
                || !username.matches("[a-zA-Z0-9_]+")
                || password == null || password.length() < 6)
            return "INVALID";

        lock.writeLock().lock();
        try {
            ObjectNode users = load();
            if (users.has(username)) return "USERNAME_TAKEN";

            String hash = BCrypt.withDefaults().hashToString(12, password.toCharArray());
            ObjectNode user = mapper.createObjectNode();
            user.put("passwordHash", hash);
            user.put("createdAt", Instant.now().toString());
            users.set(username, user);
            save(users);
            return "OK";
        } catch (Exception e) {
            return "ERROR";
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ── Đăng nhập → "OK" | "WRONG_PASSWORD" | "NOT_FOUND" ──────────────────
    public static String login(String username, String password) {
        if (username == null || password == null) return "NOT_FOUND";
        lock.readLock().lock();
        try {
            ObjectNode users = load();
            if (!users.has(username)) return "NOT_FOUND";
            String hash = users.get(username).get("passwordHash").asText();
            return BCrypt.verifyer().verify(password.toCharArray(), hash).verified
                    ? "OK" : "WRONG_PASSWORD";
        } finally {
            lock.readLock().unlock();
        }
    }

    // ── Kiểm tra tài khoản tồn tại (dùng bởi BootstrapServer) ──────────────
    // Trả về "EXISTS" nếu có, "NOT_FOUND" nếu không
    public static String exists(String username) {
        if (username == null) return "NOT_FOUND";
        lock.readLock().lock();
        try {
            return load().has(username) ? "EXISTS" : "NOT_FOUND";
        } finally {
            lock.readLock().unlock();
        }
    }
}
