package com.pindiy.bot;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

public class Config {

    private static final Properties props = new Properties();

    static {
        File localFile = new File("config.properties");
        if (localFile.exists()) {
            try (InputStream in = new FileInputStream(localFile)) {
                props.load(in);
            } catch (Exception e) {
                System.err.println("[WARN] Could not load config.properties: " + e.getMessage());
            }
        } else {
            try (InputStream in = Config.class.getClassLoader().getResourceAsStream("config.properties")) {
                if (in != null) props.load(in);
            } catch (Exception e) {
                System.err.println("[WARN] Could not load config.properties from classpath: " + e.getMessage());
            }
        }
    }

    public static String get(String key) {
        return props.getProperty(key);
    }

    public static String getOrDefault(String key, String defaultValue) {
        return props.getProperty(key, defaultValue);
    }
}
