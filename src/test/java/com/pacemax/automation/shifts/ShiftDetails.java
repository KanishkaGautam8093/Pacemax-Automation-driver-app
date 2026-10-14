package com.pacemax.automation.shifts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pacemax.automation.utils.ConfigReader;
import com.pacemax.automation.utils.DriverManager;
import io.appium.java_client.AppiumBy;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.remote.AppiumWebElement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.openqa.selenium.By;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.Rectangle;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.opentest4j.TestAbortedException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Shifts - Shift Details automation.
 *
 * Locators   -> locators/shift_details.properties
 * Test data  -> test-data/shifts.json  (the "shiftDetails" section)
 * Settings   -> config/config.properties
 *
 * The enabled tests only read the screen. Tests that SELECT dates in the calendar
 * are @Disabled until they are approved (they only change what the list shows).
 *
 * Run all enabled tests:  mvn test -Dtest=ShiftDetails
 * Run one test:           mvn test -Dtest=ShiftDetails#firstShiftDetailsTest
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ShiftDetails {

    private static final String LOCATOR_FILE = "locators/shift_details.properties";
    private static final String TEST_DATA_FILE = "test-data/shifts.json";
    private static final String SCREENSHOT_FOLDER = "target/screenshots";

    // Safety limits so scrolling / month navigation can never loop forever
    private static final int MAX_SCROLLS = 20;
    private static final int MAX_MONTH_CLICKS = 36;

    // Date formats seen in the app
    private static final DateTimeFormatter HEADING_FORMAT = DateTimeFormatter.ofPattern("EEE, dd MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private static AndroidDriver driver;
    private static WebDriverWait wait;
    private static Properties locators;
    private static JsonObject shiftData;

    // ============================================================
    // Setup and cleanup (runs once for the whole class)
    // ============================================================

    @BeforeAll
    static void setUp() {
        locators = loadLocators();
        shiftData = loadTestData().getAsJsonObject("shiftDetails");
        driver = DriverManager.startDriver();
        int waitSeconds = ConfigReader.getInt("explicit.wait.seconds");
        wait = new WebDriverWait(driver, Duration.ofSeconds(waitSeconds));
    }

    @AfterAll
    static void tearDown() {
        DriverManager.quitDriver();
    }

    // ============================================================
    // Read-only tests
    // ============================================================

    @Test
    @Order(1)
    void openShiftsPageTest() {
        try {
            goToShiftDetails();
            assertTrue(isPresent("shifts.details.tab"), "'Shift Details' tab is not shown.");
            assertTrue(isPresent("shifts.timeoff.tab"), "'Time Off' tab is not shown.");
            System.out.println("PASS: Shifts page 'My Schedule' opened with Shift Details and Time Off tabs.");
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("openShiftsPageTest");
            throw error;
        }
    }

    @Test
    @Order(2)
    void readShiftListTest() {
        try {
            goToShiftDetails();

            if (isPresent("shift.empty.message")) {
                String message = getFirstText("shift.empty.message");
                System.out.println("Shift list is empty. Message shown: '" + message + "'");
                compareIfConfigured("expectedEmptyStateMessage", message);
                checkCountIfConfigured(0);
                return;
            }

            List<String> headings = new ArrayList<>();
            List<String> rows = new ArrayList<>();
            readWholeList(headings, rows);

            System.out.println("Dates shown: " + headings);
            System.out.println("Shifts shown (" + rows.size() + "):");
            for (int i = 0; i < rows.size(); i++) {
                System.out.println("  " + (i + 1) + ". " + rows.get(i));
            }
            assertFalse(rows.isEmpty(), "Neither shifts nor the empty-state message are shown.");
            checkCountIfConfigured(rows.size());
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("readShiftListTest");
            throw error;
        }
    }

    @Test
    @Order(3)
    void firstShiftDetailsTest() {
        try {
            goToShiftDetails();
            if (isPresent("shift.empty.message")) {
                throw new TestAbortedException("SKIPPED: no shift is shown, so there is no first shift to open.");
            }

            WebElement firstRow = getFirstVisibleShiftRow();
            System.out.println("Opening the first shift: " + firstRow.getAttribute("content-desc"));
            firstRow.click();
            waitForElement("detail.start.label");

            // Values exactly as the app shows them, read once the screen has settled
            String[] values = readShiftValuesWhenSettled();
            String startTime = values[0];
            String finishTime = values[1];
            String totalTime = values[2];
            String stationAddress = values[3];

            System.out.println();
            System.out.println("Shift Details");
            System.out.println("-------------");
            System.out.println("Start Time: " + startTime);
            System.out.println("Finish Time: " + finishTime);
            System.out.println("Total Time: " + totalTime);
            System.out.println("Station Address: " + stationAddress);
            System.out.println();

            assertFalse(startTime.isEmpty(), "Start Time is empty.");
            assertFalse(finishTime.isEmpty(), "Finish Time is empty.");
            assertFalse(totalTime.isEmpty(), "Total Time is empty.");
            assertFalse(stationAddress.isEmpty(), "Station Address is empty.");

            // The APP itself showed an unusable value (e.g. "Invalid date", "undefined hrs") - report it as is
            for (int i = 0; i < values.length; i++) {
                if (looksLikeAppPlaceholder(values[i])) {
                    fail("APPLICATION ISSUE: the shift details screen shows '" + values[i] + "' for "
                            + SHIFT_FIELD_NAMES[i] + " (value reported exactly as displayed by the app).");
                }
            }

            // Back to the list
            click("detail.back.button");
            waitForElement("shifts.page.title");
            assertTrue(isPresent("shift.rows"), "After Back, the shift list is not shown.");
            System.out.println("PASS: shift details read and Back returned to the shift list.");
        } catch (TestAbortedException skipped) {
            throw skipped;
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("firstShiftDetailsTest");
            throw error;
        } finally {
            leaveDetailPageIfOpen();
        }
    }

    @Test
    @Order(4)
    void calendarNavigationTest() {
        LocalDate startDate = getRequiredDate("selectedStartDate");
        try {
            goToShiftDetails();
            openCalendar();
            goToMonth(YearMonth.from(startDate));
            assertTrue(driver.findElements(getCalendarDayLocator(startDate)).size() == 1,
                    "Day " + startDate + " is not shown in the calendar.");
            System.out.println("PASS: calendar moved to " + YearMonth.from(startDate) + " and shows " + startDate + " (not selected).");
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("calendarNavigationTest");
            throw error;
        } finally {
            closeCalendarWithoutApplying();
        }
    }

    // ============================================================
    // Tests that SELECT dates - @Disabled until approved
    // ============================================================

    // Selects the configured range, taps OK, and checks every shown date is inside the range
    @Test
    @Order(5)
    @Disabled("Needs approval: selects dates in the Shift Details calendar (changes which shifts are shown).")
    void calendarRangeFilterTest() {
        LocalDate startDate = getRequiredDate("selectedStartDate");
        LocalDate endDate = getRequiredDate("selectedEndDate");
        if (!endDate.isAfter(startDate)) {
            fail("selectedEndDate must be after selectedStartDate (the app needs at least 2 days).");
        }
        try {
            goToShiftDetails();
            openCalendar();
            selectDay(startDate);
            selectDay(endDate);
            click("calendar.ok.button");
            waitForInvisible("calendar.box");

            if (isPresent("shift.empty.message")) {
                String message = getFirstText("shift.empty.message");
                System.out.println("No shifts between " + startDate + " and " + endDate + ". Message: '" + message + "'");
                compareIfConfigured("expectedEmptyStateMessage", message);
                return;
            }

            List<String> headings = new ArrayList<>();
            List<String> rows = new ArrayList<>();
            readWholeList(headings, rows);
            System.out.println("Shifts between " + startDate + " and " + endDate + ": " + rows.size());
            for (String heading : headings) {
                LocalDate shown = parseHeadingDate(heading);
                assertTrue(!shown.isBefore(startDate) && !shown.isAfter(endDate),
                        "Date '" + heading + "' is outside the selected range " + startDate + " - " + endDate);
            }
            System.out.println("PASS: all shown dates are inside the selected range.");
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("calendarRangeFilterTest");
            throw error;
        } finally {
            closeCalendarWithoutApplying();
        }
    }

    // Selects only ONE date and taps OK - the app should refuse it (minimum 2 days)
    @Test
    @Order(6)
    @Disabled("Needs approval: selects a date in the Shift Details calendar.")
    void singleDateValidationTest() {
        LocalDate startDate = getRequiredDate("selectedStartDate");
        try {
            goToShiftDetails();
            openCalendar();
            selectDay(startDate);
            click("calendar.ok.button");

            String message = waitForElement("calendar.range.error").getText().trim();
            System.out.println("Message shown: '" + message + "'");

            List<String> expectedMessages = getStringList("expectedValidationMessages");
            if (expectedMessages.isEmpty()) {
                System.out.println("expectedValidationMessages is not set - message reported, not compared.");
            } else {
                assertTrue(expectedMessages.contains(message),
                        "Message '" + message + "' is not one of the expected messages " + expectedMessages);
            }
            System.out.println("PASS: a single date is refused.");
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("singleDateValidationTest");
            throw error;
        } finally {
            closeCalendarWithoutApplying();
        }
    }

    // ============================================================
    // Navigation
    // ============================================================

    // Shifts tab -> Shift Details section (leaves a shift detail page first if one is open)
    public void goToShiftDetails() {
        leaveDetailPageIfOpen();
        closeCalendarWithoutApplying();
        click("shifts.tab");
        waitForElement("shifts.page.title");
        click("shifts.details.tab");
        waitForElement("shift.calendar.icon");
    }

    // ============================================================
    // Shift detail values
    // ============================================================

    // The 4 fields, in the order they are read: {name, label locator key, value locator key}
    private static final String[] SHIFT_FIELD_NAMES = {"Start Time", "Finish Time", "Total Time", "Station Address"};
    private static final String[][] SHIFT_FIELD_KEYS = {
            {"detail.start.label", "detail.start.time"},
            {"detail.finish.label", "detail.finish.time"},
            {"detail.total.label", "detail.total.time"},
            {"detail.address.label", "detail.station.address"}
    };

    // Reads the 4 values. If a value still shows a temporary placeholder (the screen can be drawn
    // a moment before the shift data arrives), it reads again every half second until the values
    // are real or the normal wait time runs out. The values are NEVER changed - only re-read.
    private String[] readShiftValuesWhenSettled() {
        long endTime = System.currentTimeMillis() + ConfigReader.getInt("explicit.wait.seconds") * 1000L;
        String[] values = readShiftValuesOnce();
        while (hasPlaceholder(values) && System.currentTimeMillis() < endTime) {
            pause(500);
            values = readShiftValuesOnce();
        }
        return values;
    }

    private String[] readShiftValuesOnce() {
        String[] values = new String[SHIFT_FIELD_KEYS.length];
        for (int i = 0; i < SHIFT_FIELD_KEYS.length; i++) {
            // Time values are on the SAME ROW, to the RIGHT of their label; the address is BELOW its label
            boolean valueOnSameRow = i < 3;
            values[i] = readValueOfLabel(SHIFT_FIELD_NAMES[i], SHIFT_FIELD_KEYS[i][0], SHIFT_FIELD_KEYS[i][1], valueOnSameRow);
        }
        return values;
    }

    // Reads the value that belongs to a label and checks its position, so a value from another
    // field can never be returned. Elements are found again on every read (screen may re-render).
    private String readValueOfLabel(String fieldName, String labelKey, String valueKey, boolean valueOnSameRow) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                Rectangle label = waitForElement(labelKey).getRect();
                WebElement valueElement = waitForElement(valueKey);
                Rectangle value = valueElement.getRect();

                int labelCenterY = label.getY() + label.getHeight() / 2;
                int valueCenterY = value.getY() + value.getHeight() / 2;
                if (valueOnSameRow) {
                    boolean sameRow = Math.abs(valueCenterY - labelCenterY) <= label.getHeight();
                    boolean onTheRight = value.getX() > label.getX() + label.getWidth();
                    assertTrue(sameRow && onTheRight, fieldName + ": the value found is not on the right of its label (wrong field).");
                } else {
                    assertTrue(value.getY() >= label.getY() + label.getHeight() - 5,
                            fieldName + ": the value found is not below its label (wrong field).");
                }
                return valueElement.getText().trim();
            } catch (StaleElementReferenceException e) {
                // The screen re-rendered while reading - read it again
            }
        }
        fail(fieldName + ": the value could not be read (the screen kept changing).");
        return "";
    }

    // Text the app shows when its data is missing or not loaded yet (JavaScript placeholders)
    private boolean looksLikeAppPlaceholder(String value) {
        return value.contains("Invalid date") || value.contains("undefined") || value.contains("NaN");
    }

    private boolean hasPlaceholder(String[] values) {
        for (String value : values) {
            if (looksLikeAppPlaceholder(value)) {
                return true;
            }
        }
        return false;
    }

    // Short pause used only between two read-only checks of the screen
    private void pause(int milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void leaveDetailPageIfOpen() {
        if (isPresent("detail.start.label")) {
            click("detail.back.button");
            waitForElement("shifts.page.title");
        }
    }

    // ============================================================
    // Shift list
    // ============================================================

    // The top-most visible shift row (after scrolling the list back to the top)
    private WebElement getFirstVisibleShiftRow() {
        scrollListToTop();
        List<WebElement> rows = driver.findElements(getLocator("shift.rows"));
        if (rows.isEmpty()) {
            fail("No shift row is shown.");
        }
        WebElement first = rows.get(0);
        for (WebElement row : rows) {
            if (row.getRect().getY() < first.getRect().getY()) {
                first = row;
            }
        }
        return first;
    }

    // Reads all date headings and shift rows, scrolling through the whole list
    private void readWholeList(List<String> headings, List<String> rows) {
        scrollListToTop();
        for (int scrollCount = 0; scrollCount <= MAX_SCROLLS; scrollCount++) {
            for (WebElement heading : driver.findElements(getLocator("shift.date.headings"))) {
                String text = heading.getText().trim();
                if (!text.isEmpty() && !headings.contains(text)) {
                    headings.add(text);
                }
            }
            for (WebElement row : driver.findElements(getLocator("shift.rows"))) {
                String label = row.getAttribute("content-desc");
                if (label != null && !label.isEmpty() && !rows.contains(label)) {
                    rows.add(label);
                }
            }
            if (!scrollList("down")) {
                return;
            }
        }
        System.out.println("Stopped after " + MAX_SCROLLS + " scrolls (safety limit).");
    }

    private void scrollListToTop() {
        for (int scrollCount = 0; scrollCount < MAX_SCROLLS; scrollCount++) {
            if (!scrollList("up")) {
                return;
            }
        }
    }

    // Scrolls the list once. Returns false if nothing moved (end of the list).
    private boolean scrollList(String direction) {
        List<WebElement> containers = driver.findElements(getLocator("shift.list.container"));
        if (containers.isEmpty()) {
            return false;
        }
        String before = getListSignature();
        Map<String, Object> params = new HashMap<>();
        params.put("elementId", getElementId(containers.get(0)));
        params.put("direction", direction);
        params.put("percent", 0.6);
        driver.executeScript("mobile: scrollGesture", params);
        return !getListSignature().equals(before);
    }

    private String getListSignature() {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                StringBuilder signature = new StringBuilder();
                for (WebElement row : driver.findElements(getLocator("shift.rows"))) {
                    signature.append(row.getAttribute("content-desc")).append("@").append(row.getRect().getY()).append("|");
                }
                return signature.toString();
            } catch (StaleElementReferenceException e) {
                // The list re-rendered while reading - read it again
            }
        }
        return "";
    }

    private LocalDate parseHeadingDate(String heading) {
        try {
            return LocalDate.parse(heading, HEADING_FORMAT);
        } catch (DateTimeParseException e) {
            throw new RuntimeException("Could not read the date heading '" + heading + "' (expected e.g. 'Mon, 05 October 2026').", e);
        }
    }

    // ============================================================
    // Calendar
    // ============================================================

    private void openCalendar() {
        click("shift.calendar.icon");
        waitForElement("calendar.month.title");
    }

    // Closes the calendar by tapping the dimmed background (seen live: closes without applying)
    private void closeCalendarWithoutApplying() {
        List<WebElement> backgrounds = driver.findElements(getLocator("calendar.background"));
        List<WebElement> boxes = driver.findElements(getLocator("calendar.box"));
        if (backgrounds.isEmpty() || boxes.isEmpty()) {
            return;
        }
        // Tap the background halfway between the bottom of the white box and the bottom of the screen
        int boxBottom = boxes.get(0).getRect().getY() + boxes.get(0).getRect().getHeight();
        int screenHeight = driver.manage().window().getSize().getHeight();
        Map<String, Object> params = new HashMap<>();
        params.put("x", driver.manage().window().getSize().getWidth() / 2);
        params.put("y", boxBottom + (screenHeight - boxBottom) / 2);
        driver.executeScript("mobile: clickGesture", params);
        waitForInvisible("calendar.box");
    }

    // Clicks the month arrows until the calendar shows the wanted month
    private void goToMonth(YearMonth target) {
        for (int clickCount = 0; clickCount <= MAX_MONTH_CLICKS; clickCount++) {
            String header = waitForElement("calendar.month.title").getText().trim();
            YearMonth shown = YearMonth.parse(header, MONTH_FORMAT);
            if (shown.equals(target)) {
                return;
            }
            if (shown.isBefore(target)) {
                click("calendar.next.month");
            } else {
                click("calendar.previous.month");
            }
            wait.until(ExpectedConditions.not(ExpectedConditions.textToBe(getLocator("calendar.month.title"), header)));
        }
        fail("Could not reach " + target + " in the calendar within " + MAX_MONTH_CLICKS + " clicks.");
    }

    private void selectDay(LocalDate date) {
        goToMonth(YearMonth.from(date));
        List<WebElement> days = driver.findElements(getCalendarDayLocator(date));
        if (days.size() != 1) {
            fail("Day " + date + " is not shown in the calendar.");
        }
        days.get(0).click();
    }

    // The day button for this date. The date is validated, so it only has digits and dashes.
    private By getCalendarDayLocator(LocalDate date) {
        return buildLocator("calendar.day", readLocatorText("calendar.day").replace("{date}", date.toString()));
    }

    // ============================================================
    // Comparisons with test data
    // ============================================================

    private void checkCountIfConfigured(int actualCount) {
        JsonElement expected = shiftData.get("expectedShiftCount");
        if (expected == null || expected.isJsonNull()) {
            System.out.println("expectedShiftCount is not set - count reported, not compared.");
        } else {
            assertEquals(expected.getAsInt(), actualCount, "Number of shifts shown");
            System.out.println("PASS: shift count matches " + expected.getAsInt());
        }
    }

    private void compareIfConfigured(String key, String actual) {
        String expected = getString(shiftData, key);
        if (expected.isEmpty()) {
            System.out.println(key + " is not set - value reported, not compared.");
        } else {
            assertEquals(expected, actual, key);
            System.out.println("PASS: " + key + " matches.");
        }
    }

    // ============================================================
    // Simple helper methods
    // ============================================================

    private WebElement waitForElement(String locatorKey) {
        try {
            return wait.until(ExpectedConditions.visibilityOfElementLocated(getLocator(locatorKey)));
        } catch (TimeoutException e) {
            throw new RuntimeException("Element not visible: '" + locatorKey + "' (" + locators.getProperty(locatorKey) + ")", e);
        }
    }

    private void waitForInvisible(String locatorKey) {
        try {
            wait.until(ExpectedConditions.invisibilityOfElementLocated(getLocator(locatorKey)));
        } catch (TimeoutException e) {
            throw new RuntimeException("Element did not disappear: '" + locatorKey + "'", e);
        }
    }

    private void click(String locatorKey) {
        try {
            wait.until(ExpectedConditions.elementToBeClickable(getLocator(locatorKey))).click();
        } catch (TimeoutException e) {
            throw new RuntimeException("Could not click: '" + locatorKey + "' (" + locators.getProperty(locatorKey) + ")", e);
        }
    }

    private boolean isPresent(String locatorKey) {
        return driver.findElements(getLocator(locatorKey)).size() > 0;
    }

    private String getFirstText(String locatorKey) {
        List<WebElement> elements = driver.findElements(getLocator(locatorKey));
        if (elements.isEmpty()) {
            return "";
        }
        return elements.get(0).getText().trim();
    }

    // Appium gestures ("mobile: ...") need the element's internal id
    private String getElementId(WebElement element) {
        return ((AppiumWebElement) element).getId();
    }

    private By getLocator(String locatorKey) {
        return buildLocator(locatorKey, readLocatorText(locatorKey));
    }

    private String readLocatorText(String locatorKey) {
        String locator = locators.getProperty(locatorKey);
        if (locator == null) {
            throw new RuntimeException("Locator key not found in " + LOCATOR_FILE + ": " + locatorKey);
        }
        return locator.trim();
    }

    // Converts "type:value" into a Selenium/Appium By
    private By buildLocator(String locatorKey, String locatorText) {
        int separator = locatorText.indexOf(':');
        String type = locatorText.substring(0, separator).trim();
        String value = locatorText.substring(separator + 1).trim();

        if (type.equals("accessibility")) {
            return AppiumBy.accessibilityId(value);
        } else if (type.equals("id")) {
            return AppiumBy.id(value);
        } else if (type.equals("xpath")) {
            return AppiumBy.xpath(value);
        } else if (type.equals("class")) {
            return AppiumBy.className(value);
        } else {
            throw new RuntimeException("Unknown locator type '" + type + "' for key: " + locatorKey);
        }
    }

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

    // Returns a text value from a JSON object, or "" if it is missing or null
    private String getString(JsonObject object, String key) {
        if (object.has(key) && !object.get(key).isJsonNull()) {
            return object.get(key).getAsString().trim();
        }
        return "";
    }

    private List<String> getStringList(String key) {
        List<String> values = new ArrayList<>();
        if (shiftData.has(key) && shiftData.get(key).isJsonArray()) {
            JsonArray array = shiftData.getAsJsonArray(key);
            for (int i = 0; i < array.size(); i++) {
                String value = array.get(i).getAsString().trim();
                if (!value.isEmpty()) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    // A yyyy-MM-dd date from the shiftDetails section. Skips if empty, fails if the format is wrong.
    private LocalDate getRequiredDate(String key) {
        String value = getString(shiftData, key);
        if (value.isEmpty()) {
            throw new TestAbortedException("SKIPPED: '" + key + "' is empty in " + TEST_DATA_FILE + " (shiftDetails section).");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            fail(key + " '" + value + "' must use the format YYYY-MM-DD.");
            return null;
        }
    }
}
