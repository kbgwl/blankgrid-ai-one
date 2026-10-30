package com.blankgrid.aione.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REA 接入的两处硬骨头:命令切分与工作区边界。
 *
 * <p>边界这一条最要紧:模型给的 target 是它自己填的字符串。
 * 如果不校验就交给 ProcessBuilder,一个 {@code ../../..} 就能让它去翻整台机器。
 */
class ReaToolTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("命令切分:保留带引号的空格路径")
    void splitCommand() {
        assertEquals(List.of("npx", "-y", "rea-agents@latest"),
                ReaTool.splitCommand("npx -y rea-agents@latest"));
        assertEquals(List.of("C:\\Program Files\\rea\\rea.exe", "doctor"),
                ReaTool.splitCommand("\"C:\\Program Files\\rea\\rea.exe\" doctor"));
        assertEquals(List.of("rea"), ReaTool.splitCommand("  rea  "), "多余空格不该产生空参数");
        assertTrue(ReaTool.splitCommand("").isEmpty());
        assertTrue(ReaTool.splitCommand(null).isEmpty());
    }

    @Test
    @DisplayName("没配工作区就一律拒绝:默认全盘可读不是一个可以接受的默认值")
    void refusesWithoutWorkspace() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ReaTool.resolveInsideWorkspace(tempDir.toString(), ""));
        assertTrue(e.getMessage().contains("工作区"));
    }

    @Test
    @DisplayName("工作区内的目标放行,绝对路径与相对路径都行")
    void allowsInsideWorkspace() throws Exception {
        Path app = Files.createDirectories(tempDir.resolve("apps/demo"));
        Path byAbs = ReaTool.resolveInsideWorkspace(app.toString(), tempDir.toString());
        assertTrue(byAbs.startsWith(tempDir.toRealPath()));

        Path byRel = ReaTool.resolveInsideWorkspace("apps/demo", tempDir.toString());
        assertEquals(byAbs, byRel, "相对路径按工作区展开,结果应与绝对路径一致");
    }

    @Test
    @DisplayName("越界目标被拒:../ 绕不出去")
    void refusesOutsideWorkspace() throws Exception {
        Path inside = Files.createDirectories(tempDir.resolve("inner"));
        Path outside = Files.createDirectories(tempDir.resolve("outer"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ReaTool.resolveInsideWorkspace(outside.toString(), inside.toString()));
        assertTrue(e.getMessage().contains("不在 REA 工作区"));

        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> ReaTool.resolveInsideWorkspace("../outer", inside.toString()));
        assertTrue(e2.getMessage().contains("不在 REA 工作区"), "相对路径里的 .. 也要拦住");
    }

    @Test
    @DisplayName("工作区不存在时直接报错,不用等外部进程起来才发现")
    void refusesMissingWorkspace() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ReaTool.resolveInsideWorkspace("x", tempDir.resolve("nope").toString()));
        assertTrue(e.getMessage().contains("不存在"));
    }
}
