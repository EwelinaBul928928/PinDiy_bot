package com.pindiy.bot;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

public class CommentLog {

    private static final Path LOG_FILE       = Paths.get("data", "commented-threads.csv");
    private static final Path BLACKLIST_FILE = Paths.get("blacklist.txt");
    private static final long COOLDOWN_MS    = 50_000;

    private final Set<String> commented  = new HashSet<>();
    private final Set<String> blacklist  = new HashSet<>();
    private long lastCommentEpochMs = 0;

    private CommentLog() {}

    public static CommentLog load() {
        CommentLog log = new CommentLog();

        // Load CSV history
        if (Files.exists(LOG_FILE)) {
            try (BufferedReader br = Files.newBufferedReader(LOG_FILE, StandardCharsets.UTF_8)) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.isBlank() || line.startsWith("#") || line.startsWith("commented_at")) continue;
                    String[] parts = line.split(";", -1);
                    if (parts.length >= 2) {
                        log.commented.add(parts[1].trim());
                        try {
                            long ts = Instant.parse(parts[0].trim()).toEpochMilli();
                            if (ts > log.lastCommentEpochMs) log.lastCommentEpochMs = ts;
                        } catch (Exception ignored) {}
                    }
                }
            } catch (IOException e) {
                System.out.println("[WARN]  Could not read log: " + e.getMessage());
            }
        }

        // Load blacklist
        if (Files.exists(BLACKLIST_FILE)) {
            try (BufferedReader br = Files.newBufferedReader(BLACKLIST_FILE, StandardCharsets.UTF_8)) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (!line.isBlank() && !line.startsWith("#")) {
                        log.blacklist.add(line);
                    }
                }
            } catch (IOException e) {
                System.out.println("[WARN]  Could not read blacklist: " + e.getMessage());
            }
        }

        System.out.println("[INFO]  Loaded " + log.commented.size() + " commented threads.");
        System.out.println("[INFO]  Loaded " + log.blacklist.size() + " blacklisted threads.");
        return log;
    }

    public boolean alreadyCommented(String url) {
        return commented.contains(url);
    }

    public boolean isBlacklisted(String url) {
        for (String pattern : blacklist) {
            if (url.contains(pattern)) return true;
        }
        return false;
    }

    public void record(String url, String title) {
        commented.add(url);
        lastCommentEpochMs = System.currentTimeMillis();
        try {
            Files.createDirectories(LOG_FILE.getParent());
            String line = Instant.now() + ";" + url + ";" + title.replace(";", ",") + "\n";
            Files.writeString(LOG_FILE, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("[WARN]  Could not write log: " + e.getMessage());
        }
    }

    public long msToCooldownEnd() {
        long elapsed = System.currentTimeMillis() - lastCommentEpochMs;
        return Math.max(0, COOLDOWN_MS - elapsed);
    }

    public int totalCommented() {
        return commented.size();
    }
}
