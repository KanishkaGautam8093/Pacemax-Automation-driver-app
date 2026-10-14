package com.pacemax.automation.utils;

import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.options.UiAutomator2Options;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;

/**
 * Starts and stops the Appium driver.
 * All settings come from config/config.properties.
 */
public class DriverManager {

    private static AndroidDriver driver;

    public static AndroidDriver startDriver() {
        UiAutomator2Options options = new UiAutomator2Options();
        options.setPlatformName(ConfigReader.get("platform.name"));
        options.setAutomationName(ConfigReader.get("automation.name"));
        options.setUdid(ConfigReader.get("device.udid"));
        options.setNoReset(ConfigReader.getBoolean("no.reset"));
        options.setNewCommandTimeout(Duration.ofSeconds(ConfigReader.getInt("new.command.timeout")));

        // Some phones (e.g. Xiaomi) block the hidden API settings Appium tries to change.
        // true = ignore that error and continue starting the session.
        options.setIgnoreHiddenApiPolicyError(ConfigReader.getBoolean("ignore.hidden.api.policy.error"));

        // false = Appium does not auto-grant permissions to the app under test when installing it.
        // Note: this does NOT affect Appium's own helper app (io.appium.settings).
        options.setAutoGrantPermissions(ConfigReader.getBoolean("auto.grant.permissions"));

        // Only set the app details if they are filled in the config file
        String appPackage = ConfigReader.get("app.package");
        String appActivity = ConfigReader.get("app.activity");
        if (!appPackage.isEmpty()) {
            options.setAppPackage(appPackage);
        }
        if (!appActivity.isEmpty()) {
            options.setAppActivity(appActivity);
        }

        String serverUrl = ConfigReader.get("appium.server.url");
        System.out.println("Connecting to Appium server: " + serverUrl);

        try {
            driver = new AndroidDriver(new URL(serverUrl), options);
        } catch (MalformedURLException e) {
            throw new RuntimeException("Appium server URL is not valid: " + serverUrl, e);
        }

        System.out.println("Appium session started. Session id: " + driver.getSessionId());
        return driver;
    }

    public static AndroidDriver getDriver() {
        return driver;
    }

    public static void quitDriver() {
        if (driver != null) {
            driver.quit();
            driver = null;
            System.out.println("Appium session closed.");
        }
    }
}
