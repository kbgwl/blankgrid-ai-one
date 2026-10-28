package com.blankgrid.aione.controller;

import com.blankgrid.aione.service.ExportService;
import com.blankgrid.aione.service.LlmService;
import com.blankgrid.aione.service.SettingsService;
import com.blankgrid.aione.service.SettingsService.Provider;
import com.blankgrid.aione.service.SettingsService.Settings;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settingsService;
    private final LlmService llmService;
    private final ExportService exportService;

    public SettingsController(SettingsService settingsService, LlmService llmService,
                              ExportService exportService) {
        this.settingsService = settingsService;
        this.llmService = llmService;
        this.exportService = exportService;
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

    /** 成果落盘目录的实际情况:是否存在、能不能写。设置页保存后立刻能看到成不成。 */
    @GetMapping("/export")
    public Map<String, Object> exportStatus() {
        return exportService.status();
    }

    /**
     * 服务商的可用模型列表。body/参数二选一:?provider=服务商名,不传则用主用服务商。
     * 前端用它填模型下拉,避免手敲模型名写错导致整条生产线 400。
     */
    @GetMapping("/models")
    public Map<String, Object> models(@RequestParam(required = false) String provider) {
        Provider p = (provider == null || provider.isBlank())
                ? settingsService.primaryProvider() : settingsService.providerByName(provider);
        return llmService.listModels(p);
    }

    /**
     * 真发一次最小请求测连通。body 可选:{"provider":"服务商名"} 指定对象,
     * {"scope":"decision"} 测 CEO 决策槽位,{"model":"模型名"} 临时换模型名。
     * 不点名模型时按「这条链路真会用的模型」测,否则绿灯测的是 A、CEO 跑的是 B。
     * 密钥只在服务端读取,不经浏览器往返。
     */
    @PostMapping("/test")
    public Map<String, Object> test(@RequestBody(required = false) Map<String, String> body) {
        String name = body == null ? null : body.get("provider");
        String scope = body == null ? null : body.get("scope");
        String model = body == null ? null : body.get("model");
        boolean byName = name != null && !name.isBlank();
        Provider p = byName ? settingsService.providerByName(name)
                : "decision".equals(scope) ? settingsService.decisionProvider() : settingsService.primaryProvider();
        if (!byName && (model == null || model.isBlank())) {
            model = "decision".equals(scope) ? settingsService.decisionModel(null) : null;
        }
        return llmService.test(p, model);
    }
}
