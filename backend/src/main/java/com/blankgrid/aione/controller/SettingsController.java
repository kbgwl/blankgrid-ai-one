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
    private final com.blankgrid.aione.service.CompanyEngineService engine;
    private final com.blankgrid.aione.tool.EcomSearchTool ecomTool;
    private final com.blankgrid.aione.tool.ToolRegistry toolRegistry;

    public SettingsController(SettingsService settingsService, LlmService llmService,
                              ExportService exportService,
                              com.blankgrid.aione.service.CompanyEngineService engine,
                              com.blankgrid.aione.tool.EcomSearchTool ecomTool,
                              com.blankgrid.aione.tool.ToolRegistry toolRegistry) {
        this.settingsService = settingsService;
        this.llmService = llmService;
        this.exportService = exportService;
        this.engine = engine;
        this.ecomTool = ecomTool;
        this.toolRegistry = toolRegistry;
    }

    /** 已注册的工具清单及可用状态,设置页用来告诉用户"还差什么才能启用"。 */
    @GetMapping("/tools")
    public java.util.List<java.util.Map<String, Object>> tools() {
        return toolRegistry.describe();
    }

    /**
     * 电商联盟连通性自检。真去搜一条,比"保存成功"有用得多 ——
     * 密钥填错、应用没备案、adzone_id 与 AppKey 不匹配,这些都是保存时不报错、
     * 一到干活才 403 的坑。
     */
    @PostMapping("/ecom/test")
    public java.util.Map<String, Object> testEcom() {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        long t0 = System.currentTimeMillis();
        String detail = ecomTool.selfTest();
        out.put("ok", !detail.contains("失败") && !detail.contains("还没配置"));
        out.put("detail", detail);
        out.put("ms", System.currentTimeMillis() - t0);
        return out;
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
        Map<String, Object> r = llmService.test(p, model);
        // 充完值点一次测试连通,生产线立刻恢复,不用等冷却也不用重启
        if (Boolean.TRUE.equals(r.get("ok"))) engine.clearLlmBlock();
        return r;
    }
}
