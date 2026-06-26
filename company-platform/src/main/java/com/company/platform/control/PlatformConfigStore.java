/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public class PlatformConfigStore {

    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
    private final Map<ConfigFile, Resource> resources = new EnumMap<>(ConfigFile.class);

    public PlatformConfigStore(
            @Value("${company.platform.models.config}") Resource models,
            @Value("${company.platform.providers.config}") Resource providers,
            @Value("${company.platform.agents.config}") Resource agents,
            @Value("${company.platform.tools.config}") Resource tools,
            @Value("${company.platform.mcps.config}") Resource mcps,
            @Value("${company.platform.skills.config}") Resource skills) {
        resources.put(ConfigFile.MODELS, models);
        resources.put(ConfigFile.PROVIDERS, providers);
        resources.put(ConfigFile.AGENTS, agents);
        resources.put(ConfigFile.TOOLS, tools);
        resources.put(ConfigFile.MCPS, mcps);
        resources.put(ConfigFile.SKILLS, skills);
    }

    public <T> T read(ConfigFile file, Class<T> type) throws IOException {
        Resource resource = resource(file);
        ensureExists(file, resource);
        try (InputStream input = resource.getInputStream()) {
            return mapper.readValue(input, type);
        }
    }

    public void write(ConfigFile file, Object value) {
        Path path = writablePath(file);
        try {
            Files.createDirectories(path.getParent());
            mapper.writeValue(path.toFile(), value);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist config " + file + ": " + path, e);
        }
    }

    public Path path(ConfigFile file) {
        return writablePath(file);
    }

    private void ensureExists(ConfigFile file, Resource resource) throws IOException {
        if (!resource.isFile()) {
            return;
        }
        Path path = resource.getFile().toPath();
        if (Files.exists(path)) {
            return;
        }
        Files.createDirectories(path.getParent());
        Resource defaults = new ClassPathResource(file.defaultResource());
        if (defaults.exists()) {
            try (InputStream input = defaults.getInputStream()) {
                Files.copy(input, path);
            }
            return;
        }
        Files.writeString(path, file.emptyYaml());
    }

    private Resource resource(ConfigFile file) {
        Resource resource = resources.get(file);
        if (resource == null) {
            throw new IllegalStateException("No config resource registered for " + file);
        }
        return resource;
    }

    private Path writablePath(ConfigFile file) {
        Resource resource = resource(file);
        try {
            if (!resource.isFile()) {
                throw new IllegalStateException(
                        "Config " + file + " is not writable. Use a file: resource.");
            }
            return resource.getFile().toPath();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot resolve config path for " + file, e);
        }
    }

    public enum ConfigFile {
        MODELS("models.yml", "models: []\n"),
        PROVIDERS("providers.yml", "providers: []\n"),
        AGENTS("agents.yml", "agents: []\n"),
        TOOLS("tools.yml", "tools: []\n"),
        MCPS("mcps.yml", "mcps: []\n"),
        SKILLS("skills.yml", "skills: []\n");

        private final String defaultResource;
        private final String emptyYaml;

        ConfigFile(String defaultResource, String emptyYaml) {
            this.defaultResource = defaultResource;
            this.emptyYaml = emptyYaml;
        }

        String defaultResource() {
            return defaultResource;
        }

        String emptyYaml() {
            return emptyYaml;
        }
    }
}
