package com.swiftcheck.aml.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * Configuration pour le routage Single Page Application (SPA Angular).
 * Redirige automatiquement toutes les routes de navigation frontend (comme /parsers, /providers, etc.)
 * vers index.html pour que le routeur Angular puisse afficher la vue demandée,
 * tout en préservant l'accès direct aux ressources statiques (.js, .css, images)
 * et aux API backend (/api/**).
 */
@Configuration
public class SpaWebMvcConfigurer implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requestedResource = location.createRelative(resourcePath);

                        // Si la ressource physique existe (ex: main.js, styles.css, images), on la sert directement
                        if (requestedResource.exists() && requestedResource.isReadable()) {
                            return requestedResource;
                        }

                        // Ne pas intercepter les requêtes destinées aux APIs backend
                        if (resourcePath.startsWith("api/") || resourcePath.startsWith("api")) {
                            return null;
                        }

                        // Pour toute autre route frontend (/parsers, /monitoring, etc.), renvoyer index.html
                        return new ClassPathResource("/static/index.html");
                    }
                });
    }
}
