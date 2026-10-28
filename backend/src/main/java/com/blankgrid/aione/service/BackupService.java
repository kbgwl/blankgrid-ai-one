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
}
