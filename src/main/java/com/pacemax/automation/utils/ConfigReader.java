package com.pacemax.automation.utils;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Reads values from config/config.properties.
 */
public class ConfigReader {

    private static final String CONFIG_FILE_PATH = "config/config.properties";

    private static Properties properties;

    // Loads the file once, the first time a value is requested
    private static void loadProperties() {
        if (properties != null) {
            return;
        }
        properties = new Properties();
        try {
            FileInputStream file = new FileInputStream(CONFIG_FILE_PATH);
            properties.load(file);
            file.close();
        } catch (IOException e) {
            throw new RuntimeException("Could not read config file: " + CONFIG_FILE_PATH, e);
        }
    }

    // Returns the value for a key, or an empty string if the key is missing
    public static String get(String key) {
        loadProperties();
        String value = properties.getProperty(key);
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    public static int getInt(String key) {
        return Integer.parseInt(get(key));
    }

    public static boolean getBoolean(String key) {
        return Boolean.parseBoolean(get(key));
    }
}
