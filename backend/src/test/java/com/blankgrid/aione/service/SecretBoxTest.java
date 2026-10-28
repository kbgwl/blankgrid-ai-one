package com.blankgrid.aione.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 落盘加密:磁盘上的文件不许出现明文 Key,但内存里必须还是明文(否则调不通模型)。
 */
class SecretBoxTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("加密后再解密回到原文,密文里不含原文")
    void roundTrip() {
        SecretBox box = new SecretBox(dir);
        String key = "sk-259270db29f54d72be3a454e4a452ec1";
        String enc = box.encrypt(key);

        assertNotEquals(key, enc);
        assertFalse(enc.contains(key), "密文里绝不能出现明文片段");
        assertTrue(enc.startsWith("enc:v1:"));
        assertEquals(key, box.decrypt(enc));
    }

    @Test
    @DisplayName("同一把 Key 加密两次结果不同(随机 IV),但都能解开")
    void randomIv() {
        SecretBox box = new SecretBox(dir);
        String a = box.encrypt("secret");
        String b = box.encrypt("secret");
        assertNotEquals(a, b, "固定 IV 会让相同明文出现相同密文,等于泄露「这两个 Key 一样」");
        assertEquals("secret", box.decrypt(a));
        assertEquals("secret", box.decrypt(b));
    }

    @Test
    @DisplayName("换一台机器(换密钥文件)解不开时应返回空串,而不是把乱码当 Key 发出去")
    void wrongKeyYieldsEmpty() {
        SecretBox a = new SecretBox(dir);
        String enc = a.encrypt("sk-live");

        Path other = dir.resolve("other");
        try { Files.createDirectories(other); } catch (Exception ignored) {}
        SecretBox b = new SecretBox(other);
        assertEquals("", b.decrypt(enc));
    }

    @Test
    @DisplayName("明文老数据原样返回,便于自动迁移")
    void plaintextPassThrough() {
        SecretBox box = new SecretBox(dir);
        assertEquals("sk-old", box.decrypt("sk-old"));
        assertEquals("", box.encrypt(""));
    }

    @Test
    @DisplayName("settings.json 落盘后是密文,重新加载仍能用原 Key 调模型")
    void settingsFileIsEncrypted() throws Exception {
        SettingsService svc = new SettingsService(dir.toString());
        SettingsService.Settings in = new SettingsService.Settings();
        in.providers = new java.util.ArrayList<>(java.util.List.of(
                new SettingsService.Provider("甲", "https://api.example.com/v1", "sk-really-secret", "flash", true)));
        svc.save(in);

        String raw = Files.readString(dir.resolve("settings.json"));
        assertFalse(raw.contains("sk-really-secret"), "磁盘上不该有明文 Key");
        assertTrue(raw.contains("enc:v1:"));

        SettingsService reopened = new SettingsService(dir.toString());
        assertEquals("sk-really-secret", reopened.get().providers.get(0).apiKey,
                "重新加载要能解回真身,否则重启一次就调不通模型");
        assertArrayEquals(new String[]{"甲"},
                new String[]{reopened.get().providers.get(0).name});
    }
}
