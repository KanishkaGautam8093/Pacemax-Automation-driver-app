package com.pacemax.automation.dashboard;

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
 * Dashboard - Accident Reporting automation (STEP 1 "General Information" only).
 *
 * Locators   -> locators/dashboard_accident.properties
 * Test data  -> test-data/dashboard.json  (the "accidentReporting" section)
 * Settings   -> config/config.properties
 *
 * SAFETY: nothing in this class selects a value, presses Next, or submits.
 * Pickers and dropdowns are opened, checked, and closed with Cancel / the background.
 * Steps 2-4 have not been inspected, so they are not automated.
 *
 * Run all tests:  mvn test -Dtest=DashboardAccident
 * Run one test:   mvn test -Dtest=DashboardAccident#driverNameTest
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DashboardAccident {

    private static final String LOCATOR_FILE = "locators/dashboard_accident.properties";
    private static final String TEST_DATA_FILE = "test-data/dashboard.json";
    private static final String SCREENSHOT_FOLDER = "target/screenshots";

    // Safety limits so scrolling / month navigation can never loop forever
    private static final int MAX_SCROLLS = 20;
    private static final int MAX_MONTH_CLICKS = 36;

    private static AndroidDriver driver;
    private static WebDriverWait wait;
    private static Properties locators;
    private static JsonObject accidentData;

    // ============================================================
    // Setup and cleanup (runs once for the whole class)
    // ============================================================

    @BeforeAll
    static void setUp() {
        locators = loadLocators();
        accidentData = loadTestData().getAsJsonObject("accidentReporting");
        driver = DriverManager.startDriver();
        int waitSeconds = ConfigReader.getInt("explicit.wait.seconds");
        wait = new WebDriverWait(driver, Duration.ofSeconds(waitSeconds));
    }

    @AfterAll
    static void tearDown() {
        DriverManager.quitDriver();
    }

    // ============================================================
    // Tests (none of them select, press Next, or submit)
    // ============================================================

    @Test
    @Order(1)
    // ENABLED read-only SMOKE test: Dashboard -> Report -> checks the 4 steps -> leaves.
    // It is the only test that runs by default, so "mvn test -Dtest=DashboardAccident" always
    // does something visible WITHOUT entering data or submitting.
    void openAccidentReportTest() {
        try {
            openAccidentReport();

            // The 4 steps seen live
            assertTrue(isPresent("accident.step1.label"), "Step 1 'General Information' is not shown.");
            assertTrue(isPresent("accident.step2.label"), "Step 2 'Your Vehicle Damage' is not shown.");
            assertTrue(isPresent("accident.step3.label"), "Step 3 'Other Party Information' is not shown.");
            assertTrue(isPresent("accident.step4.label"), "Step 4 'Additional Information' is not shown.");
            System.out.println("PASS: Accident Reporting opened with its 4 steps.");
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("openAccidentReportTest");
            throw error;
        } finally {
            leaveAccidentReport();
        }
    }

    @Test
    @Order(2)
    @Disabled("Replaced by completeAccidentReportFlowTest: this read-only check opens and leaves the form on its own. Kept for on-demand runs.")
    void driverNameTest() {
        try {
            openAccidentReport();

            assertTrue(isPresent("accident.driver.name.label"), "'Driver Name' label is not shown.");
            WebElement field = waitForElement("accident.driver.name.field");
            String driverName = field.getText().trim();

            assertFalse(driverName.isEmpty(), "Driver Name is empty.");
            assertFalse(field.isEnabled(), "Driver Name can be edited (it should be read-only).");
            System.out.println("PASS: Driver Name is filled in ('" + driverName + "') and cannot be edited.");

            // Compare only if an expected name is configured
            String expectedName = getString(accidentData, "expectedDriverName");
            if (expectedName.isEmpty()) {
                System.out.println("expectedDriverName is not set - name read from the app, not compared.");
            } else {
                assertEquals(expectedName, driverName, "Driver Name");
                System.out.println("PASS: Driver Name matches the expected value.");
            }
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("driverNameTest");
            throw error;
        } finally {
            leaveAccidentReport();
        }
    }

    @Test
    @Order(3)
    @Disabled("Replaced by completeAccidentReportFlowTest: this read-only check opens and leaves the form on its own. Kept for on-demand runs.")
    void vanOptionsTest() {
        try {
            openAccidentReport();
            assertTrue(isPresent("accident.van.label"), "'Van Name*' label is not shown.");

            click("accident.van.dropdown");
            waitForElement("dropdown.list");
            List<String> vans = readAllDropdownOptions();
            closeDropdownWithoutSelecting();

            System.out.println("Vans in the dropdown (" + vans.size() + "): " + vans);
            assertFalse(vans.isEmpty(), "The Van dropdown has no options.");

            // Nothing may have been selected
            assertTrue(isPresent("accident.van.dropdown"), "'Select Van' is no longer shown - a van may have been selected.");

            String targetVan = getString(accidentData, "targetVanName");
            if (targetVan.isEmpty()) {
                System.out.println("targetVanName is not set - options read, not checked.");
            } else {
                assertTrue(vans.contains(targetVan), "Van '" + targetVan + "' is not in the dropdown. Available: " + vans);
                System.out.println("PASS: van '" + targetVan + "' is available (not selected).");
            }
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("vanOptionsTest");
            throw error;
        } finally {
            leaveAccidentReport();
        }
    }

    @Test
    @Order(4)
    @Disabled("Replaced by completeAccidentReportFlowTest: this read-only check opens and leaves the form on its own. Kept for on-demand runs.")
    void datePickerTest() {
        LocalDate accidentDate = getRequiredDate();
        try {
            openAccidentReport();

            click("accident.date.field");
            waitForElement("calendar.month.title");
            goToMonth(YearMonth.from(accidentDate));

            // The day button's resource-id uses yyyy-MM-dd (seen live), e.g. undefined.day_2026-10-13
            By dayLocator = getCalendarDayLocator(accidentDate);
            assertTrue(driver.findElements(dayLocator).size() == 1,
                    "Day " + accidentDate + " is not shown in the calendar.");
            System.out.println("PASS: " + accidentDate + " is available in the date picker (not selected).");

            click("calendar.cancel.button");
            waitForElement("accident.date.field"); // still "mm/dd/yyyy" = nothing selected
            System.out.println("PASS: date picker closed with Cancel - no date selected.");
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("datePickerTest");
            throw error;
        } finally {
            leaveAccidentReport();
        }
    }

    @Test
    @Order(5)
    @Disabled("Replaced by completeAccidentReportFlowTest: this read-only check opens and leaves the form on its own. Kept for on-demand runs.")
    void timePickerTest() {
        LocalTime accidentTime = getRequiredTime();
        try {
            openAccidentReport();

            click("accident.time.field");
            waitForElement("time.picker");

            // Typed input is more reliable than the clock face - switch to it if needed
            if (!isPresent("time.input.hour")) {
                click("time.mode.toggle");
            }
            waitForElement("time.input.hour");
            assertTrue(isPresent("time.input.minute"), "Minute box is not shown in the time picker.");
            assertTrue(isPresent("time.ampm.spinner"), "AM/PM selector is not shown in the time picker.");

            // The picker is 12-hour (seen live) - show how HH:mm would be entered
            System.out.println("PASS: time picker supports typed input. '" + accidentTime + "' would be entered as "
                    + getHour12(accidentTime) + ":" + String.format("%02d", accidentTime.getMinute()) + " "
                    + getAmPm(accidentTime) + " (not entered).");

            click("time.cancel.button");
            waitForElement("accident.time.field"); // still "-- : --" = nothing selected
            System.out.println("PASS: time picker closed with CANCEL - no time selected.");
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("timePickerTest");
            throw error;
        } finally {
            leaveAccidentReport();
        }
    }

    // ============================================================
    // PRODUCTION-SAFE END-TO-END: all 4 steps with Next, STOPS BEFORE SUBMIT.
    // Seen live 2026-10-13: Next does not keep a draft - leaving the form discards everything.
    // This test re-checks that after leaving (the reopened form must be empty).
    // ============================================================

    @Test
    @Order(9)
    void accidentFlowWithoutSubmitTest() {
        // Stop BEFORE opening the form if a mandatory value is missing (names the exact key)
        String vanName = getMandatoryValue("targetVanName");
        LocalDate accidentDate = parseMandatoryDate();
        LocalTime accidentTime = parseMandatoryTime();

        List<String> completed = new ArrayList<>();
        try {
            openAccidentReport(); // Dashboard -> Report, ONCE

            checkCurrentStep(1, "accident.step1.marker");
            checkDriverName();
            selectVan(vanName);
            selectAccidentDate(accidentDate);
            selectAccidentTime(accidentTime);
            fillConfiguredFields("generalInformation");
            completed.add("Step 1 General Information (van, date, time" + describeConfigured("generalInformation") + ")");
            goToNextStep(2, "accident.step2.marker");

            fillConfiguredFields("vehicleDamage");
            completed.add("Step 2 Your Vehicle Damage" + describeConfigured("vehicleDamage"));
            goToNextStep(3, "accident.step3.marker");

            fillConfiguredFields("otherPartyInformation");
            completed.add("Step 3 Other Party Information" + describeConfigured("otherPartyInformation"));
            goToNextStep(4, "accident.step4.marker");

            fillConfiguredFields("additionalInformation");
            completed.add("Step 4 Additional Information" + describeConfigured("additionalInformation"));

            // STOP here: Submit must be present and usable, but it is NEVER tapped
            WebElement submit = scrollToElement("accident.submit.button");
            assertTrue(submit.isDisplayed() && submit.isEnabled(), "Submit is not shown/enabled on step 4.");
            System.out.println("Submit is shown on step 4 - NOT tapped (production-safe test).");
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("accidentFlowWithoutSubmitTest");
            throw error;
        } finally {
            System.out.println("Completed steps: " + completed);
            leaveAccidentReport(); // discards the form - nothing was submitted
        }

        // Safety check: the reopened form must be empty (no draft was kept)
        openAccidentReport();
        try {
            assertTrue(isPresent("accident.van.dropdown"), "Reopened form still shows a van - a draft may have been kept.");
            assertTrue(isPresent("accident.date.field"), "Reopened form still shows a date - a draft may have been kept.");
            System.out.println("PASS: reopened form is empty - no draft was kept.");
        } finally {
            leaveAccidentReport();
        }
        System.out.println("PASS: all 4 steps completed and navigated with Next. Submit was NOT tapped.");
    }

    // " + N configured fields" for the report, or "" if none are configured
    private String describeConfigured(String stepKey) {
        int count = getFieldList(stepKey).size();
        return count == 0 ? "" : ", " + count + " configured field(s)";
    }

    // ============================================================
    // END-TO-END: one complete Accident Report, opened ONCE, moved forward with Next only.
    // SUBMITS a real accident report - @Disabled until the user approves the data and the run.
    // ============================================================

    @Test
    @Order(10)
    // SUBMITS a real accident report, so it runs ONLY when explicitly switched on:
    //   mvn test "-Dtest=DashboardAccident#completeAccidentReportFlowTest" -Daccident.submit.approved=true
    @EnabledIfSystemProperty(named = "accident.submit.approved", matches = "true",
            disabledReason = "SKIPPED on purpose: this test creates a real accident report. Add -Daccident.submit.approved=true to run it.")
    void completeAccidentReportFlowTest() {
        // Stop BEFORE opening the form if a mandatory value is missing (names the exact key)
        String vanName = getMandatoryValue("targetVanName");
        LocalDate accidentDate = parseMandatoryDate();
        LocalTime accidentTime = parseMandatoryTime();

        boolean submitTapped = false;
        try {
            openAccidentReport(); // Dashboard -> Report, ONCE

            // ---- Step 1: General Information ----
            checkCurrentStep(1, "accident.step1.marker");
            checkDriverName();
            selectVan(vanName);
            selectAccidentDate(accidentDate);
            selectAccidentTime(accidentTime);
            fillConfiguredFields("generalInformation");
            goToNextStep(2, "accident.step2.marker");

            // ---- Step 2: Your Vehicle Damage ----
            fillConfiguredFields("vehicleDamage");
            goToNextStep(3, "accident.step3.marker");

            // ---- Step 3: Other Party Information ----
            fillConfiguredFields("otherPartyInformation");
            goToNextStep(4, "accident.step4.marker");

            // ---- Step 4: Additional Information ----
            fillConfiguredFields("additionalInformation");

            // ---- Submit ONCE ----
            WebElement submit = scrollToElement("accident.submit.button");
            System.out.println("Tapping Submit (once)...");
            submitTapped = true; // from here on Submit is never tapped again
            submit.click();
            verifySubmission();
        } catch (RuntimeException | AssertionError error) {
            System.out.println(submitTapped
                    ? "Submit WAS tapped once - it will NOT be tapped again."
                    : "Submit was NOT tapped - no accident report was created.");
            takeScreenshot("completeAccidentReportFlowTest");
            throw error;
        } finally {
            // Leaving before Submit discards the form (seen live: no draft is kept)
            if (!submitTapped) {
                leaveAccidentReport();
            }
        }
    }

    // ---------- Step checks ----------

    // All 4 step names are always on screen, so a step is recognised by a text only it shows
    private void checkCurrentStep(int stepNumber, String markerKey) {
        if (!isPresent(markerKey)) {
            fail("Expected to be on step " + stepNumber + " but its content ('" + markerKey + "') is not shown.");
        }
        System.out.println("---- Step " + stepNumber + " is shown.");
    }

    // Taps Next ONCE and checks the next step opened. Never taps Next again on failure.
    private void goToNextStep(int nextStepNumber, String nextMarkerKey) {
        scrollToElement("accident.next.button").click();
        try {
            wait.until(ExpectedConditions.presenceOfElementLocated(getLocator(nextMarkerKey)));
        } catch (TimeoutException e) {
            fail("After tapping Next, step " + nextStepNumber + " did not open. Texts on screen: " + getAllScreenTexts());
        }
        checkCurrentStep(nextStepNumber, nextMarkerKey);
    }

    private void checkDriverName() {
        WebElement field = waitForElement("accident.driver.name.field");
        String driverName = field.getText().trim();
        assertFalse(driverName.isEmpty(), "Driver Name is empty.");
        assertFalse(field.isEnabled(), "Driver Name can be edited (it should be read-only).");
        String expectedName = getString(accidentData, "expectedDriverName");
        if (!expectedName.isEmpty()) {
            assertEquals(expectedName, driverName, "Driver Name");
        }
        System.out.println("Driver Name: '" + driverName + "' (read-only).");
    }

    // ---------- Step 1 mandatory fields ----------

    // Opens the Van dropdown (the control under 'Van Name*') and picks the exact van name
    private void selectVan(String vanName) {
        WebElement label = waitForElement("accident.van.label");
        WebElement dropdown = getSingleChoiceUnder(label);
        dropdown.click();
        waitForElement("dropdown.list");

        WebElement option = null;
        for (int scrollCount = 0; scrollCount <= MAX_SCROLLS && option == null; scrollCount++) {
            for (WebElement text : driver.findElements(getLocator("dropdown.option.texts"))) {
                if (text.getText().trim().equals(vanName)) {
                    option = text;
                }
            }
            if (option == null && !scrollDropdownList()) {
                break;
            }
        }
        if (option == null) {
            List<String> available = readAllDropdownOptions();
            closeDropdownWithoutSelecting();
            fail("Van '" + vanName + "' is not in the dropdown. Available: " + available);
        }
        option.click();

        String shown = getSingleChoiceUnder(waitForElement("accident.van.label")).getAttribute("content-desc");
        assertEquals(vanName, shown, "Van shown after selecting");
        System.out.println("Van selected: '" + shown + "'");
    }

    // Date field = LEFT control under 'Accident Date and Time*'; time field = RIGHT one (seen live)
    private void selectAccidentDate(LocalDate date) {
        getDateTimeControls()[0].click();
        waitForElement("calendar.month.title");
        goToMonth(YearMonth.from(date));
        List<WebElement> days = driver.findElements(getCalendarDayLocator(date));
        if (days.size() != 1) {
            fail("Day " + date + " is not shown in the date picker.");
        }
        days.get(0).click();
        click("calendar.ok.button");
        waitForInvisible("calendar.month.title");

        // Seen live: the field then shows MM/dd/yyyy, e.g. 10/13/2026
        String shown = getDateTimeControls()[0].getAttribute("content-desc");
        assertEquals(date.format(DateTimeFormatter.ofPattern("MM/dd/yyyy")), shown, "Accident date shown");
        System.out.println("Accident date selected: '" + shown + "'");
    }

    // Uses the AM/PM buttons of the CLOCK mode, then types hour and minute in TEXT mode
    private void selectAccidentTime(LocalTime time) {
        getDateTimeControls()[1].click();
        waitForElement("time.picker");

        // 1. AM/PM in clock mode (the am_label / pm_label buttons were seen live)
        if (isPresent("time.input.hour")) {
            click("time.mode.toggle"); // switch to clock mode
        }
        click(getAmPm(time).equals("AM") ? "time.clock.am" : "time.clock.pm");

        // 2. Hour and minute in text mode
        click("time.mode.toggle");
        WebElement hour = waitForElement("time.input.hour");
        hour.clear();
        hour.sendKeys(String.valueOf(getHour12(time)));
        WebElement minute = waitForElement("time.input.minute");
        minute.clear();
        minute.sendKeys(String.format("%02d", time.getMinute()));

        String amPmShown = waitForElement("time.ampm.text").getText().trim();
        assertTrue(amPmShown.equalsIgnoreCase(getAmPm(time)), "AM/PM in the picker is '" + amPmShown + "', expected " + getAmPm(time));
        click("time.ok.button");
        waitForInvisible("time.picker");

        // Seen live: the field then shows hh:mm AM/PM, e.g. 05:30 PM
        String shown = getDateTimeControls()[1].getAttribute("content-desc");
        assertEquals(time.format(DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH)), shown, "Accident time shown");
        System.out.println("Accident time selected: '" + shown + "'");
    }

    // The two controls under 'Accident Date and Time*', ordered left (date) to right (time)
    private WebElement[] getDateTimeControls() {
        List<WebElement> controls = getChoicesUnder(scrollToElement("accident.datetime.label"));
        if (controls.size() != 2) {
            fail("Expected 2 controls (date, time) under 'Accident Date and Time*' but found " + controls.size() + ".");
        }
        WebElement first = controls.get(0);
        WebElement second = controls.get(1);
        if (first.getRect().getX() > second.getRect().getX()) {
            return new WebElement[] {second, first};
        }
        return new WebElement[] {first, second};
    }

    // ---------- Configured optional fields (all 4 steps) ----------

    // Fills every entry of the given step list in dashboard.json:
    //   {"section": "", "label": "", "type": "text" or "choice", "value": ""}
    // "section" = heading the label is under (needed when a label repeats, e.g. "State").
    private void fillConfiguredFields(String stepKey) {
        List<JsonObject> fields = getFieldList(stepKey);
        if (fields.isEmpty()) {
            System.out.println("No optional fields configured for '" + stepKey + "' - left as they are.");
            return;
        }
        for (JsonObject field : fields) {
            String section = getString(field, "section");
            String label = getString(field, "label");
            String type = getString(field, "type");
            String value = getString(field, "value");

            // Never type or pick an empty value
            if (label.isEmpty() || value.isEmpty()) {
                fail(stepKey + ": an entry has an empty 'label' or 'value' (label='" + label + "'). Fill it or remove it.");
            }
            if (type.equals("text")) {
                fillTextField(section, label, value);
            } else if (type.equals("choice")) {
                selectChoice(section, label, value);
            } else {
                fail(stepKey + ": field '" + label + "' has unknown type '" + type + "' (use 'text' or 'choice').");
            }
        }
    }

    // Types into the text box under the label (or the box whose placeholder IS the label, e.g. 'East')
    private void fillTextField(String section, String label, String value) {
        WebElement input = null;
        WebElement labelElement = findLabel(section, label);
        if (labelElement != null) {
            List<WebElement> inputs = getInputsUnder(labelElement);
            if (inputs.size() == 1) {
                input = inputs.get(0);
            } else {
                fail("Found " + inputs.size() + " text boxes under '" + label + "' - add the box's placeholder as the label instead.");
            }
        } else {
            input = findInputByPlaceholder(label);
        }
        if (input == null) {
            fail("Text field '" + label + "'" + (section.isEmpty() ? "" : " (section '" + section + "')") + " was not found on this step.");
        }
        input.click();
        input.clear();
        input.sendKeys(value);
        hideKeyboard();
        System.out.println("Entered '" + label + "'.");
    }

    // Taps the choice (radio / Yes-No / option button) labelled 'value' under the question label
    private void selectChoice(String section, String label, String value) {
        WebElement labelElement = findLabel(section, label);
        if (labelElement == null) {
            fail("Question '" + label + "'" + (section.isEmpty() ? "" : " (section '" + section + "')") + " was not found on this step.");
        }
        WebElement choice = null;
        List<String> available = new ArrayList<>();
        for (WebElement candidate : getChoicesUnder(labelElement)) {
            String text = candidate.getAttribute("content-desc").trim();
            available.add(text);
            if (text.equals(value)) {
                choice = candidate;
            }
        }
        if (choice == null) {
            fail("Option '" + value + "' is not offered for '" + label + "'. Available: " + available);
        }
        choice.click();

        // Real radio buttons report checked=true (seen live on 'Did you call 911?')
        if ("true".equals(choice.getAttribute("checkable"))) {
            assertEquals("true", choice.getAttribute("checked"), "'" + value + "' is not selected for '" + label + "'");
            System.out.println("Selected '" + value + "' for '" + label + "' (verified).");
        } else {
            // PENDING: option buttons show their selection by colour only - not readable
            System.out.println("Selected '" + value + "' for '" + label + "' (selection is colour-only, not readable).");
        }
    }

    // ---------- Finding labels and the controls under them (the form is flat) ----------

    // The label with exactly this text. If 'section' is set, the first one BELOW that section heading.
    // Scrolls down the step until it is on screen. Returns null if the step does not have it.
    private WebElement findLabel(String section, String label) {
        for (int scrollCount = 0; scrollCount <= MAX_SCROLLS; scrollCount++) {
            List<WebElement> labels = driver.findElements(getLocator("accident.form.labels"));
            Integer sectionTop = null;
            if (!section.isEmpty()) {
                for (WebElement candidate : labels) {
                    if (candidate.getText().trim().equals(section)) {
                        sectionTop = candidate.getRect().getY();
                    }
                }
            }
            WebElement best = null;
            int matches = 0;
            for (WebElement candidate : labels) {
                if (!candidate.getText().trim().equals(label)) {
                    continue;
                }
                int top = candidate.getRect().getY();
                if (section.isEmpty()) {
                    matches++;
                    best = candidate;
                } else if (sectionTop != null && top > sectionTop
                        && (best == null || top < best.getRect().getY())) {
                    best = candidate;
                }
            }
            if (section.isEmpty() && matches > 1) {
                fail("'" + label + "' appears " + matches + " times on this step - set its 'section' in dashboard.json.");
            }
            // Use it only when there is room below it for its control (upper 70% of the screen)
            int screenHeight = driver.manage().window().getSize().getHeight();
            if (best != null && best.getRect().getY() < screenHeight * 7 / 10) {
                return best;
            }
            if (!scrollForm()) {
                return best; // end of the step - use it where it is (or null)
            }
        }
        return null;
    }

    private WebElement findInputByPlaceholder(String placeholder) {
        for (int scrollCount = 0; scrollCount <= MAX_SCROLLS; scrollCount++) {
            for (WebElement input : driver.findElements(getLocator("accident.form.inputs"))) {
                if (input.getText().trim().equals(placeholder)) {
                    return input;
                }
            }
            if (!scrollForm()) {
                return null;
            }
        }
        return null;
    }

    // Area that belongs to a label: from the label down to the next label. Returns {top, bottom}.
    private int[] getRegionBelow(WebElement label) {
        int top = label.getRect().getY();
        int bottom = driver.manage().window().getSize().getHeight();
        for (WebElement other : driver.findElements(getLocator("accident.form.labels"))) {
            int otherTop = other.getRect().getY();
            if (otherTop > top && otherTop < bottom) {
                bottom = otherTop;
            }
        }
        return new int[] {top, bottom};
    }

    private List<WebElement> getChoicesUnder(WebElement label) {
        return getElementsInRegion("accident.form.choices", getRegionBelow(label));
    }

    private List<WebElement> getInputsUnder(WebElement label) {
        return getElementsInRegion("accident.form.inputs", getRegionBelow(label));
    }

    private WebElement getSingleChoiceUnder(WebElement label) {
        List<WebElement> choices = getChoicesUnder(label);
        if (choices.size() != 1) {
            fail("Expected 1 control under '" + label.getText().trim() + "' but found " + choices.size() + ".");
        }
        return choices.get(0);
    }

    private List<WebElement> getElementsInRegion(String locatorKey, int[] region) {
        List<WebElement> inside = new ArrayList<>();
        for (WebElement element : driver.findElements(getLocator(locatorKey))) {
            Rectangle box = element.getRect();
            int centerY = box.getY() + box.getHeight() / 2;
            if (centerY > region[0] && centerY < region[1]) {
                inside.add(element);
            }
        }
        return inside;
    }

    // ---------- Scrolling the step ----------

    // Scrolls the step until the element is on screen (fixed footer buttons are found straight away)
    private WebElement scrollToElement(String locatorKey) {
        for (int scrollCount = 0; scrollCount <= MAX_SCROLLS; scrollCount++) {
            List<WebElement> found = driver.findElements(getLocator(locatorKey));
            if (!found.isEmpty()) {
                return found.get(0);
            }
            if (!scrollForm()) {
                break;
            }
        }
        fail("'" + locatorKey + "' was not found on this step (" + locators.getProperty(locatorKey) + ").");
        return null;
    }

    // Scrolls the step's form down once. Returns false if nothing moved (end of the step).
    private boolean scrollForm() {
        List<WebElement> forms = driver.findElements(getLocator("accident.form.scroll"));
        if (forms.isEmpty()) {
            return false;
        }
        String before = getAllScreenTexts().toString();
        Map<String, Object> params = new HashMap<>();
        params.put("elementId", getElementId(forms.get(0)));
        params.put("direction", "down");
        params.put("percent", 0.6);
        driver.executeScript("mobile: scrollGesture", params);
        return !getAllScreenTexts().toString().equals(before);
    }

    // ---------- After Submit ----------

    // Seen after the real submission on 2026-10-13: the form closes, the Dashboard is shown,
    // and a green banner "Accident Report submitted" appears at the top for a short time.
    // Outcome:
    //   SUCCESS - success message seen AND Dashboard shown
    //   UNKNOWN - Dashboard shown but the message was not seen (it may have disappeared already)
    //   FAILED  - the form is still shown (the texts on screen are reported)
    // Read-only checks only. Submit is NEVER tapped again here.
    private void verifySubmission() {
        // 1. The success message - waited for explicitly, right away (the banner is short-lived)
        String messageShown = waitForSuccessMessage();

        // 2. The destination screen - the form must close and the Dashboard must appear
        boolean onDashboard;
        try {
            wait.until(ExpectedConditions.invisibilityOfElementLocated(getLocator("accident.title")));
            waitForElement("dashboard.report.button");
            onDashboard = true;
        } catch (RuntimeException e) {
            onDashboard = false;
        }

        if (messageShown != null && onDashboard) {
            System.out.println("SUBMISSION OUTCOME: SUCCESS - message '" + messageShown + "' shown and the Dashboard is displayed.");
            return;
        }
        if (onDashboard) {
            fail("SUBMISSION OUTCOME: UNKNOWN - the Dashboard is shown but the success message was not seen"
                    + " (it may have disappeared). Submit was tapped once - check the report in the admin/web app. Not re-submitted.");
        }
        fail("SUBMISSION OUTCOME: FAILED or UNKNOWN - the form did not close after Submit. Texts on screen: "
                + getAllScreenTexts() + ". Submit was tapped once - check the admin/web app. Not re-submitted.");
    }

    // Waits for the success message and returns its text, or null if it did not appear in time.
    // Uses expectedSubmissionMessage from dashboard.json if set, otherwise the observed banner locator.
    private String waitForSuccessMessage() {
        String expectedMessage = getString(accidentData, "expectedSubmissionMessage");
        if (expectedMessage.isEmpty()) {
            try {
                WebElement banner = wait.until(ExpectedConditions.presenceOfElementLocated(getLocator("accident.success.message")));
                return readTextSafely(banner, "accident.success.message");
            } catch (TimeoutException e) {
                return null;
            }
        }
        // A configured message: check the screen texts every half second until it appears or time runs out
        long endTime = System.currentTimeMillis() + ConfigReader.getInt("explicit.wait.seconds") * 1000L;
        while (System.currentTimeMillis() < endTime) {
            if (getAllScreenTexts().contains(expectedMessage)) {
                return expectedMessage;
            }
            try {
                Thread.sleep(500); // short pause between two read-only checks
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    // Reads an element's text; if the screen re-rendered, finds the element again (max 3 tries)
    private String readTextSafely(WebElement element, String locatorKey) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return element.getText().trim();
            } catch (StaleElementReferenceException e) {
                List<WebElement> again = driver.findElements(getLocator(locatorKey));
                if (again.isEmpty()) {
                    return "(text could not be read - element disappeared)";
                }
                element = again.get(0);
            }
        }
        return "(text could not be read - screen kept changing)";
    }

    // ---------- Test data for the end-to-end test ----------

    // Mandatory value: FAILS with the exact key name if it is empty (no guessing, no defaults)
    private String getMandatoryValue(String key) {
        String value = getString(accidentData, key);
        if (value.isEmpty()) {
            fail("Mandatory value 'accidentReporting." + key + "' is empty in " + TEST_DATA_FILE + ". Nothing was entered.");
        }
        return value;
    }

    private LocalDate parseMandatoryDate() {
        String value = getMandatoryValue("accidentDate");
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            fail("accidentReporting.accidentDate '" + value + "' must use the format YYYY-MM-DD.");
            return null;
        }
    }

    private LocalTime parseMandatoryTime() {
        String value = getMandatoryValue("accidentTime");
        try {
            return LocalTime.parse(value, DateTimeFormatter.ofPattern("HH:mm"));
        } catch (DateTimeParseException e) {
            fail("accidentReporting.accidentTime '" + value + "' must use the format HH:mm (24-hour).");
            return null;
        }
    }

    // The entries of one step list in dashboard.json (empty if the key is missing)
    private List<JsonObject> getFieldList(String stepKey) {
        List<JsonObject> fields = new ArrayList<>();
        if (accidentData.has(stepKey) && accidentData.get(stepKey).isJsonArray()) {
            for (JsonElement element : accidentData.getAsJsonArray(stepKey)) {
                fields.add(element.getAsJsonObject());
            }
        }
        return fields;
    }

    // Every visible text on screen (READ-ONLY, used for reports and messages).
    // The texts are found again on every try: if the screen changes while reading
    // (an element disappears), the whole read is repeated - at most 3 times.
    private List<String> getAllScreenTexts() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                List<String> texts = new ArrayList<>();
                for (WebElement text : driver.findElements(getLocator("screen.texts"))) {
                    String value = text.getText().trim();
                    if (!value.isEmpty()) {
                        texts.add(value);
                    }
                }
                return texts;
            } catch (StaleElementReferenceException e) {
                // The screen re-rendered while reading - read it again
            }
        }
        List<String> unreadable = new ArrayList<>();
        unreadable.add("(screen texts could not be read - the screen kept changing)");
        return unreadable;
    }

    private void waitForInvisible(String locatorKey) {
        try {
            wait.until(ExpectedConditions.invisibilityOfElementLocated(getLocator(locatorKey)));
        } catch (TimeoutException e) {
            throw new RuntimeException("Element did not disappear: '" + locatorKey + "'", e);
        }
    }

    private void hideKeyboard() {
        try {
            driver.hideKeyboard();
        } catch (Exception e) {
            // Keyboard was not open - nothing to do
        }
    }

    // ============================================================
    // Navigation
    // ============================================================

    // Dashboard -> Report -> Accident Reporting (step 1)
    public void openAccidentReport() {
        leaveAccidentReport();
        click("dashboard.tab");
        System.out.println("Opening Accident Reporting...");
        click("dashboard.report.button");
        waitForElement("accident.title");
    }

    // Leaves Accident Reporting with its back arrow (seen live: no confirmation, back to Dashboard)
    public void leaveAccidentReport() {
        if (isPresent("accident.title")) {
            System.out.println("Leaving Accident Reporting without saving...");
            click("accident.back.button");
            waitForElement("dashboard.report.button");
        }
    }

    // ============================================================
    // Dropdown overlay list
    // ============================================================

    // Reads every option, scrolling the list until it stops moving
    private List<String> readAllDropdownOptions() {
        List<String> options = new ArrayList<>();
        for (int scrollCount = 0; scrollCount <= MAX_SCROLLS; scrollCount++) {
            for (WebElement option : driver.findElements(getLocator("dropdown.option.texts"))) {
                String text = option.getText().trim();
                if (!text.isEmpty() && !options.contains(text)) {
                    options.add(text);
                }
            }
            if (!scrollDropdownList()) {
                break;
            }
        }
        return options;
    }

    // Scrolls the list down once. Returns false if the options did not move (end reached).
    private boolean scrollDropdownList() {
        String before = getOptionsSignature();
        Map<String, Object> params = new HashMap<>();
        params.put("elementId", getElementId(waitForElement("dropdown.list")));
        params.put("direction", "down");
        params.put("percent", 0.6);
        driver.executeScript("mobile: scrollGesture", params);
        return !getOptionsSignature().equals(before);
    }

    private String getOptionsSignature() {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                StringBuilder signature = new StringBuilder();
                for (WebElement option : driver.findElements(getLocator("dropdown.option.texts"))) {
                    signature.append(option.getText()).append("@").append(option.getRect().getY()).append("|");
                }
                return signature.toString();
            } catch (StaleElementReferenceException e) {
                // The list re-rendered while reading - read it again
            }
        }
        return "";
    }

    // Closes the overlay by tapping the dimmed background just below the list (verified live)
    private void closeDropdownWithoutSelecting() {
        Rectangle list = waitForElement("dropdown.list").getRect();
        int screenHeight = driver.manage().window().getSize().getHeight();
        int listBottom = list.getY() + list.getHeight();

        Map<String, Object> params = new HashMap<>();
        params.put("x", list.getX() + list.getWidth() / 2);
        params.put("y", listBottom + (screenHeight - listBottom) / 2);
        driver.executeScript("mobile: clickGesture", params);

        try {
            wait.until(ExpectedConditions.invisibilityOfElementLocated(getLocator("dropdown.list")));
        } catch (TimeoutException e) {
            fail("The dropdown list did not close.");
        }
    }

    // ============================================================
    // Date and time pickers
    // ============================================================

    // Clicks the month arrows until the calendar shows the wanted month
    private void goToMonth(YearMonth target) {
        DateTimeFormatter headerFormat = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);
        for (int clickCount = 0; clickCount <= MAX_MONTH_CLICKS; clickCount++) {
            String header = waitForElement("calendar.month.title").getText().trim();
            YearMonth shown = YearMonth.parse(header, headerFormat);
            if (shown.equals(target)) {
                return;
            }
            if (shown.isBefore(target)) {
                click("calendar.next.month");
            } else {
                click("calendar.previous.month");
            }
            // Wait until the header changes before reading it again
            wait.until(ExpectedConditions.not(
                    ExpectedConditions.textToBe(getLocator("calendar.month.title"), header)));
        }
        fail("Could not reach " + target + " in the calendar within " + MAX_MONTH_CLICKS + " clicks.");
    }

    // The day button for this date. The date is validated, so it only has digits and dashes.
    private By getCalendarDayLocator(LocalDate date) {
        String template = readLocatorText("calendar.day");
        return buildLocator("calendar.day", template.replace("{date}", date.toString()));
    }

    private int getHour12(LocalTime time) {
        int hour = time.getHour() % 12;
        if (hour == 0) {
            return 12;
        }
        return hour;
    }

    private String getAmPm(LocalTime time) {
        if (time.getHour() < 12) {
            return "AM";
        }
        return "PM";
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

    // accidentDate as yyyy-MM-dd. Skips the test if empty, fails if the format is wrong.
    private LocalDate getRequiredDate() {
        String value = getString(accidentData, "accidentDate");
        if (value.isEmpty()) {
            throw new TestAbortedException("SKIPPED: 'accidentDate' is empty in " + TEST_DATA_FILE);
        }
        try {
            return LocalDate.parse(value); // yyyy-MM-dd
        } catch (DateTimeParseException e) {
            fail("accidentDate '" + value + "' must use the format YYYY-MM-DD.");
            return null;
        }
    }

    // accidentTime as HH:mm (24-hour). Skips the test if empty, fails if the format is wrong.
    private LocalTime getRequiredTime() {
        String value = getString(accidentData, "accidentTime");
        if (value.isEmpty()) {
            throw new TestAbortedException("SKIPPED: 'accidentTime' is empty in " + TEST_DATA_FILE);
        }
        try {
            return LocalTime.parse(value, DateTimeFormatter.ofPattern("HH:mm"));
        } catch (DateTimeParseException e) {
            fail("accidentTime '" + value + "' must use the format HH:mm (24-hour), e.g. 14:30.");
            return null;
        }
    }
}
