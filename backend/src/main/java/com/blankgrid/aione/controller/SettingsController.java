package com.blankgrid.aione.controller;

import com.blankgrid.aione.service.SettingsService;
import com.blankgrid.aione.service.SettingsService.Settings;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public Settings get() {
        return settingsService.masked();
    }

    @PutMapping
    public Settings save(@RequestBody Settings incoming) {
        settingsService.save(incoming);
        return settingsService.masked();
    }
}
