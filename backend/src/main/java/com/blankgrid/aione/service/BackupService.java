package com.blankgrid.aione.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** 数据备份:用 H2 BACKUP TO 热备份为 zip,保留最近 7 份 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final int KEEP = 7;

    private final Path backupDir;
    private final String dataDir;
    private final JdbcTemplate jdbc;

    public BackupService(@Value("${aione.data-dir:./data}") String dataDir, JdbcTemplate jdbc) {
        this.dataDir = dataDir;
        this.backupDir = Path.of(dataDir, "backup");
        this.jdbc = jdbc;
    }

    @PostConstruct
    @Scheduled(initialDelay = 600000, fixedDelay = 21600000) // 启动即备份,之后每 6 小时
    public void backup() {
        doBackup();
    }

    public String doBackup() {
        try {
            Files.createDirectories(backupDir);
            String name = "aione-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".zip";
            // H2 BACKUP TO:热备份,运行中也可用;路径需要正斜杠
            String h2Path = backupDir.resolve(name).toAbsolutePath().toString().replace('\\', '/');
            jdbc.execute("BACKUP TO '" + h2Path + "'");
            prune();
            log.info("数据备份完成: {}", name);
            return name;
        } catch (Exception e) {
            log.warn("备份失败: {}", e.getMessage());
            return null;
        }
    }

    private void prune() {
        try (Stream<Path> files = Files.list(backupDir)) {
            List<Path> sorted = files
                    .filter(p -> p.getFileName().toString().startsWith("aione-") && p.getFileName().toString().endsWith(".zip"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
            for (int i = KEEP; i < sorted.size(); i++) {
                Files.deleteIfExists(sorted.get(i));
            }
        } catch (IOException e) {
            log.warn("清理旧备份失败: {}", e.getMessage());
        }
    }

    /** 现有备份列表,新的在前。让「备份」不只是一个看不见的动作。 */
    public List<Path> list() {
        if (!Files.isDirectory(backupDir)) return List.of();
        try (Stream<Path> files = Files.list(backupDir)) {
            return files
                    .filter(p -> p.getFileName().toString().startsWith("aione-") && p.getFileName().toString().endsWith(".zip"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * 把某份备份取出来,准备在下次启动时替换数据库文件。
     *
     * <p>H2 在运行中锁着数据库文件,热替换会直接损坏数据,所以这里只做「暂存 + 打标记」,
     * 真正的替换发生在下次启动、JPA 打开连接之前(见 {@link RestoreApplier})。
     * 宁可多一步重启,也不要在运行中动一个正在被写的库。
     */
    public String stageRestore(String name) {
        Path root = backupDir.toAbsolutePath().normalize();
        Path zip = root.resolve(name).normalize();
        // 只允许取备份目录里的文件,避免 ../ 读到别处
        if (!zip.startsWith(root)) throw new IllegalArgumentException("备份文件名不合法");
        if (!Files.isRegularFile(zip)) throw new IllegalArgumentException("找不到这份备份:" + name);
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip.toFile())) {
            java.util.zip.ZipEntry dbEntry = zf.stream()
                    .filter(e -> e.getName().endsWith(".mv.db"))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("这份备份里没有 H2 数据文件"));
            Path staging = Path.of(dataDir, "restore-pending");
            Files.createDirectories(staging);
            try (var in = zf.getInputStream(dbEntry)) {
                Files.copy(in, staging.resolve("aione.mv.db"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(staging.resolve("SOURCE"), name + "\n" + LocalDateTime.now());
            return name;
        } catch (IOException e) {
            throw new IllegalStateException("解出备份失败:" + e.getMessage(), e);
        }
    }
}
