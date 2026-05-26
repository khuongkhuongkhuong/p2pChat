package com.p2pchat.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.*;
import java.util.*;

public class GroupStore {
    private static final String FILE = "groups.json";
    private static final ObjectMapper mapper = new ObjectMapper();

    private static ObjectNode load() {
        try {
            Path p = Paths.get(FILE);
            if (!Files.exists(p))
                return mapper.createObjectNode();
            return (ObjectNode) mapper.readTree(p.toFile());
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private static void save(ObjectNode root) {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(Paths.get(FILE).toFile(), root);
        } catch (Exception e) {
            System.err.println("Lỗi lưu groups: " + e.getMessage());
        }
    }

    public static void createGroup(String id, String name, String owner, List<String> members) {
        ObjectNode root = load();

        LinkedHashSet<String> uniqueMembers = new LinkedHashSet<>(members);
        uniqueMembers.add(owner);

        ObjectNode g = mapper.createObjectNode();
        g.put("name", name);
        g.put("owner", owner);

        ArrayNode arr = mapper.createArrayNode();
        uniqueMembers.forEach(arr::add);
        g.set("members", arr);

        root.set(id, g);
        save(root);
    }

    public static List<Map<String, Object>> getGroupsOfUser(String username) {
        List<Map<String, Object>> result = new ArrayList<>();
        ObjectNode root = load();

        root.fieldNames().forEachRemaining(id -> {
            ObjectNode g = (ObjectNode) root.get(id);
            List<String> members = getMembersFromNode(g);

            if (members.contains(username)) {
                result.add(Map.of(
                        "id", id,
                        "name", g.get("name").asText(),
                        "owner", g.get("owner").asText(),
                        "members", members));
            }
        });

        return result;
    }

    public static List<String> getMembers(String groupId) {
        ObjectNode g = (ObjectNode) load().get(groupId);
        if (g == null)
            return List.of();
        return getMembersFromNode(g);
    }

    public static String getOwner(String groupId) {
        try {
            ObjectNode root = load();

            if (!root.has(groupId)) {
                return null;
            }

            return root.get(groupId).path("owner").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    public static void deleteGroup(String groupId, String requester) {
        ObjectNode root = load();
        ObjectNode g = (ObjectNode) root.get(groupId);
        if (g == null)
            return;

        String owner = g.get("owner").asText();
        if (!owner.equals(requester))
            return;

        root.remove(groupId);
        save(root);
    }

    public static void deleteGroupLocal(String groupId) {
        try {
            ObjectNode root = load();

            root.remove(groupId);

            save(root);
        } catch (Exception e) {
            System.err.println("Không xóa được group local: " + e.getMessage());
        }
    }

    public static String getGroupName(String groupId) {
        try {
            ObjectNode root = load();

            if (!root.has(groupId)) {
                return null;
            }

            return root.get(groupId).path("name").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> getMembersFromNode(ObjectNode g) {
        List<String> members = new ArrayList<>();
        if (g == null || !g.has("members"))
            return members;
        g.withArray("members").forEach(n -> members.add(n.asText()));
        return members;
    }
}