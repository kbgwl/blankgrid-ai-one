package com.blankgrid.aione.controller;

import com.blankgrid.aione.service.SettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/** 口令登录:设置里配置了口令后,所有 /api 需携带 token */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final SettingsService settings;

    public AuthController(SettingsService settings) {
        this.settings = settings;
    }

    public static String tokenOf(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((password + ":blankgrid").getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String pwd = settings.get().password;
        Map<String, Object> out = new LinkedHashMap<>();
        if (pwd == null || pwd.isBlank()) {
            out.put("token", "");
            out.put("message", "未启用口令");
            return ResponseEntity.ok(out);
        }
        String input = body.getOrDefault("password", "");
        if (pwd.equals(input)) {
            out.put("token", tokenOf(pwd));
            return ResponseEntity.ok(out);
        }
        out.put("message", "口令不对");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(out);
    }
}
