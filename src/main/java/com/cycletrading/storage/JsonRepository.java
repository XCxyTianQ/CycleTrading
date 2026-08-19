package com.cycletrading.storage;

import com.cycletrading.CycleTradingPlugin;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 泛型 JSON 仓储：加载（缺失返回 null，损坏自动隔离 .corrupt 副本）与原子保存（tmp + ATOMIC_MOVE）。
 * 数据格式完全由快照类决定（Gson 按字段名序列化，与历史存档零迁移兼容）。
 */
public final class JsonRepository {

    private final CycleTradingPlugin plugin;
    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public JsonRepository(CycleTradingPlugin plugin, Path dataDir, String name) {
        this.plugin = plugin;
        this.file = dataDir.resolve(name + ".json");
    }

    public Path file() {
        return file;
    }

    /** 加载快照；文件缺失或损坏返回 null（损坏文件保留 .corrupt-<ts> 副本）。 */
    public <T> T load(Class<T> type) {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return gson.fromJson(Files.readString(file), type);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load " + file.getFileName() + ": " + e.getMessage());
            quarantine();
            return null;
        }
    }

    /** 原子保存快照（IO 线程调用）。 */
    public void save(Object snapshot) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
            Files.writeString(tmp, gson.toJson(snapshot));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to write " + file.getFileName() + ": " + e.getMessage());
        }
    }

    private void quarantine() {
        try {
            Files.move(file, file.resolveSibling(file.getFileName().toString() + ".corrupt-" + System.currentTimeMillis()),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // 保留原文件
        }
    }
}
