package com.pacemax.automation;

import com.pacemax.automation.utils.DriverManager;
import io.appium.java_client.android.AndroidDriver;

/**
 * Simple connectivity check.
 * Opens an Appium session, prints what is currently on the device screen, then closes the session.
 * It does NOT click or change anything in the app.
 *
 * Run with:  mvn compile exec:java -Dexec.mainClass=com.pacemax.automation.App
 */
public class App {

    public static void main(String[] args) {
        try {
            AndroidDriver driver = DriverManager.startDriver();

            System.out.println("Current package : " + driver.getCurrentPackage());
            System.out.println("Current activity: " + driver.currentActivity());
            System.out.println("Connectivity check PASSED.");
        } catch (Exception e) {
            System.out.println("Connectivity check FAILED: " + e.getMessage());
        } finally {
            DriverManager.quitDriver();
        }
    }
}
