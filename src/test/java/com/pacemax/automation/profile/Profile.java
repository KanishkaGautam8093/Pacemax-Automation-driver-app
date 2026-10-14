package com.pacemax.automation.profile;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pacemax.automation.utils.ConfigReader;
import com.pacemax.automation.utils.DriverManager;
import io.appium.java_client.AppiumBy;
import io.appium.java_client.android.AndroidDriver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.openqa.selenium.By;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Profile tab automation for the Pacemax Driver App.
 *
 * Locators   -> locators/profile.properties
 * Test data  -> test-data/profile.json
 * Settings   -> config/config.properties
 *
 * Run all Profile tests:  mvn test -Dtest=Profile
 * Run one test:           mvn test -Dtest=Profile#verifySharedDocuments
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Profile {

    private static final String LOCATOR_FILE = "locators/profile.properties";
    private static final String TEST_DATA_FILE = "test-data/profile.json";
    private static final String SCREENSHOT_FOLDER = "target/screenshots";

    private static AndroidDriver driver;
    private static WebDriverWait wait;
    private static Properties locators;
    private static JsonObject testData;

    // ============================================================
    // Setup and cleanup (runs once for the whole class)
    // ============================================================

    @BeforeAll
    static void setUp() {
        locators = loadLocators();
        testData = loadTestData();
        driver = DriverManager.startDriver();
        int waitSeconds = ConfigReader.getInt("explicit.wait.seconds");
        wait = new WebDriverWait(driver, Duration.ofSeconds(waitSeconds));
    }

    @AfterAll
    static void tearDown() {
        DriverManager.quitDriver();
    }

    // ============================================================
    // Tests
    // Each test opens the Profile tab first, so each one can run on its own.
    // ============================================================

    @Test
    @Order(1)
    void openProfileTabTest() {
        try {
            openProfileTab();
            System.out.println("PASS: Profile page is displayed.");
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("openProfileTabTest");
            throw error;
        }
    }

    @Test
    @Order(2)
    void verifyProfileDetails() {
        try {
            openProfileTab();

            String email = getText("profile.email.value");
            String phoneNumber = getText("profile.phone.value");
            String status = getText("profile.status.value");

            System.out.println();
            System.out.println("Profile Details");
            System.out.println("---------------");
            System.out.println("Email: " + email);
            System.out.println("Phone Number: " + phoneNumber);
            System.out.println("Status: " + status);
            System.out.println();

            // Values must not be blank on the screen
            assertTrue(!email.isEmpty(), "Email is empty on the Profile page");
            assertTrue(!phoneNumber.isEmpty(), "Phone number is empty on the Profile page");
            assertTrue(!status.isEmpty(), "Status is empty on the Profile page");

            // Compare with test data only if an expected value is filled in
            compareIfExpected("Email", getTestDataValue("expectedEmail"), email);
            compareIfExpected("Phone Number", getTestDataValue("expectedPhoneNumber"), phoneNumber);
            compareIfExpected("Status", getTestDataValue("expectedStatus"), status);
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("verifyProfileDetails");
            throw error;
        }
    }

    @Test
    @Order(3)
    void verifySharedDocuments() {
        try {
            openProfileTab();
            openSharedDocuments();

            List<String> documentNames = readSharedDocuments();
            List<String> expectedDocuments = getExpectedDocuments();

            if (documentNames.isEmpty()) {
                System.out.println("Shared Documents: No Documents (empty state is displayed)");
                // If documents were expected, an empty list is a failure
                if (!expectedDocuments.isEmpty()) {
                    fail("Expected documents " + expectedDocuments + " but the page shows 'No Documents'");
                }
            } else {
                System.out.println("Shared Documents (" + documentNames.size() + " visible text items):");
                for (int i = 0; i < documentNames.size(); i++) {
                    System.out.println("  " + (i + 1) + ". " + documentNames.get(i));
                }
                // Every expected document name must be shown on the screen
                for (String expectedName : expectedDocuments) {
                    assertTrue(documentNames.contains(expectedName),
                            "Expected document not found: " + expectedName);
                }
            }

            goBackToProfile();
            System.out.println("PASS: Returned to the Profile page.");
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("verifySharedDocuments");
            throw error;
        }
    }

    // ============================================================
    // Profile page actions
    // ============================================================

    // Taps the Profile tab in the bottom navigation and waits for the Profile page
    public void openProfileTab() {
        System.out.println("Opening the Profile tab...");
        click("profile.tab");
        waitForElement("profile.page.title");
    }

    // Taps Shared Documents and waits for the Shared Documents page
    public void openSharedDocuments() {
        System.out.println("Opening Shared Documents...");
        click("profile.shared.documents.button");
        waitForElement("shared.documents.page.title");
    }

    // Returns the visible document texts, or an empty list when "No Documents" is shown
    public List<String> readSharedDocuments() {
        List<String> documentNames = new ArrayList<>();

        // Wait until the list area shows something (documents or the empty message)
        wait.until(ExpectedConditions.presenceOfElementLocated(getLocator("shared.documents.list.texts")));

        // Empty state
        List<WebElement> emptyMessage = driver.findElements(getLocator("shared.documents.empty.message"));
        if (emptyMessage.size() > 0) {
            return documentNames;
        }

        // Documents are present: collect every visible text in the list area
        List<WebElement> textElements = driver.findElements(getLocator("shared.documents.list.texts"));
        for (WebElement element : textElements) {
            String text = element.getText().trim();
            if (!text.isEmpty()) {
                documentNames.add(text);
            }
        }
        return documentNames;
    }

    // Taps the back arrow on Shared Documents and waits for the Profile page
    public void goBackToProfile() {
        System.out.println("Going back to the Profile page...");
        click("shared.documents.back.button");
        waitForElement("profile.page.title");
    }

    // ============================================================
    // Simple helper methods
    // ============================================================

    // Waits until the element is visible and returns it
    private WebElement waitForElement(String locatorKey) {
        try {
            return wait.until(ExpectedConditions.visibilityOfElementLocated(getLocator(locatorKey)));
        } catch (Exception e) {
            throw new RuntimeException("Element not visible: '" + locatorKey + "' (" + locators.getProperty(locatorKey) + ")", e);
        }
    }

    // Waits until the element can be clicked, then clicks it
    private void click(String locatorKey) {
        try {
            wait.until(ExpectedConditions.elementToBeClickable(getLocator(locatorKey))).click();
        } catch (Exception e) {
            throw new RuntimeException("Could not click: '" + locatorKey + "' (" + locators.getProperty(locatorKey) + ")", e);
        }
    }

    // Returns the trimmed text of an element
    private String getText(String locatorKey) {
        return waitForElement(locatorKey).getText().trim();
    }

    // Compares only when the expected value is not empty
    private void compareIfExpected(String fieldName, String expected, String actual) {
        if (expected.isEmpty()) {
            System.out.println(fieldName + ": no expected value in " + TEST_DATA_FILE + ", comparison skipped");
        } else {
            assertEquals(expected, actual, fieldName + " does not match the expected value");
            System.out.println(fieldName + ": matches the expected value");
        }
    }

    // Converts "type:value" from the locator file into a Selenium/Appium By
    private By getLocator(String locatorKey) {
        String locator = locators.getProperty(locatorKey);
        if (locator == null) {
            throw new RuntimeException("Locator key not found in " + LOCATOR_FILE + ": " + locatorKey);
        }

        int separator = locator.indexOf(':');
        String type = locator.substring(0, separator).trim();
        String value = locator.substring(separator + 1).trim();

        if (type.equals("accessibility")) {
            return AppiumBy.accessibilityId(value);
        } else if (type.equals("id")) {
            return AppiumBy.id(value);
        } else if (type.equals("xpath")) {
            return AppiumBy.xpath(value);
        } else {
            throw new RuntimeException("Unknown locator type '" + type + "' for key: " + locatorKey);
        }
    }

    // Saves a screenshot to target/screenshots when a test fails
    private void takeScreenshot(String testName) {
        try {
            File screenshot = driver.getScreenshotAs(OutputType.FILE);
            File folder = new File(SCREENSHOT_FOLDER);
            folder.mkdirs();
            File destination = new File(folder, testName + "_" + System.currentTimeMillis() + ".png");
            Files.copy(screenshot.toPath(), destination.toPath());
            System.out.println("Screenshot saved: " + destination.getPath());
        } catch (Exception e) {
            System.out.println("Could not take screenshot: " + e.getMessage());
        }
    }

    // ============================================================
    // Reading locator and test data files
    // ============================================================

    private static Properties loadLocators() {
        Properties properties = new Properties();
        try {
            FileInputStream file = new FileInputStream(LOCATOR_FILE);
            properties.load(file);
            file.close();
        } catch (IOException e) {
            throw new RuntimeException("Could not read locator file: " + LOCATOR_FILE, e);
        }
        return properties;
    }

    private static JsonObject loadTestData() {
        try {
            FileReader reader = new FileReader(TEST_DATA_FILE);
            JsonObject data = JsonParser.parseReader(reader).getAsJsonObject();
            reader.close();
            return data;
        } catch (IOException e) {
            throw new RuntimeException("Could not read test data file: " + TEST_DATA_FILE, e);
        }
    }

    // Returns a text value from profile.json, or "" if it is missing
    private String getTestDataValue(String key) {
        if (testData.has(key)) {
            return testData.get(key).getAsString().trim();
        }
        return "";
    }

    // Returns the expectedSharedDocuments list from profile.json (empty if not filled in)
    private List<String> getExpectedDocuments() {
        List<String> expectedDocuments = new ArrayList<>();
        if (testData.has("expectedSharedDocuments")) {
            JsonArray array = testData.getAsJsonArray("expectedSharedDocuments");
            for (int i = 0; i < array.size(); i++) {
                String name = array.get(i).getAsString().trim();
                if (!name.isEmpty()) {
                    expectedDocuments.add(name);
                }
            }
        }
        return expectedDocuments;
    }
}
