package com.blankgrid.aione.service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

/**
 * settings.json 里密钥的落盘加密。
 *
 * <p>背景:原来的实现把 API Key 明文写进 ./data/settings.json,任何人拿到这个文件
 * (备份、压缩包、误提交、远程巡检)就等于拿到账单。数据库里的东西都在本机,配置文件却常被顺手拷走。
 *
 * <p>方案:AES-256-GCM,每次加密随机 12 字节 IV,密文格式 {@code enc:v1:base64(iv||ciphertext)}。
 * 主密钥优先取环境变量 {@code AIONE_SECRET};没有就在 data 目录生成 {@code .secret.key}
 * (32 字节随机数,Windows 之外的系统上尽量设成 600)。
 *
 * <p>兼容:读到的还是明文就照常使用,并在下一次保存时自动转成密文,不需要人工迁移。
 * 代价是主密钥和密文放在同一个目录时保护力有限 —— 它是为了挡住「文件被拷走」,不是挡住「整台机器被拿走」。
 */
final class SecretBox {

    private static final String PREFIX = "enc:v1:";
    private static final String AAD = "aione-settings";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    SecretBox(Path dataDir) {
        this.key = new SecretKeySpec(deriveKey(dataDir), "AES");
    }

    /** 已加密的串原样返回;明文字符串加密;空值不处理。 */
    String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return plain;
        if (isEncrypted(plain)) return plain;
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(AAD.getBytes(StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            // 加密失败不能把密钥弄丢:宁可这次明文写盘,也不要写出一份读不回来的配置
            throw new IllegalStateException("密钥加密失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解密。解不开时返回空串而不是原文 —— 宁可让用户重填一次 Key,
     * 也不能把一段乱码当成 Key 发给大模型(那会得到 401,还查不出原因)。
     */
    String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) return stored;
        if (!isEncrypted(stored)) return stored;
        try {
            byte[] raw = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (raw.length <= IV_LEN) return "";
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, raw, 0, IV_LEN));
            c.updateAAD(AAD.getBytes(StandardCharsets.UTF_8));
            byte[] pt = c.doFinal(raw, IV_LEN, raw.length - IV_LEN);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    boolean isEncrypted(String s) {
        return s != null && s.startsWith(PREFIX);
    }

    // ==================== 主密钥 ====================

    private static byte[] deriveKey(Path dataDir) {
        String env = System.getenv("AIONE_SECRET");
        if (env != null && !env.isBlank()) return sha256(("aione:" + env).getBytes(StandardCharsets.UTF_8));
        Path keyFile = dataDir.resolve(".secret.key");
        try {
            if (Files.exists(keyFile)) {
                byte[] raw = Base64.getDecoder().decode(Files.readString(keyFile, StandardCharsets.UTF_8).trim());
                if (raw.length >= 32) return raw;
            }
            byte[] fresh = new byte[32];
            new SecureRandom().nextBytes(fresh);
            Files.createDirectories(dataDir);
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(fresh), StandardCharsets.UTF_8);
            tighten(keyFile);
            return fresh;
        } catch (Exception e) {
            throw new IllegalStateException("无法初始化密钥文件 " + keyFile + ": " + e.getMessage(), e);
        }
    }

    private static void tighten(Path file) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows 没有 POSIX 权限,靠目录本身的位置保护
        }
    }

    private static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
