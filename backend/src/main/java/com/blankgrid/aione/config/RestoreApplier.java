package com.blankgrid.aione.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 恢复备份:在容器初始化最开始、JPA 打开数据库连接之前把库文件换掉。
 *
 * <p>为什么不用接口里直接做:H2 运行中锁着 .mv.db,热替换会把库写坏。
 * 所以恢复接口只负责把备份解到 {@code data/restore-pending/},真正换文件放到下次启动的最早时机。
 *
 * <p>用 {@link BeanFactoryPostProcessor} 而不是普通 Bean,是因为它的执行点早于任何业务 Bean
 * 和 DataSource 初始化;换成 @PostConstruct 就已经太晚了,连接池可能已经把旧库打开了。
 */
@Component
public class RestoreApplier implements BeanFactoryPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(RestoreApplier.class);

    /** 与 application.yml 的 aione.data-dir 默认值保持一致;可用系统属性覆盖 */
    private static Path dataDir() {
        return Path.of(System.getProperty("aione.data-dir", "./data"));
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        applyIfPending();
    }

    /** 有暂存备份就换掉旧库,并留一份换下来的文件,出问题还能退回去。 */
    public static void applyIfPending() {
        Path dir = dataDir();
        Path pending = dir.resolve("restore-pending");
        Path staged = pending.resolve("aione.mv.db");
        if (!Files.isRegularFile(staged)) return;
        try {
            Path db = dir.resolve("aione.mv.db");
            if (Files.isRegularFile(db)) {
                String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
                Path keep = dir.resolve("aione.mv.db.before-restore-" + stamp);
                Files.move(db, keep, StandardCopyOption.REPLACE_EXISTING);
                log.info("已把当前数据库另存为 {}", keep.getFileName());
            }
            Files.move(staged, db, StandardCopyOption.REPLACE_EXISTING);
            String source = "";
            Path src = pending.resolve("SOURCE");
            if (Files.isRegularFile(src)) source = Files.readString(src).split("\n")[0].trim();
            Files.deleteIfExists(src);
            Files.deleteIfExists(pending);
            log.info("已从备份恢复数据库: {}", source);
        } catch (IOException e) {
            log.error("恢复备份失败,仍在使用原数据库: {}", e.getMessage());
        }
    }
}
