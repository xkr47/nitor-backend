package io.nitor.api.backend;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Launcher;
import io.vertx.core.json.JsonObject;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.Scanner;

import static java.lang.System.getProperty;
import static java.lang.System.getenv;
import static java.util.Locale.US;

/**
 * A launcher which enables overriding properties in the deployment config
 * by using system properties and environment variables.
 * <p>
 * The launcher also looks for a default config in the classpath at /config.json.
 * If you define sensible defaults in this file you get a nice setup where you aren't required
 * to send in any conf, but can if you want to. And you are able to override properties.
 * <p>
 * https://gist.github.com/atorstling/d2669ba477924adac88d3caff6a36e8d
 **/
public class PropertiesLauncher extends Launcher {

    public static void main(String[] args) {
        new PropertiesLauncher().dispatch(args);
    }

    @Override
    public void beforeDeployingVerticle(DeploymentOptions deploymentOptions) {
        JsonObject conf = readDefaultsConf();
        JsonObject inputConf = deploymentOptions.getConfig();
        if (inputConf != null) {
            conf.mergeIn(inputConf);
        }

        override(conf, "");
        deploymentOptions.setConfig(conf);
    }

    private void override(JsonObject conf, String root) {
        for (Map.Entry<String, Object> e : conf) {
            String key = root + e.getKey();
            Object value = e.getValue();
            if (value instanceof JsonObject) {
                override((JsonObject) value, key + '.');
                return;
            }
            Prop prop = getProp(key);
            if (prop != null) {
                conf.put(e.getKey(), convertType(value.getClass(), prop.value));
            }
        }
    }

    private JsonObject readDefaultsConf() {
        InputStream defaultsConf = getClass().getResourceAsStream("/config.json");
        return defaultsConf == null ? null : new JsonObject(toStr(defaultsConf));
    }

    private String toStr(InputStream is) {
        // http://stackoverflow.com/a/5445161/83741
        try (Scanner s = new Scanner(is).useDelimiter("\\A")) {
            return s.hasNext() ? s.next() : "";
        }
    }

    private Object convertType(Class<?> type, String value) {
        // http://stackoverflow.com/a/13569408/83741
        try {
            return type.getConstructor(String.class).newInstance(value);
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new IllegalStateException(String.format("Could not convert value %s into type %s, tried finding a " +
                    "String constructor", value, type), e);
        }
    }

    static class Prop {
        final String source;
        final String key;
        final String value;

        public Prop(String source, String key, String value) {
            this.source = source;
            this.key = key;
            this.value = value;
        }
    }

    static Prop getProp(final String name) {
        String sysPropValue = getProperty(name);
        if (sysPropValue != null) {
            return new Prop("system property", name, sysPropValue);
        }
        String envKey = name.toUpperCase(US).replaceAll("\\.", "_");
        String envValue = getenv(envKey);
        if (envValue != null) {
            return new Prop("environment variable", envKey, envValue);
        }
        return null;
    }
}
