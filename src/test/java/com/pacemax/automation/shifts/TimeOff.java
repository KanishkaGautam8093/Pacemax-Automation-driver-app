package com.pacemax.automation.shifts;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pacemax.automation.utils.ConfigReader;
import com.pacemax.automation.utils.DriverManager;
import io.appium.java_client.AppiumBy;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.remote.AppiumWebElement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.openqa.selenium.By;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.Rectangle;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.opentest4j.TestAbortedException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Shifts - Time Off automation.
 *
 * Locators   -> locators/time_off.properties
 * Test data  -> test-data/shifts.json  (the "timeOff" section)
 * Settings   -> config/config.properties
 *
 * SAFETY (the app is connected to PRODUCTION):
 *  - Tests 1-6 never tap Apply; a filled form is left with the back arrow, which discards it.
 *  - applyTimeOffRequestTest is the ONLY test that taps Apply (once, never retried). It runs only with
 *    -Dtimeoff.submit.approved=true and is reported as skipped otherwise.
 *
 * Run the safe tests:      mvn test -Dtest=TimeOff
 * Run one test:            mvn test -Dtest=TimeOff#readTimeOffRecordsTest
 * Submit (REAL request):   mvn test "-Dtest=TimeOff#applyTimeOffRequestTest" "-Dtimeoff.submit.approved=true"
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TimeOff {

    private static final String LOCATOR_FILE = "locators/time_off.properties";
    private static final String TEST_DATA_FILE = "test-data/shifts.json";
    private static final String SCREENSHOT_FOLDER = "target/screenshots";

    // Safety limits so scrolling / month navigation can never loop forever
    private static final int MAX_SCROLLS = 20;
    private static final int MAX_MONTH_CLICKS = 36;

    // Duration options seen live (2026-10-14). Multiple Days turns the Date into a range.
    private static final String FULL_DAY = "Full Day";
    private static final String MULTIPLE_DAYS = "Multiple Days";

    // Date formats seen in the app
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter REQUEST_DATE_FORMAT = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter RECORD_DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private static AndroidDriver driver;
    private static WebDriverWait wait;
    private static Properties locators;
    private static JsonObject timeOffData;

    // ============================================================
    // Setup and cleanup (runs once for the whole class)
    // ============================================================

    @BeforeAll
    static void setUp() {
        locators = loadLocators();
        timeOffData = loadTestData().getAsJsonObject("timeOff");
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
    void readTimeOffRecordsTest() {
        try {
            goToTimeOff();

            List<String[]> records = readAllRecords();
            String summary = getFirstText("timeoff.summary");
            System.out.println("Summary: '" + summary + "'");
            System.out.println("Time off records (" + records.size() + "):");
            for (String[] record : records) {
                printRecord(record);
                assertFalse(record[0].isEmpty(), "A record has no date.");
                assertFalse(record[1].isEmpty(), "A record has no duration.");
                assertFalse(record[2].isEmpty(), "A record has no status.");
                assertFalse(record[3].isEmpty(), "A record has no 'Applied on' value.");
            }

            if (records.isEmpty()) {
                // PENDING: the empty state of this list has not been seen live yet - reported only
                System.out.println("No time off records are shown.");
                String expectedEmpty = getString(timeOffData, "expectedEmptyStateMessage");
                if (!expectedEmpty.isEmpty()) {
                    assertTrue(getAllScreenTexts().contains(expectedEmpty), "Empty-state message '" + expectedEmpty + "' is not shown.");
                }
                return;
            }

            // The summary line states the number of records, e.g. "You have 1 time off applied"
            assertEquals(getFirstNumber(summary), records.size(), "Records shown vs. the number in the summary line");
            System.out.println("PASS: " + records.size() + " record(s) read and the count matches the summary.");
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("readTimeOffRecordsTest");
            throw error;
        }
    }

    @Test
    @Order(2)
    void timeOffCalendarOpensTest() {
        try {
            goToTimeOff();
            click("timeoff.calendar.icon");
            String month = waitForElement("calendar.month.title").getText().trim();
            System.out.println("PASS: Time Off calendar opened on '" + month + "' (nothing selected).");
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("timeOffCalendarOpensTest");
            throw error;
        } finally {
            closeCalendarWithoutApplying();
        }
    }

    @Test
    @Order(3)
    void requestFormFieldsTest() {
        try {
            openRequestForm();

            List<String> durations = getTexts("request.duration.labels");
            List<String> types = getOptionLabels("request.type.options");
            String dateShown = getDateFieldText();

            System.out.println("Duration options: " + durations);
            System.out.println("Date shown: '" + dateShown + "'");
            System.out.println("Request Type options: " + types);

            assertFalse(durations.isEmpty(), "No Duration options are shown.");
            assertEquals(durations.size(), driver.findElements(getLocator("request.duration.radios")).size(),
                    "Each Duration option should have one radio button.");
            assertFalse(dateShown.isEmpty(), "The Date field is empty.");
            parseRequestDate(dateShown); // fails with a clear message if the format changed
            assertFalse(types.isEmpty(), "No Request Type options are shown.");
            assertTrue(isPresent("request.reason.field"), "The Reason field is not shown.");
            assertFalse(waitForElement("request.apply.button").isEnabled(), "Apply should be disabled on an untouched form.");
            System.out.println("PASS: Request Time Off form shows all fields; Apply is disabled.");
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("requestFormFieldsTest");
            throw error;
        } finally {
            leaveRequestFormIfOpen();
        }
    }

    @Test
    @Order(4)
    void requestDatePickerTest() {
        String duration = getString(getRequestData(), "duration");
        LocalDate requestDate = getRequestDates()[0]; // start date (single date or start of a range)
        try {
            openRequestForm();
            // Duration BEFORE Date: the picker must be the one for the configured Duration
            selectDuration(duration);
            String dateBefore = getDateFieldText();

            openDatePicker();
            goToMonth(YearMonth.from(requestDate));
            assertTrue(driver.findElements(getCalendarDayLocator(requestDate)).size() == 1,
                    "Day " + requestDate + " is not shown in the date picker.");
            System.out.println("PASS: '" + duration + "' date picker shows " + requestDate + " (not selected).");

            closeDatePickerWithoutChoosing();
            assertEquals(dateBefore, getDateFieldText(), "The date changed although nothing was chosen.");
            System.out.println("PASS: closing the picker kept the date '" + dateBefore + "'.");
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("requestDatePickerTest");
            throw error;
        } finally {
            closeCalendarWithoutApplying();
            leaveRequestFormIfOpen();
        }
    }

    // ============================================================
    // Form tests that NEVER tap Apply (checked: no call to request.apply.button.click()).
    // Leaving the form with the back arrow discards everything (seen live: the list still showed
    // "You have 1 time off applied" after leaving a filled form).
    // ============================================================

    // Fills the form from shifts.json and checks Apply becomes enabled. Leaves WITHOUT Apply.
    @Test
    @Order(5)
    void fillRequestWithoutApplyTest() {
        checkRequestDataIsComplete();
        try {
            openRequestForm();
            // Apply must be disabled on the untouched form (seen live: enabled=false)
            assertFalse(waitForElement("request.apply.button").isEnabled(), "Apply is enabled on an untouched form.");
            fillRequestForm();
            verifyFormMatchesData();
            System.out.println("PASS: form filled and Apply is enabled. Apply was NOT tapped.");
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("fillRequestWithoutApplyTest");
            throw error;
        } finally {
            closeCalendarWithoutApplying();
            leaveRequestFormIfOpen();
        }
    }

    // Invalid dates in the Full Day picker. Taps days, reads the result, then CANCEL - never OK, never Apply.
    // Seen live 2026-10-14:
    //   - past dates (and today): the tap is ignored - not selected, no message
    //   - a date overlapping an existing time off: red message in the picker
    //     "Time Off requests are not allowed for this date. Please choose a different date."
    @Test
    @Order(6)
    void pastDateValidationTest() {
        JsonObject invalidDate = timeOffData.getAsJsonObject("invalidDate");
        LocalDate pastDate = getRequiredDate(invalidDate, "pastDate");
        if (!pastDate.isBefore(LocalDate.now())) {
            fail("pastDate " + pastDate + " is not before today (" + LocalDate.now() + ").");
        }
        String blockedValue = getString(invalidDate, "blockedDate"); // optional
        String expectedMessage = getString(invalidDate, "expectedValidationMessage");
        String duration = getString(getRequestData(), "duration");
        try {
            openRequestForm();
            // Duration BEFORE Date. PENDING: past/blocked-day behaviour was seen live in the Full Day picker only
            selectDuration(duration);
            String dateBefore = getDateFieldText();
            openDatePicker();

            // 1. Past date: must NOT become selected
            tapDayOnly(pastDate);
            String pastLabel = driver.findElements(getCalendarDayLocator(pastDate)).get(0).getAttribute("content-desc");
            System.out.println("Past date " + pastDate + " after tap: '" + pastLabel + "', messages: " + getCalendarMessages());
            assertFalse(pastLabel != null && pastLabel.contains("selected"), "The past date " + pastDate + " was selected.");
            System.out.println("PASS: the past date cannot be selected.");

            // 2. Blocked date (optional): the app must show its message
            if (!blockedValue.isEmpty()) {
                LocalDate blockedDate = parseDate("blockedDate", blockedValue);
                tapDayOnly(blockedDate);
                List<String> messages = getCalendarMessages();
                System.out.println("Blocked date " + blockedDate + " - message(s): " + messages);
                assertFalse(messages.isEmpty(), "No message was shown for the blocked date " + blockedDate + ".");
                if (expectedMessage.isEmpty()) {
                    System.out.println("expectedValidationMessage is not set - message reported, not compared.");
                } else {
                    assertTrue(messages.contains(expectedMessage),
                            "Expected message '" + expectedMessage + "' but the app shows " + messages);
                    System.out.println("PASS: the expected validation message is shown.");
                }
            } else {
                System.out.println("blockedDate is not set - the 'not allowed' message is not checked.");
            }

            // 3. Close without choosing: the Date field must be unchanged
            closeDatePickerWithoutChoosing();
            assertEquals(dateBefore, getDateFieldText(), "The Date field changed although nothing was chosen.");
            System.out.println("PASS: closing the picker kept the date '" + dateBefore + "'.");
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("pastDateValidationTest");
            throw error;
        } finally {
            closeCalendarWithoutApplying();
            leaveRequestFormIfOpen();
        }
    }

    // Moves to the day's month and taps the day (no checks - used to observe how the app reacts)
    private void tapDayOnly(LocalDate date) {
        goToMonth(YearMonth.from(date));
        List<WebElement> days = driver.findElements(getCalendarDayLocator(date));
        if (days.size() != 1) {
            fail("Day " + date + " is not shown in the date picker.");
        }
        days.get(0).click();
    }

    // ============================================================
    // SUBMISSION - creates a REAL time off request (the app is connected to PRODUCTION).
    // Runs ONLY when started with:  -Dtimeoff.submit.approved=true   (otherwise reported as skipped)
    // Apply is tapped EXACTLY ONCE and is never retried, whatever happens.
    // ============================================================

    // "Applied on" format seen live: "Fri, 09 October 2026"
    private static final DateTimeFormatter APPLIED_ON_FORMAT = DateTimeFormatter.ofPattern("EEE, dd MMMM yyyy", Locale.ENGLISH);

    @Test
    @Order(7)
    @EnabledIfSystemProperty(named = "timeoff.submit.approved", matches = "true",
            disabledReason = "Creates a REAL time off request on production - run only with -Dtimeoff.submit.approved=true")
    void applyTimeOffRequestTest() {
        checkRequestDataIsComplete();
        LocalDate[] dates = getRequestDates(); // dates[1] is the end date for Multiple Days, otherwise null
        String duration = getString(getRequestData(), "duration");
        try {
            // 1. Shifts -> Time Off (navigated once) and remember what already exists
            goToTimeOff();
            List<String[]> recordsBefore = readAllRecords();
            if (findRecordStartingOn(recordsBefore, dates[0]) != null) {
                fail("A request starting on " + dates[0].format(RECORD_DATE_FORMAT)
                        + " already exists - NOT submitting, to avoid a duplicate. Check the Time Off list.");
            }

            // 2. Request Time Off - tapped once; we are already on the list, so no extra navigation
            click("timeoff.request.button");
            waitForElement("request.title");
            assertFalse(waitForElement("request.apply.button").isEnabled(), "Apply is enabled on an untouched form.");

            // 3-6. Duration, Date, Request Type, Reason from shifts.json
            fillRequestForm();

            // 7. The form shows the configured values and Apply is enabled
            verifyFormMatchesData();

            // 8. Apply - exactly once
            System.out.println("Tapping Apply ONCE (submission approved with -Dtimeoff.submit.approved=true)...");
            click("request.apply.button");

            // 9. Actual result - read from the app, never by tapping Apply again
            String[] newRecord = verifySubmission(recordsBefore, dates[0]);

            // 10. The new request in the Time Off list
            verifyNewRecord(newRecord, duration, dates);
            System.out.println("PASS: time off request submitted and found in the list.");
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("applyTimeOffRequestTest");
            throw error;
        } finally {
            // Only closes what is still open - never taps Apply
            closeCalendarWithoutApplying();
            leaveRequestFormIfOpen();
        }
    }

    // After Apply: watches the screen until the app returns to the Time Off list, then reads the list.
    // SUCCESS = exactly one new record starting on the start date. Anything unclear -> UNKNOWN (test fails,
    // nothing is retried). Returns the new record on SUCCESS.
    private String[] verifySubmission(List<String[]> recordsBefore, LocalDate startDate) {
        // Every text seen while waiting (a success/error banner may only be shown briefly)
        Set<String> textsSeen = new LinkedHashSet<>();
        boolean backOnList;
        try {
            backOnList = wait.until(d -> {
                textsSeen.addAll(getAllScreenTexts());
                textsSeen.addAll(getToastTexts());
                return !isPresent("request.title") && isPresent("timeoff.request.button");
            });
        } catch (TimeoutException e) {
            backOnList = false;
        }
        System.out.println("Texts seen after Apply: " + textsSeen);
        takeScreenshot("applyTimeOffRequest_afterApply");

        String expectedMessage = getString(timeOffData, "expectedSubmissionMessage");
        if (expectedMessage.isEmpty()) {
            System.out.println("expectedSubmissionMessage is not set - messages reported, not compared.");
        } else {
            System.out.println("Expected message '" + expectedMessage + "' seen: " + textsSeen.contains(expectedMessage));
        }

        if (!backOnList) {
            // Still on the form: leave it with the back arrow (discards, never submits) and check the list
            System.out.println("The app did NOT return to the Time Off list by itself.");
            leaveRequestFormIfOpen();
        }

        List<String[]> recordsAfter = readAllRecords();
        String[] newRecord = findRecordStartingOn(recordsAfter, startDate);
        System.out.println("Records before: " + recordsBefore.size() + ", after: " + recordsAfter.size());

        String outcome;
        if (newRecord != null && recordsAfter.size() == recordsBefore.size() + 1) {
            outcome = "SUCCESS";
        } else if (newRecord == null && recordsAfter.size() == recordsBefore.size() && !backOnList) {
            outcome = "FAILED"; // the form stayed open and nothing new is listed
        } else {
            outcome = "UNKNOWN";
        }
        System.out.println("SUBMISSION RESULT: " + outcome);

        if (!outcome.equals("SUCCESS")) {
            fail("Submission result " + outcome + ". Texts seen: " + textsSeen
                    + ". Apply was tapped ONCE and NOT retried. Check the Time Off list in the app before any new run.");
        }
        if (!expectedMessage.isEmpty()) {
            assertTrue(textsSeen.contains(expectedMessage), "Message '" + expectedMessage + "' was not seen after Apply.");
        }
        assertTrue(backOnList, "The request was created, but the app did not return to the Time Off list by itself.");
        return newRecord;
    }

    // Checks Date, Duration, Applied On and Status of the new record
    private void verifyNewRecord(String[] record, String duration, LocalDate[] dates) {
        printRecord(record);

        // Date - seen live for a range: "09 Oct 2026 - 16 Oct 2026"
        if (duration.equals(MULTIPLE_DAYS)) {
            assertEquals(dates[0].format(RECORD_DATE_FORMAT) + " - " + dates[1].format(RECORD_DATE_FORMAT), record[0], "Date of the new request");
        } else {
            // PENDING: how a single-day record shows its date has not been seen - only the start is checked
            assertTrue(record[0].startsWith(dates[0].format(RECORD_DATE_FORMAT)), "Date of the new request: '" + record[0] + "'");
        }

        // Duration - seen live: 09-16 Oct shows "8 days" (both days counted)
        if (duration.equals(MULTIPLE_DAYS)) {
            long days = ChronoUnit.DAYS.between(dates[0], dates[1]) + 1;
            assertEquals(days, getFirstNumber(record[1]), "Number of days in Duration '" + record[1] + "'");
        } else {
            // PENDING: the Duration text of a Full Day (or Specify Hours) request has not been seen - reported only
            System.out.println("Full Day duration shown as '" + record[1] + "' (not compared).");
        }

        // Applied On - today, e.g. "Wed, 14 October 2026"
        assertEquals(LocalDate.now().format(APPLIED_ON_FORMAT), record[3], "'Applied on' of the new request");

        // Status - compared only when configured
        String expectedStatus = getString(timeOffData, "expectedRequestStatus");
        if (expectedStatus.isEmpty()) {
            System.out.println("expectedRequestStatus is not set - status '" + record[2] + "' reported, not compared.");
        } else {
            assertEquals(expectedStatus, record[2], "Status of the new request");
        }
    }

    // ============================================================
    // Navigation
    // ============================================================

    // Shifts tab -> Time Off section (closes a picker / leaves the request form first).
    // If the Time Off list is already showing, nothing is tapped (no repeated navigation).
    public void goToTimeOff() {
        closeCalendarWithoutApplying();
        leaveRequestFormIfOpen();
        if (isTimeOffListShowing()) {
            return;
        }
        click("shifts.tab");
        waitForElement("shifts.page.title");
        click("shifts.timeoff.tab");
        waitForElement("timeoff.request.button");
    }

    public void openRequestForm() {
        goToTimeOff();
        click("timeoff.request.button");
        waitForElement("request.title");
    }

    private boolean isTimeOffListShowing() {
        for (WebElement button : driver.findElements(getLocator("timeoff.request.button"))) {
            try {
                if (button.isDisplayed()) {
                    return true;
                }
            } catch (StaleElementReferenceException e) {
                // re-rendered - treat as not showing
            }
        }
        return false;
    }

    // Leaves the request form with its back arrow (seen live: no prompt, back to the list)
    private void leaveRequestFormIfOpen() {
        if (isPresent("request.title")) {
            click("request.back.button");
            waitForElement("timeoff.request.button");
        }
    }

    // ============================================================
    // Time Off records
    // ============================================================

    // Each record = {dates, duration, status, appliedOn}, read from the texts inside the row
    private List<String[]> readAllRecords() {
        List<String[]> records = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        scrollRecordsToTop();
        for (int scrollCount = 0; scrollCount <= MAX_SCROLLS; scrollCount++) {
            for (WebElement row : driver.findElements(getLocator("timeoff.records"))) {
                String label = row.getAttribute("content-desc");
                if (label == null || seen.contains(label)) {
                    continue;
                }
                seen.add(label);
                records.add(readRecord(row));
            }
            if (!scrollRecords("down")) {
                break;
            }
        }
        return records;
    }

    // The texts in a row are recognised by their content (seen live):
    // "09 Oct 2026 - 16 Oct 2026", "Duration: 8 days", "Pending", "Applied on: Fri, 09 October 2026"
    private String[] readRecord(WebElement row) {
        String dates = "";
        String duration = "";
        String status = "";
        String appliedOn = "";
        for (WebElement text : row.findElements(getLocator("timeoff.record.texts"))) {
            String value = text.getText().trim();
            if (value.startsWith("Duration:")) {
                duration = value.substring("Duration:".length()).trim();
            } else if (value.startsWith("Applied on:")) {
                appliedOn = value.substring("Applied on:".length()).trim();
            } else if (dates.isEmpty() && startsWithRecordDate(value)) {
                dates = value;
            } else if (!value.isEmpty()) {
                status = value;
            }
        }
        return new String[] {dates, duration, status, appliedOn};
    }

    private boolean startsWithRecordDate(String value) {
        if (value.length() < 11) {
            return false;
        }
        try {
            LocalDate.parse(value.substring(0, 11), RECORD_DATE_FORMAT);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private String[] findRecordStartingOn(List<String[]> records, LocalDate date) {
        String start = date.format(RECORD_DATE_FORMAT);
        for (String[] record : records) {
            if (record[0].startsWith(start)) {
                return record;
            }
        }
        return null;
    }

    private void printRecord(String[] record) {
        System.out.println();
        System.out.println("Time Off Record");
        System.out.println("---------------");
        System.out.println("Date: " + record[0]);
        System.out.println("Duration: " + record[1]);
        System.out.println("Status: " + record[2]);
        System.out.println("Applied On: " + record[3]);
    }

    private void scrollRecordsToTop() {
        for (int scrollCount = 0; scrollCount < MAX_SCROLLS; scrollCount++) {
            if (!scrollRecords("up")) {
                return;
            }
        }
    }

    // Scrolls the record list once. Returns false if nothing moved (end of the list).
    private boolean scrollRecords(String direction) {
        List<WebElement> containers = driver.findElements(getLocator("timeoff.list.container"));
        if (containers.isEmpty()) {
            return false;
        }
        String before = getRecordsSignature();
        Map<String, Object> params = new HashMap<>();
        params.put("elementId", getElementId(containers.get(0)));
        params.put("direction", direction);
        params.put("percent", 0.6);
        driver.executeScript("mobile: scrollGesture", params);
        return !getRecordsSignature().equals(before);
    }

    private String getRecordsSignature() {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                StringBuilder signature = new StringBuilder();
                for (WebElement row : driver.findElements(getLocator("timeoff.records"))) {
                    signature.append(row.getAttribute("content-desc")).append("@").append(row.getRect().getY()).append("|");
                }
                return signature.toString();
            } catch (StaleElementReferenceException e) {
                // The list re-rendered while reading - read it again
            }
        }
        return "";
    }

    // ============================================================
    // Request form
    // ============================================================

    // Checks the request data BEFORE opening the form. Skips (names the key) if a value is missing,
    // fails if a value does not fit what the app offers (seen live 2026-10-14).
    private void checkRequestDataIsComplete() {
        JsonObject request = getRequestData();
        String[] keys = {"duration", "requestDate", "requestType", "reason"};
        for (String key : keys) {
            if (getString(request, key).isEmpty()) {
                throw new TestAbortedException("SKIPPED: timeOff.request." + key + " is empty in " + TEST_DATA_FILE);
            }
        }
        String duration = getString(request, "duration");
        LocalDate[] dates = getRequestDates();
        if (duration.equals(MULTIPLE_DAYS)) {
            if (dates[1] == null || !dates[1].isAfter(dates[0])) {
                fail("For 'Multiple Days', requestDate must be a range 'start - end' with the end after the start.");
            }
        } else if (duration.equals(FULL_DAY)) {
            if (dates[1] != null) {
                fail("For 'Full Day', requestDate must be ONE date, not a range.");
            }
        } else {
            fail("duration '" + duration + "' is not offered by the app (seen live: 'Full Day', 'Multiple Days').");
        }
        // Specify Hours (only for Full Day): both times or none
        LocalTime start = getOptionalTime("startTime");
        LocalTime end = getOptionalTime("endTime");
        if ((start == null) != (end == null)) {
            fail("Fill BOTH timeOff.request.startTime and endTime, or leave both empty.");
        }
        if (start != null && !duration.equals(FULL_DAY)) {
            fail("startTime/endTime (Specify Hours) only exist for 'Full Day' (seen live).");
        }
    }

    // Duration -> Date (or range) -> optional hours -> Request Type -> Reason, all from shifts.json
    private void fillRequestForm() {
        JsonObject request = getRequestData();
        String duration = getString(request, "duration");
        LocalDate[] dates = getRequestDates();
        String requestType = getString(request, "requestType");
        String reason = getString(request, "reason");

        selectDuration(duration);
        if (duration.equals(MULTIPLE_DAYS)) {
            selectRequestDateRange(dates[0], dates[1]);
        } else {
            selectRequestDate(dates[0]);
            LocalTime start = getOptionalTime("startTime");
            if (start != null) {
                setSpecifiedHours(start, getOptionalTime("endTime"));
            }
        }
        selectRequestType(requestType);

        // Observation: is Apply already enabled before the Reason is typed?
        System.out.println("Apply enabled BEFORE entering the reason: " + waitForElement("request.apply.button").isEnabled());

        WebElement reasonField = waitForElement("request.reason.field");
        reasonField.click();
        reasonField.sendKeys(reason);
        hideKeyboard();
        assertEquals(reason, waitForElement("request.reason.field").getText().trim(), "Reason text");
        System.out.println("Reason entered.");
    }

    // The filled form shows exactly the values from shifts.json and Apply is enabled (nothing was reset)
    private void verifyFormMatchesData() {
        JsonObject request = getRequestData();
        String duration = getString(request, "duration");
        LocalDate[] dates = getRequestDates();

        verifyDurationSelected(duration);
        String expectedDate = dates[0].format(REQUEST_DATE_FORMAT);
        if (duration.equals(MULTIPLE_DAYS)) {
            expectedDate = expectedDate + " - " + dates[1].format(REQUEST_DATE_FORMAT);
        }
        assertEquals(expectedDate, getDateFieldText(), "Date shown on the filled form");
        verifyRequestTypeSelected(getString(request, "requestType"));
        assertEquals(getString(request, "reason"), waitForElement("request.reason.field").getText().trim(), "Reason shown on the filled form");
        assertTrue(waitForElement("request.apply.button").isEnabled(), "Apply is disabled on the filled form.");
        System.out.println("Verified: the form shows the configured values and Apply is enabled.");
    }

    // ---------- Reading the selected option (colour-based, calibrated on live screenshots) ----------

    // The app exposes NO selected/checked attribute for these options (seen live: always false).
    // A selected option is drawn in blue: measured on 2026-10-14 screenshots, the share of blue
    // pixels was 0.359 for a selected radio and 0.839 for a selected chip, and 0.000 for unselected ones.
    private static final double SELECTED_BLUE_SHARE = 0.15;

    // True if the element's own screenshot is mostly the app's selection blue
    private boolean looksSelected(WebElement element) {
        try {
            byte[] png = element.getScreenshotAs(OutputType.BYTES);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            int blue = 0;
            int total = 0;
            for (int x = 0; x < image.getWidth(); x += 2) {
                for (int y = 0; y < image.getHeight(); y += 2) {
                    int rgb = image.getRGB(x, y);
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    total++;
                    if (b > 150 && b > r + 60 && b > g + 30) {
                        blue++;
                    }
                }
            }
            return total > 0 && (double) blue / total > SELECTED_BLUE_SHARE;
        } catch (IOException e) {
            throw new RuntimeException("Could not read the element screenshot to check its selected state.", e);
        }
    }

    // The configured Duration must look selected and every other Duration option must not
    private void verifyDurationSelected(String duration) {
        for (String option : getTexts("request.duration.labels")) {
            boolean selected = looksSelected(findRadioForLabel(option));
            assertEquals(option.equals(duration), selected,
                    "Duration '" + option + "' is " + (selected ? "" : "not ") + "shown as selected.");
        }
        System.out.println("Verified: Duration '" + duration + "' is selected (and only it).");
    }

    // The configured Request Type must look selected and every other chip must not
    private void verifyRequestTypeSelected(String requestType) {
        for (WebElement chip : driver.findElements(getLocator("request.type.options"))) {
            String label = chip.getAttribute("content-desc").trim();
            boolean selected = looksSelected(chip);
            assertEquals(label.equals(requestType), selected,
                    "Request Type '" + label + "' is " + (selected ? "" : "not ") + "shown as selected.");
        }
        System.out.println("Verified: Request Type '" + requestType + "' is selected (and only it).");
    }

    // Taps the radio button next to the Duration label with exactly this text, then checks it is selected
    private void selectDuration(String duration) {
        // Already selected (e.g. the default Full Day): no tap needed
        if (looksSelected(findRadioForLabel(duration))) {
            verifyDurationSelected(duration);
            return;
        }
        findRadioForLabel(duration).click();
        waitUntilLooksSelected(() -> findRadioForLabel(duration));
        verifyDurationSelected(duration);
    }

    // Gives the app up to 3 seconds to redraw the option in blue (the check itself is done by the caller)
    private void waitUntilLooksSelected(java.util.function.Supplier<WebElement> option) {
        try {
            new WebDriverWait(driver, Duration.ofSeconds(3)).ignoring(StaleElementReferenceException.class)
                    .until(d -> looksSelected(option.get()));
        } catch (TimeoutException e) {
            // Not redrawn in time: the verify step that follows reports the real state
        }
    }

    // The radio circle directly to the LEFT of the Duration label with exactly this text
    private WebElement findRadioForLabel(String duration) {
        WebElement label = null;
        for (WebElement candidate : driver.findElements(getLocator("request.duration.labels"))) {
            if (candidate.getText().trim().equals(duration)) {
                label = candidate;
            }
        }
        if (label == null) {
            fail("Duration option '" + duration + "' is not shown. Available: " + getTexts("request.duration.labels"));
        }

        Rectangle labelBox = label.getRect();
        int labelCenterY = labelBox.getY() + labelBox.getHeight() / 2;
        WebElement radio = null;
        int closestRightEdge = -1;
        for (WebElement candidate : driver.findElements(getLocator("request.duration.radios"))) {
            Rectangle box = candidate.getRect();
            int rightEdge = box.getX() + box.getWidth();
            int centerY = box.getY() + box.getHeight() / 2;
            boolean sameRow = Math.abs(centerY - labelCenterY) < labelBox.getHeight();
            if (sameRow && rightEdge <= labelBox.getX() + 5 && rightEdge > closestRightEdge) {
                radio = candidate;
                closestRightEdge = rightEdge;
            }
        }
        if (radio == null) {
            fail("No radio button found next to '" + duration + "'.");
        }
        return radio;
    }

    // Full Day: one date. OK is tapped only if the app accepted the day (no message, day selected).
    private void selectRequestDate(LocalDate date) {
        openDatePicker();
        tapDayAndCheckAccepted(date);
        click("calendar.ok.button");
        waitForInvisible("calendar.box");

        String shown = getDateFieldText();
        assertEquals(date.format(REQUEST_DATE_FORMAT), shown, "Date shown after choosing " + date);
        System.out.println("Date selected: '" + shown + "'");
    }

    // Multiple Days: start day, then end day, then OK (this picker has only OK - seen live)
    private void selectRequestDateRange(LocalDate start, LocalDate end) {
        openDatePicker();
        tapDayAndCheckAccepted(start);
        tapDayAndCheckAccepted(end);
        click("calendar.ok.button");
        waitForInvisible("calendar.box");

        // Seen live: the field shows e.g. "Thu, 15 Oct 2026 - Fri, 16 Oct 2026"
        String expected = start.format(REQUEST_DATE_FORMAT) + " - " + end.format(REQUEST_DATE_FORMAT);
        String shown = getDateFieldText();
        assertEquals(expected, shown, "Date range shown after choosing " + start + " to " + end);
        System.out.println("Date range selected: '" + shown + "'");
    }

    // Taps a day and checks the app accepted it. Seen live 2026-10-14:
    //   - past days and today: the tap is ignored (day not selected, no message)
    //   - days overlapping an existing time off: a red message appears in the picker
    private void tapDayAndCheckAccepted(LocalDate date) {
        goToMonth(YearMonth.from(date));
        List<WebElement> days = driver.findElements(getCalendarDayLocator(date));
        if (days.size() != 1) {
            fail("Day " + date + " is not shown in the date picker.");
        }
        days.get(0).click();

        List<String> messages = getCalendarMessages();
        if (!messages.isEmpty()) {
            fail("The app refused " + date + ": " + messages + ". Nothing was applied.");
        }
        String dayLabel = driver.findElements(getCalendarDayLocator(date)).get(0).getAttribute("content-desc");
        if (dayLabel == null || !dayLabel.contains("selected")) {
            fail("The app ignored the tap on " + date + " (past dates and today cannot be chosen). Nothing was applied.");
        }
    }

    // Texts inside the open calendar that are not the month, weekday names, day numbers or buttons,
    // i.e. validation messages such as "Time Off requests are not allowed for this date. ..."
    private List<String> getCalendarMessages() {
        List<String> messages = new ArrayList<>();
        String month = getFirstText("calendar.month.title");
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                messages.clear();
                for (WebElement text : driver.findElements(getLocator("calendar.box.texts"))) {
                    String value = text.getText().trim();
                    boolean standard = value.isEmpty() || value.equals(month) || value.matches("\\d{1,2}")
                            || value.matches("Sun|Mon|Tue|Wed|Thu|Fri|Sat") || value.equals("Cancel") || value.equals("OK");
                    if (!standard) {
                        messages.add(value);
                    }
                }
                return messages;
            } catch (StaleElementReferenceException e) {
                // The calendar re-rendered while reading - read it again
            }
        }
        return messages;
    }

    // Full Day + Specify Hours: opens the section and sets Start Time (left field) and End Time (right field)
    private void setSpecifiedHours(LocalTime start, LocalTime end) {
        click("request.specify.hours");
        wait.until(ExpectedConditions.numberOfElementsToBe(getLocator("request.hours.fields"), 2));
        LocalTime[] times = {start, end};
        String[] names = {"Start Time", "End Time"};
        for (int i = 0; i < 2; i++) {
            List<WebElement> fields = driver.findElements(getLocator("request.hours.fields"));
            WebElement field = fields.get(0).getRect().getX() < fields.get(1).getRect().getX()
                    ? fields.get(i) : fields.get(1 - i);
            field.click();
            setTimeInPicker(times[i]);

            List<WebElement> after = driver.findElements(getLocator("request.hours.fields"));
            WebElement fieldAfter = after.get(0).getRect().getX() < after.get(1).getRect().getX()
                    ? after.get(i) : after.get(1 - i);
            String shown = fieldAfter.getAttribute("content-desc");
            // PENDING: the display format of a chosen hour has not been seen yet - only "no longer empty" is checked
            assertFalse("HH:MM".equals(shown), names[i] + " is still empty after choosing " + times[i]);
            System.out.println(names[i] + " set: '" + shown + "'");
        }
    }

    // Android's time dialog: AM/PM in clock mode (am_label / pm_label), then hour and minute typed in text mode
    private void setTimeInPicker(LocalTime time) {
        waitForElement("time.picker");
        if (isPresent("time.input.hour")) {
            click("time.mode.toggle"); // to clock mode, where AM/PM are buttons
        }
        boolean morning = time.getHour() < 12;
        click(morning ? "time.clock.am" : "time.clock.pm");
        click("time.mode.toggle"); // to text mode
        int hour12 = time.getHour() % 12 == 0 ? 12 : time.getHour() % 12;
        WebElement hour = waitForElement("time.input.hour");
        hour.clear();
        hour.sendKeys(String.valueOf(hour12));
        WebElement minute = waitForElement("time.input.minute");
        minute.clear();
        minute.sendKeys(String.format("%02d", time.getMinute()));
        String amPm = waitForElement("time.ampm.text").getText().trim();
        assertTrue(amPm.equalsIgnoreCase(morning ? "AM" : "PM"), "AM/PM shows '" + amPm + "' for " + time);
        click("time.ok.button");
        waitForInvisible("time.picker");
    }

    // Taps the Request Type chip whose label is exactly the configured value
    private void selectRequestType(String requestType) {
        for (WebElement chip : driver.findElements(getLocator("request.type.options"))) {
            String label = chip.getAttribute("content-desc");
            if (label != null && label.trim().equals(requestType)) {
                chip.click();
                waitUntilLooksSelected(() -> driver.findElement(AppiumBy.accessibilityId(requestType)));
                verifyRequestTypeSelected(requestType);
                return;
            }
        }
        fail("Request Type '" + requestType + "' is not shown. Available: " + getOptionLabels("request.type.options"));
    }

    // The date field is the top-most clickable between "Date" and "Request Type"
    private WebElement getDateField() {
        List<WebElement> candidates = driver.findElements(getLocator("request.date.area"));
        if (candidates.isEmpty()) {
            fail("The Date field is not shown.");
        }
        WebElement top = candidates.get(0);
        for (WebElement candidate : candidates) {
            if (candidate.getRect().getY() < top.getRect().getY()) {
                top = candidate;
            }
        }
        return top;
    }

    private String getDateFieldText() {
        String text = "";
        for (WebElement part : getDateField().findElements(getLocator("request.date.texts"))) {
            text = text + part.getText().trim();
        }
        return text;
    }

    private LocalDate parseRequestDate(String text) {
        try {
            return LocalDate.parse(text, REQUEST_DATE_FORMAT);
        } catch (DateTimeParseException e) {
            throw new RuntimeException("Could not read the date '" + text + "' (expected e.g. 'Wed, 14 Oct 2026').", e);
        }
    }

    private JsonObject getRequestData() {
        return timeOffData.getAsJsonObject("request");
    }

    // ============================================================
    // Calendars (the app's own calendar component)
    // ============================================================

    private void openDatePicker() {
        getDateField().click();
        waitForElement("calendar.month.title");
    }

    // Closes the request date picker without choosing anything. Seen live: the Full Day picker has
    // Cancel; the Multiple Days picker has only OK and is closed by tapping the dimmed background.
    private void closeDatePickerWithoutChoosing() {
        if (isPresent("calendar.cancel.button")) {
            click("calendar.cancel.button");
            waitForInvisible("calendar.box");
        } else {
            closeCalendarWithoutApplying();
        }
    }

    // Closes an open calendar by tapping the dimmed background below it (seen live), if one is open
    private void closeCalendarWithoutApplying() {
        List<WebElement> boxes = driver.findElements(getLocator("calendar.box"));
        if (boxes.isEmpty() || driver.findElements(getLocator("calendar.background")).isEmpty()) {
            return;
        }
        int boxBottom = boxes.get(0).getRect().getY() + boxes.get(0).getRect().getHeight();
        int screenHeight = driver.manage().window().getSize().getHeight();
        Map<String, Object> params = new HashMap<>();
        params.put("x", driver.manage().window().getSize().getWidth() / 2);
        params.put("y", boxBottom + (screenHeight - boxBottom) / 2);
        driver.executeScript("mobile: clickGesture", params);
        waitForInvisible("calendar.box");
    }

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

    // The day button for this date. The date is validated, so it only has digits and dashes.
    private By getCalendarDayLocator(LocalDate date) {
        return buildLocator("calendar.day", readLocatorText("calendar.day").replace("{date}", date.toString()));
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

    private List<String> getTexts(String locatorKey) {
        List<String> texts = new ArrayList<>();
        for (WebElement element : driver.findElements(getLocator(locatorKey))) {
            texts.add(element.getText().trim());
        }
        return texts;
    }

    // Labels of option chips (their accessibility label is the visible text)
    private List<String> getOptionLabels(String locatorKey) {
        List<String> labels = new ArrayList<>();
        for (WebElement element : driver.findElements(getLocator(locatorKey))) {
            String label = element.getAttribute("content-desc");
            if (label != null) {
                labels.add(label.trim());
            }
        }
        return labels;
    }

    // Every visible text on the screen (used to look for messages whose location is not known yet)
    private List<String> getAllScreenTexts() {
        List<String> texts = new ArrayList<>();
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                texts.clear();
                for (WebElement text : driver.findElements(getLocator("screen.texts"))) {
                    String value = text.getText().trim();
                    if (!value.isEmpty()) {
                        texts.add(value);
                    }
                }
                return texts;
            } catch (StaleElementReferenceException e) {
                // The screen changed while reading (e.g. right after Apply) - read it again
            }
        }
        return texts;
    }

    // PENDING: whether this app uses Android toasts has not been seen yet
    private List<String> getToastTexts() {
        List<String> texts = new ArrayList<>();
        for (WebElement toast : driver.findElements(getLocator("toast.message"))) {
            texts.add(toast.getText().trim());
        }
        return texts;
    }

    // First whole number in a text, e.g. "You have 1 time off applied" -> 1
    private int getFirstNumber(String text) {
        String digits = "";
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isDigit(c)) {
                digits = digits + c;
            } else if (!digits.isEmpty()) {
                break;
            }
        }
        if (digits.isEmpty()) {
            fail("No number found in '" + text + "'.");
        }
        return Integer.parseInt(digits);
    }

    private void hideKeyboard() {
        try {
            driver.hideKeyboard();
        } catch (Exception e) {
            // Keyboard was not open - nothing to do
        }
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
        if (object != null && object.has(key) && !object.get(key).isJsonNull()) {
            return object.get(key).getAsString().trim();
        }
        return "";
    }

    // A single date from the given JSON object. Skips if empty, fails if the format is wrong.
    private LocalDate getRequiredDate(JsonObject object, String key) {
        String value = getString(object, key);
        if (value.isEmpty()) {
            throw new TestAbortedException("SKIPPED: '" + key + "' is empty in " + TEST_DATA_FILE + " (timeOff section).");
        }
        return parseDate(key, value);
    }

    // Accepts YYYY-MM-DD (e.g. 2026-10-18) or DD-MM-YYYY (e.g. 18-10-2026). Fails for anything else.
    private LocalDate parseDate(String key, String value) {
        String[] patterns = {"yyyy-MM-dd", "dd-MM-yyyy"};
        for (String pattern : patterns) {
            try {
                return LocalDate.parse(value.trim(), DateTimeFormatter.ofPattern(pattern));
            } catch (DateTimeParseException e) {
                // try the next format
            }
        }
        fail(key + " '" + value + "' must use the format YYYY-MM-DD or DD-MM-YYYY.");
        return null;
    }

    // timeOff.request.requestDate: one date ("18-10-2026") or a range ("18-10-2026 - 23-10-2026").
    // Returns {start, end}; end is null for a single date. Skips if empty.
    private LocalDate[] getRequestDates() {
        String value = getString(getRequestData(), "requestDate");
        if (value.isEmpty()) {
            throw new TestAbortedException("SKIPPED: 'requestDate' is empty in " + TEST_DATA_FILE + " (timeOff section).");
        }
        String[] parts = value.split(" - ");
        if (parts.length == 1) {
            return new LocalDate[] {parseDate("requestDate", parts[0]), null};
        }
        if (parts.length == 2) {
            return new LocalDate[] {parseDate("requestDate", parts[0]), parseDate("requestDate", parts[1])};
        }
        fail("requestDate '" + value + "' must be one date, or two dates separated by ' - '.");
        return null;
    }

    // timeOff.request.startTime / endTime as HH:mm (24-hour), or null if empty
    private LocalTime getOptionalTime(String key) {
        String value = getString(getRequestData(), key);
        if (value.isEmpty()) {
            return null;
        }
        try {
            return LocalTime.parse(value, DateTimeFormatter.ofPattern("HH:mm"));
        } catch (DateTimeParseException e) {
            fail("timeOff.request." + key + " '" + value + "' must use the format HH:mm (24-hour), e.g. 09:30.");
            return null;
        }
    }
}
