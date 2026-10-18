package com.swiftcheck.aml;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.swiftcheck.aml.service.PluginManagementService;
import com.swiftcheck.aml.service.PluginManagementService.PluginInfo;

import tech.followthemoney.model.Model;

/**
 * Modèle FtM et gestion des plugins PF4J.
 * <pre>
 * GET  /api/ftm/schemas                       schémas FollowTheMoney disponibles
 * GET  /api/ftm/plugins                       plugins chargés (id, version, état, fichier)
 * POST /api/ftm/plugins/load?path=...         charge un JAR (remplace la version chargée de même id)
 * POST /api/ftm/plugins/{pluginId}/reload     recharge un plugin depuis son JAR
 * POST /api/ftm/plugins/{pluginId}/unload     décharge un plugin
 * </pre>
 * Pour exécuter un parser : {@code POST /api/parsers/{id}/run}.
 */
@RestController
@RequestMapping("/api/ftm")
public class FtmController {

    private final PluginManagementService pluginService;

    public FtmController(PluginManagementService pluginService) {
        this.pluginService = pluginService;
    }

    @GetMapping("/schemas")
    public List<String> getSchemas() throws IOException {
        return new ArrayList<>(Model.loadDefault().getSchemata().keySet());
    }

    @GetMapping("/plugins")
    public List<PluginInfo> listPlugins() {
        return pluginService.listPlugins();
    }

    @PostMapping("/plugins/load")
    public Map<String, String> load(@RequestParam String path) {
        return Map.of("pluginId", pluginService.load(path));
    }

    @PostMapping("/plugins/{pluginId}/reload")
    public Map<String, String> reload(@PathVariable String pluginId) {
        return Map.of("pluginId", pluginService.reload(pluginId));
    }

    @PostMapping("/plugins/{pluginId}/unload")
    public Map<String, String> unload(@PathVariable String pluginId) {
        pluginService.unload(pluginId);
        return Map.of("pluginId", pluginId);
    }
}
