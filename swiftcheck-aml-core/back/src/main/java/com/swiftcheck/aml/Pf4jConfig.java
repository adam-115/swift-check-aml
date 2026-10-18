package com.swiftcheck.aml;

import org.pf4j.PluginManager;
import org.pf4j.spring.SpringPluginManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
public class Pf4jConfig {

    @Bean
    public SpringPluginManager pluginManager(@org.springframework.beans.factory.annotation.Value("${pf4j.plugins.dir:D:/test-parser/jar parser plugins}") String pluginsDir) {
        // Le chemin est maintenant injectÃ© dynamiquement depuis application.properties
        // Si la propriÃ©tÃ© n'existe pas, on utilise le chemin par dÃ©faut.
        Path pluginsRoot = Paths.get(pluginsDir);
        // SpringPluginManager appelle automatiquement loadPlugins() et startPlugins() lors de son initialisation via @PostConstruct
        return new SpringPluginManager(pluginsRoot);
    }

    @Bean
    @DependsOn("pluginManager")
    public PluginLifecycle pluginLifecycle(SpringPluginManager pluginManager) {
        return new PluginLifecycle(pluginManager);
    }

    public static class PluginLifecycle {
        private final PluginManager pluginManager;

        public PluginLifecycle(PluginManager pluginManager) {
            this.pluginManager = pluginManager;
        }

        @PreDestroy
        public void cleanup() {
            pluginManager.stopPlugins();
        }
    }
}
