package com.pacemax.automation.dashboard;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Dashboard - Assignment Details / Vehicle Inspection automation.
 *
 * Locators   -> locators/dashboard_inspection.properties
 * Test data  -> test-data/dashboard.json  (the "inspection" section)
 * Settings   -> config/config.properties
 *
 * SAFETY: nothing in this class submits an inspection.
 * answerQuestionsWithoutSubmitTest is @Disabled until it is separately approved.
 *
 * Run all enabled tests:  mvn test -Dtest=DashboardInspection
 * Run one test:           mvn test -Dtest=DashboardInspection#readInspectionCardsTest
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DashboardInspection {

    private static final String LOCATOR_FILE = "locators/dashboard_inspection.properties";
    private static final String TEST_DATA_FILE = "test-data/dashboard.json";
    private static final String SCREENSHOT_FOLDER = "target/screenshots";

    // Safety limits so swiping/scrolling can never loop forever
    private static final int MAX_SWIPES = 30;
    private static final int MAX_SCROLLS = 20;

    // How often polling loops check the screen again (milliseconds)
    private static final int POLL_INTERVAL_MS = 500;

    private static AndroidDriver driver;
    private static WebDriverWait wait;
    private static int waitSeconds;
    private static Properties locators;
    private static JsonObject inspectionData;

    // ============================================================
    // Setup and cleanup (runs once for the whole class)
    // ============================================================

    @BeforeAll
    static void setUp() {
        locators = loadLocators();
        inspectionData = loadTestData().getAsJsonObject("inspection");
        driver = DriverManager.startDriver();
        waitSeconds = ConfigReader.getInt("explicit.wait.seconds");
        wait = new WebDriverWait(driver, Duration.ofSeconds(waitSeconds));
    }

    @AfterAll
    static void tearDown() {
        DriverManager.quitDriver();
    }

    // ============================================================
    // Tests (none of them submit anything)
    // ============================================================

    @Test
    @Order(1)
    void dashboardLoadsTest() {
        try {
            goToDashboard();
            assertTrue(isPresent("dashboard.report.button"), "The accident 'Report' button is not shown on the Dashboard.");

            if (isPresent("card.section.label")) {
                System.out.println("PASS: Dashboard shows the Assignment Details card.");
            } else if (isPresent("dashboard.no.assignment.message")) {
                System.out.println("PASS: Dashboard shows 'No vehicle assigned' (no inspection assigned).");
            } else {
                fail("Dashboard shows neither an Assignment Details card nor 'No vehicle assigned'.");
            }
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("dashboardLoadsTest");
            throw error;
        }
    }

    @Test
    @Order(2)
    @Disabled("Replaced by completeInspectionSubmitTest: swipes through all cards and opens/leaves the form on its own. Kept for on-demand runs.")
    void readInspectionCardsTest() {
        try {
            goToDashboard();
            List<String[]> cards = readAllCards();

            System.out.println("Assigned inspections found: " + cards.size());
            for (int i = 0; i < cards.size(); i++) {
                System.out.println("  " + (i + 1) + ". " + cards.get(i)[0] + " - " + cards.get(i)[1]);
            }

            // Compare only if an expected count is configured (null = not configured)
            JsonElement expected = inspectionData.get("expectedInspectionCount");
            if (expected == null || expected.isJsonNull()) {
                System.out.println("expectedInspectionCount is not set - count reported, not compared.");
            } else {
                assertEquals(expected.getAsInt(), cards.size(), "Number of assigned inspections");
                System.out.println("PASS: inspection count matches " + expected.getAsInt());
            }
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("readInspectionCardsTest");
            throw error;
        }
    }

    @Test
    @Order(3)
    @Disabled("Replaced by completeInspectionSubmitTest: swipes through all cards and opens/leaves the form on its own. Kept for on-demand runs.")
    void openTargetInspectionTest() {
        String vehicleName = getRequiredValue("targetVehicleName");
        try {
            goToDashboard();
            openTargetInspection();

            assertTrue(getFormHeaderTexts().contains(vehicleName),
                    "The inspection form header does not show the vehicle '" + vehicleName + "'.");
            System.out.println("PASS: inspection form opened for '" + vehicleName + "'.");
            System.out.println("Questions remaining: " + getRemainingCount());
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("openTargetInspectionTest");
            throw error;
        } finally {
            leaveInspectionForm();
        }
    }

    @Test
    @Order(4)
    @Disabled("Replaced by completeInspectionSubmitTest: swipes through all cards and opens/leaves the form on its own. Kept for on-demand runs.")
    void readInspectionQuestionsTest() {
        getRequiredValue("targetVehicleName");
        try {
            goToDashboard();
            openTargetInspection();

            List<String> labels = readAllQuestionLabels();
            System.out.println("Questions on the form (" + labels.size() + "):");
            for (int i = 0; i < labels.size(); i++) {
                String type = detectQuestionType(labels.get(i));
                System.out.println("  " + (i + 1) + ". " + labels.get(i) + "  [" + type + "]");
            }

            // Compare with the configured questions (only if any are configured)
            List<String> problems = new ArrayList<>();
            List<String> configuredLabels = new ArrayList<>();
            for (JsonObject question : getConfiguredQuestions()) {
                String label = getString(question, "questionLabel");
                configuredLabels.add(label);
                if (!labels.contains(label) && !isTextInputLabel(label)) {
                    problems.add("configured question not found on the form: '" + label + "'");
                }
            }
            for (String label : labels) {
                if (!configuredLabels.contains(label)) {
                    System.out.println("NOTE: no answer configured for question '" + label + "'");
                }
            }
            if (!problems.isEmpty()) {
                fail("Question mismatch: " + problems);
            }
            System.out.println("PASS: questions read (nothing was answered).");
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("readInspectionQuestionsTest");
            throw error;
        } finally {
            leaveInspectionForm();
        }
    }

    // Answers the configured questions and leaves WITHOUT submitting.
    // Verified once live: an unsubmitted Yes answer was discarded after leaving the form.
    // Still requires separate approval because answers might be kept as drafts.
    @Test
    @Order(5)
    @Disabled("Needs separate approval before running: answers may persist as drafts. Never submits.")
    void answerQuestionsWithoutSubmitTest() {
        getRequiredValue("targetVehicleName");
        List<JsonObject> questions = getConfiguredQuestions();
        assumeTrue(!questions.isEmpty(), "SKIPPED: no questions configured in " + TEST_DATA_FILE);

        List<String> problems = new ArrayList<>();
        try {
            goToDashboard();
            openTargetInspection();

            for (JsonObject question : questions) {
                String label = getString(question, "questionLabel");
                String type = getString(question, "type");
                String response = getString(question, "response");

                // Never answer with an empty value
                if (label.isEmpty() || response.isEmpty()) {
                    System.out.println("SKIPPED question '" + label + "': questionLabel or response is empty.");
                    continue;
                }

                System.out.println("---- Answering '" + label + "' (" + type + ") with '" + response + "'");
                try {
                    if (type.equals("yesNo")) {
                        answerYesNo(label, response);
                    } else if (type.equals("dropdown")) {
                        answerDropdown(label, response);
                    } else if (type.equals("text")) {
                        answerText(label, response);
                    } else if (type.equals("photo")) {
                        System.out.println("SKIPPED photo question '" + label + "': photo capture is not automated yet.");
                    } else {
                        problems.add("'" + label + "': unknown type '" + type + "' (use yesNo, dropdown, text or photo)");
                    }
                } catch (RuntimeException | AssertionError error) {
                    problems.add("'" + label + "': " + error.getMessage());
                }
            }

            if (!problems.isEmpty()) {
                fail("Some questions could not be answered: " + problems);
            }
            System.out.println("PASS: configured questions answered. The form was NOT submitted.");
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("answerQuestionsWithoutSubmitTest");
            throw error;
        } finally {
            // Leave without submitting
            leaveInspectionForm();
        }
    }

    // ============================================================
    // END-TO-END (PRODUCTION): find the card with minimal swiping, open it ONCE, answer ONLY the
    // configured questions, tap Submit ONCE, then check the result and the status.
    // Runs ONLY when explicitly switched on:
    //   mvn test "-Dtest=DashboardInspection#completeInspectionSubmitTest" -Dinspection.submit.approved=true
    // ============================================================

    @Test
    @Order(10)
    @EnabledIfSystemProperty(named = "inspection.submit.approved", matches = "true",
            disabledReason = "SKIPPED on purpose: this test submits a real inspection. Add -Dinspection.submit.approved=true to run it.")
    void completeInspectionSubmitTest() {
        String vehicleName = getRequiredValue("targetVehicleName");
        String inspectionName = getString(inspectionData, "targetInspectionName");
        List<JsonObject> questions = getConfiguredQuestions();
        if (questions.isEmpty()) {
            throw new TestAbortedException("SKIPPED: no questions configured in " + TEST_DATA_FILE + " - nothing to answer, nothing submitted.");
        }

        List<String> answered = new ArrayList<>();
        List<String> notAnswered = new ArrayList<>();
        boolean submitTapped = false;
        try {
            // 1. Dashboard once, card with as few swipes as possible, form opened once
            goToDashboard();
            showTargetCard(vehicleName, inspectionName);
            System.out.println("Opening inspection '" + getFirstText("card.inspection.name") + "' for '" + vehicleName + "'...");
            click("card.inspect.button");
            waitForElement("form.title");
            assertTrue(getFormHeaderTexts().contains(vehicleName), "The opened form is not for '" + vehicleName + "'.");

            // 2. Answer ONLY the configured questions (by their exact label, never by position)
            List<String> configuredLabels = new ArrayList<>();
            for (JsonObject question : questions) {
                String label = getString(question, "questionLabel");
                String type = getString(question, "type");
                String response = getString(question, "response");
                configuredLabels.add(label);

                if (label.isEmpty() || response.isEmpty()) {
                    fail("A configured question has an empty questionLabel or response (label='" + label + "'). Nothing was submitted.");
                }
                if (type.equals("yesNo")) {
                    answerYesNo(label, response);
                } else if (type.equals("dropdown")) {
                    answerDropdown(label, response);
                } else if (type.equals("text")) {
                    answerText(label, response);
                } else if (type.equals("photo")) {
                    // Photo capture/upload is not automated - the question is left unanswered
                    notAnswered.add(label + " (photo - not automated)");
                    continue;
                } else {
                    fail("Question '" + label + "' has unknown type '" + type + "'. Nothing was submitted.");
                }
                answered.add(label + " = " + response);
            }

            // 3. Optional questions that are not configured stay unanswered
            for (String label : readAllQuestionLabels()) {
                if (!configuredLabels.contains(label)) {
                    notAnswered.add(label + " (not configured)");
                }
            }
            printInspectionSummary(vehicleName, inspectionName, answered, notAnswered, "(not submitted yet)");

            // 4. Submit ONCE
            WebElement submit = scrollToSubmit();
            System.out.println("Tapping Submit (once)...");
            submitTapped = true; // from here on Submit is never tapped again
            submit.click();

            // 5. Result and status
            String status = verifyInspectionSubmission(vehicleName, inspectionName, answered.size());
            printInspectionSummary(vehicleName, inspectionName, answered, notAnswered, status);
        } catch (TestAbortedException skipped) {
            throw skipped;
        } catch (RuntimeException | AssertionError error) {
            System.out.println(submitTapped
                    ? "Submit WAS tapped once - it will NOT be tapped again."
                    : "Submit was NOT tapped - no inspection was submitted.");
            takeScreenshot("completeInspectionSubmitTest");
            throw error;
        } finally {
            // Leaving before Submit discards the answers (seen live: they are not kept)
            if (!submitTapped) {
                leaveInspectionForm();
            }
        }
    }

    // Shows the card for this vehicle (+ inspection name, if set) with as few swipes as possible:
    // checks the visible card, then swipes forward, then backward - stopping at the first match.
    private void showTargetCard(String vehicleName, String inspectionName) {
        if (!isPresent("card.scroller")) {
            fail("No inspection card is shown on the Dashboard (no inspection assigned).");
        }
        if (visibleCardMatches(vehicleName, inspectionName)) {
            System.out.println("Target card already shown - no swiping needed.");
            return;
        }
        String[] directions = {"left", "right"}; // left = next card, right = previous card
        for (String direction : directions) {
            for (int swipeCount = 0; swipeCount < MAX_SWIPES; swipeCount++) {
                String before = getCardSignature();
                swipeCards(direction);
                if (getCardSignature().equals(before)) {
                    break; // end of the cards in this direction
                }
                if (visibleCardMatches(vehicleName, inspectionName)) {
                    System.out.println("Target card found after swiping " + direction + ".");
                    return;
                }
            }
        }
        fail("No inspection card for vehicle '" + vehicleName + "'"
                + (inspectionName.isEmpty() ? "" : " and inspection '" + inspectionName + "'") + ".");
    }

    private boolean visibleCardMatches(String vehicleName, String inspectionName) {
        boolean vehicleMatches = getFirstText("card.vehicle.name").equals(vehicleName);
        boolean nameMatches = inspectionName.isEmpty() || getFirstText("card.inspection.name").equals(inspectionName);
        return vehicleMatches && nameMatches;
    }

    // The Submit button, scrolling the form down if it is not visible yet
    private WebElement scrollToSubmit() {
        for (int scrollCount = 0; scrollCount <= MAX_SCROLLS; scrollCount++) {
            List<WebElement> found = driver.findElements(getLocator("form.submit.button"));
            if (!found.isEmpty()) {
                return found.get(0);
            }
            if (!scrollForm("down")) {
                break;
            }
        }
        fail("Submit button not found on the inspection form. Nothing was submitted.");
        return null;
    }

    // Read-only checks after Submit. Returns the status that was confirmed.
    //   - form still open                    -> FAILED with the texts shown (e.g. validation)
    //   - form closed, answers saved         -> CONFIRMED (status derived from the saved progress)
    //   - form closed, progress not readable -> UNKNOWN (never reported as passed)
    //   - fewer answers saved than entered   -> NOT CONFIRMED
    private String verifyInspectionSubmission(String vehicleName, String inspectionName, int answeredCount) {
        // Messages right after Submit (screen texts + toast), read safely while the screen changes
        List<String> textsAfterSubmit = getAllScreenTextsSafely();
        textsAfterSubmit.addAll(getToastTextsSafely());
        System.out.println("Texts right after Submit: " + textsAfterSubmit);

        boolean formClosed;
        try {
            wait.until(ExpectedConditions.invisibilityOfElementLocated(getLocator("form.title")));
            formClosed = true;
        } catch (TimeoutException e) {
            formClosed = false;
        }
        if (!formClosed) {
            fail("SUBMISSION OUTCOME: FAILED - the form is still open after Submit (the app may have rejected it)."
                    + " Texts on screen: " + getAllScreenTextsSafely() + ". Submit was tapped once - not re-submitted.");
        }

        String expectedMessage = getString(inspectionData, "expectedSubmissionMessage");
        if (!expectedMessage.isEmpty()) {
            assertTrue(textsAfterSubmit.contains(expectedMessage) || getAllScreenTextsSafely().contains(expectedMessage),
                    "SUBMISSION OUTCOME: UNKNOWN - the form closed but the success message '" + expectedMessage + "' was not seen.");
        }

        // Status. The driver app shows NO status label anywhere (seen live, before and after a real
        // submission on 2026-10-13). Evidence used instead, also seen live on 2026-10-13:
        //   - before Submit, leaving the form discards all answers (reopened form: 00/06)
        //   - after Submit, the reopened form KEEPS the answers (it showed 04/06, "02 remaining")
        // So the status is derived from the SAVED progress of the reopened inspection:
        //   some answered + some remaining = "In Progress", none remaining = "Completed".
        String expectedStatus = getString(inspectionData, "expectedStatusAfterSubmit");
        if (expectedStatus.isEmpty()) {
            fail("SUBMISSION OUTCOME: UNKNOWN - the form closed, but 'inspection.expectedStatusAfterSubmit' is empty, so the status cannot be checked.");
        }

        int[] progress = reopenAndReadSavedProgress(vehicleName, inspectionName); // {answered, total}
        if (progress == null) {
            fail("SUBMISSION OUTCOME: UNKNOWN - the form closed after Submit, but the inspection could not be reopened"
                    + " to read its saved progress. Check it in the admin/web app. Not re-submitted.");
        }
        int savedAnswers = progress[0];
        int totalQuestions = progress[1];
        System.out.println("Saved progress after Submit: " + savedAnswers + "/" + totalQuestions);

        if (savedAnswers < answeredCount) {
            fail("SUBMISSION OUTCOME: NOT CONFIRMED - only " + savedAnswers + " answer(s) are saved, but " + answeredCount
                    + " were entered before Submit. Check it in the admin/web app. Not re-submitted.");
        }
        String derivedStatus;
        if (savedAnswers >= totalQuestions) {
            derivedStatus = "Completed";
        } else if (savedAnswers > 0) {
            derivedStatus = "In Progress";
        } else {
            derivedStatus = "Not Started";
        }
        assertEquals(expectedStatus, derivedStatus, "Status derived from the saved progress " + savedAnswers + "/" + totalQuestions);
        System.out.println("SUBMISSION OUTCOME: CONFIRMED - answers saved (" + savedAnswers + "/" + totalQuestions
                + "), status '" + derivedStatus + "'.");
        return derivedStatus + " (derived from saved progress " + savedAnswers + "/" + totalQuestions + ")";
    }

    // READ-ONLY: shows the inspection's card again, opens it, reads the "answered/total" counter
    // (e.g. "04/06"), and leaves with the back arrow WITHOUT submitting. Returns {answered, total},
    // or null if the card or the counter could not be found.
    private int[] reopenAndReadSavedProgress(String vehicleName, String inspectionName) {
        try {
            waitForElement("dashboard.report.button");
            showTargetCard(vehicleName, inspectionName);
            click("card.inspect.button");
            waitForElement("form.title");
            for (String text : getFormHeaderTexts()) {
                // The counter looks like "04/06" (answered/total)
                if (text.matches("\\d+/\\d+")) {
                    String[] parts = text.split("/");
                    return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
                }
            }
            return null;
        } catch (RuntimeException | AssertionError e) {
            System.out.println("Could not reopen the inspection: " + e.getMessage());
            return null;
        } finally {
            leaveInspectionForm(); // back arrow only - Submit is never tapped here
        }
    }

    private void printInspectionSummary(String vehicleName, String inspectionName,
                                        List<String> answered, List<String> notAnswered, String status) {
        System.out.println();
        System.out.println("Inspection Summary");
        System.out.println("------------------");
        System.out.println("Vehicle: " + vehicleName);
        System.out.println("Inspection: " + (inspectionName.isEmpty() ? "(not configured)" : inspectionName));
        System.out.println("Answered: " + answered);
        System.out.println("Left unanswered (optional): " + notAnswered);
        System.out.println("Status: " + status);
        System.out.println();
    }

    // Every visible text on screen (READ-ONLY). Found again on every try; if the screen changes
    // while reading, the whole read is repeated - at most 3 times.
    private List<String> getAllScreenTextsSafely() {
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

    // PENDING (not seen live): Android toast messages, if the app uses them
    private List<String> getToastTextsSafely() {
        List<String> texts = new ArrayList<>();
        try {
            for (WebElement toast : driver.findElements(getLocator("toast.message"))) {
                texts.add(toast.getText().trim());
            }
        } catch (StaleElementReferenceException e) {
            // The toast disappeared while reading - nothing to report
        }
        return texts;
    }

    // ============================================================
    // Dashboard and cards
    // ============================================================

    // Makes sure the Dashboard is shown (leaves the inspection form first if it is open)
    public void goToDashboard() {
        leaveInspectionForm();
        click("dashboard.tab");
        waitForElement("dashboard.report.button");
    }

    // Reads every inspection card by swiping through them.
    // Each entry is {inspectionName, vehicleName}. Empty list = no inspection assigned.
    public List<String[]> readAllCards() {
        List<String[]> cards = new ArrayList<>();
        if (!isPresent("card.scroller")) {
            return cards;
        }

        goToFirstCard();
        for (int swipeCount = 0; swipeCount <= MAX_SWIPES; swipeCount++) {
            String[] card = new String[] {getFirstText("card.inspection.name"), getFirstText("card.vehicle.name")};
            cards.add(card);

            // Swipe to the next card; if nothing changes, this was the last card
            String before = getCardSignature();
            swipeCards("left");
            if (getCardSignature().equals(before)) {
                return cards;
            }
        }
        System.out.println("Stopped after " + MAX_SWIPES + " swipes (safety limit).");
        return cards;
    }

    // Finds the ONE card matching targetVehicleName (and targetInspectionName if set) and opens it
    public void openTargetInspection() {
        String vehicleName = getRequiredValue("targetVehicleName");
        String inspectionName = getString(inspectionData, "targetInspectionName");

        List<String[]> cards = readAllCards();
        int matchCount = 0;
        int matchPosition = -1;
        for (int i = 0; i < cards.size(); i++) {
            boolean vehicleMatches = cards.get(i)[1].equals(vehicleName);
            boolean nameMatches = inspectionName.isEmpty() || cards.get(i)[0].equals(inspectionName);
            if (vehicleMatches && nameMatches) {
                matchCount++;
                matchPosition = i;
            }
        }

        if (matchCount == 0) {
            fail("No inspection card for vehicle '" + vehicleName + "'"
                    + (inspectionName.isEmpty() ? "" : " and inspection '" + inspectionName + "'") + ".");
        }
        if (matchCount > 1) {
            fail(matchCount + " inspection cards match vehicle '" + vehicleName
                    + "'. Set targetInspectionName in " + TEST_DATA_FILE + " to choose one.");
        }

        // Swipe from the first card to the matching one
        goToFirstCard();
        for (int i = 0; i < matchPosition; i++) {
            swipeCards("left");
        }
        String visibleVehicle = getFirstText("card.vehicle.name");
        if (!visibleVehicle.equals(vehicleName)) {
            fail("Expected the card for '" + vehicleName + "' but '" + visibleVehicle + "' is shown. Not opening it.");
        }

        System.out.println("Opening inspection '" + cards.get(matchPosition)[0] + "' for '" + vehicleName + "'...");
        click("card.inspect.button");
        waitForElement("form.title");
    }

    // Swipes right until the card no longer changes (= first card)
    private void goToFirstCard() {
        for (int swipeCount = 0; swipeCount < MAX_SWIPES; swipeCount++) {
            String before = getCardSignature();
            swipeCards("right");
            if (getCardSignature().equals(before)) {
                return;
            }
        }
    }

    // Text that identifies the visible card (inspection name + vehicle name)
    private String getCardSignature() {
        return getFirstText("card.inspection.name") + " | " + getFirstText("card.vehicle.name");
    }

    // Swipes the card area. "left" = next card, "right" = previous card.
    private void swipeCards(String direction) {
        WebElement scroller = waitForElement("card.scroller");
        Map<String, Object> params = new HashMap<>();
        params.put("elementId", getElementId(scroller));
        params.put("direction", direction);
        params.put("percent", 0.75);
        driver.executeScript("mobile: swipeGesture", params);
        pause(POLL_INTERVAL_MS); // let the card finish snapping into place
    }

    // ============================================================
    // Inspection form - reading
    // ============================================================

    // Leaves the inspection form with its back arrow (WITHOUT submitting), if it is open
    public void leaveInspectionForm() {
        if (isPresent("form.title")) {
            System.out.println("Leaving the inspection form without submitting...");
            click("form.back.button");
            waitForElement("dashboard.report.button");
        }
    }

    // Texts in the form header (vehicle name, driver name, "NN remaining", "NN/NN")
    public List<String> getFormHeaderTexts() {
        List<String> texts = new ArrayList<>();
        for (WebElement text : driver.findElements(getLocator("form.header.texts"))) {
            texts.add(text.getText().trim());
        }
        return texts;
    }

    // Number in "06 remaining" (goes down by 1 for each answered question)
    public int getRemainingCount() {
        String text = waitForElement("form.remaining.text").getText().trim();
        String number = text.substring(0, text.indexOf(' '));
        return Integer.parseInt(number);
    }

    // Reads every question label, scrolling through the whole form
    public List<String> readAllQuestionLabels() {
        List<String> labels = new ArrayList<>();
        scrollFormToTop();
        for (int scrollCount = 0; scrollCount <= MAX_SCROLLS; scrollCount++) {
            for (WebElement label : driver.findElements(getLocator("form.question.labels"))) {
                String text = label.getText().trim();
                if (!text.isEmpty() && !labels.contains(text)) {
                    labels.add(text);
                }
            }
            if (!scrollForm("down")) {
                break;
            }
        }
        scrollFormToTop();
        return labels;
    }

    // Decides the type of a question from the controls under its label.
    // Order matters: a "Yes" answer can add an Upload Photo under a Yes/No question (seen live).
    public String detectQuestionType(String labelText) {
        WebElement label = scrollToQuestionLabel(labelText);
        int[] region = getQuestionRegion(label);

        boolean hasYes = !findControlsInRegion("form.yes.buttons", region).isEmpty();
        boolean hasNo = !findControlsInRegion("form.no.buttons", region).isEmpty();
        if (hasYes && hasNo) {
            return "yesNo";
        }
        if (!findControlsInRegion("form.dropdown.boxes", region).isEmpty()) {
            return "dropdown";
        }
        if (!findControlsInRegion("form.photo.buttons", region).isEmpty()) {
            return "photo";
        }
        if (!findControlsInRegion("form.text.inputs", region).isEmpty()) {
            return "text";
        }
        return "unknown";
    }

    // ============================================================
    // Inspection form - answering (used only by the @Disabled test)
    // ============================================================

    // Taps Yes or No UNDER the given question label, then checks the counter went down
    public void answerYesNo(String labelText, String response) {
        if (!response.equals("Yes") && !response.equals("No")) {
            fail("Yes/No answer must be exactly 'Yes' or 'No', not '" + response + "'.");
        }
        WebElement label = scrollToQuestionLabel(labelText);
        int[] region = getQuestionRegion(label);

        String optionsKey = response.equals("Yes") ? "form.yes.buttons" : "form.no.buttons";
        List<WebElement> options = findControlsInRegion(optionsKey, region);
        if (options.size() != 1) {
            fail("Found " + options.size() + " '" + response + "' buttons under '" + labelText + "' (need exactly 1). Not answered.");
        }

        int remainingBefore = getRemainingCount();
        options.get(0).click();
        waitForRemainingCount(remainingBefore - 1, labelText);
        System.out.println("PASS: '" + labelText + "' answered '" + response + "' (remaining " + (remainingBefore - 1) + ").");
    }

    // Opens the dropdown UNDER the label, picks the exact option, checks the counter went down
    public void answerDropdown(String labelText, String response) {
        WebElement label = scrollToQuestionLabel(labelText);
        int[] region = getQuestionRegion(label);

        List<WebElement> boxes = findControlsInRegion("form.dropdown.boxes", region);
        if (boxes.size() != 1) {
            fail("Found " + boxes.size() + " dropdowns under '" + labelText + "' (need exactly 1). Not answered.");
        }

        int remainingBefore = getRemainingCount();
        boxes.get(0).click();
        waitForElement("dropdown.list");

        WebElement option = findDropdownOption(response);
        if (option == null) {
            List<String> available = readDropdownOptions();
            closeDropdownWithoutSelecting();
            fail("Option '" + response + "' is not in the '" + labelText + "' dropdown. Available: " + available);
        }
        option.click();
        waitForRemainingCount(remainingBefore - 1, labelText);

        // PENDING: how the chosen value is displayed in the box was not seen yet - reported only
        String shown = "";
        List<WebElement> boxAfter = findControlsInRegion("form.dropdown.boxes", getQuestionRegion(scrollToQuestionLabel(labelText)));
        if (boxAfter.size() == 1) {
            for (WebElement text : boxAfter.get(0).findElements(getLocator("form.dropdown.box.texts"))) {
                shown = shown + text.getText().trim();
            }
        }
        System.out.println("PASS: '" + labelText + "' answered '" + response + "' (box shows: '" + shown + "').");
    }

    // Types into the text box under the label (or the box whose placeholder IS the label,
    // like "Additional Comments"), then checks the box contains the text
    public void answerText(String labelText, String response) {
        WebElement field = null;

        if (findQuestionLabels(labelText).size() == 1) {
            List<WebElement> fields = findControlsInRegion("form.text.inputs", getQuestionRegion(scrollToQuestionLabel(labelText)));
            if (fields.size() == 1) {
                field = fields.get(0);
            }
        }
        if (field == null) {
            field = scrollToTextInputWithPlaceholder(labelText);
        }
        if (field == null) {
            fail("No text box found for '" + labelText + "'. Not answered.");
        }

        field.click();
        field.sendKeys(response);
        hideKeyboard();
        assertEquals(response, field.getText().trim(), "Text in '" + labelText + "'");
        System.out.println("PASS: '" + labelText + "' text entered.");
    }

    // True if a text box on the form uses this text as its placeholder (e.g. "Additional Comments")
    private boolean isTextInputLabel(String labelText) {
        return scrollToTextInputWithPlaceholder(labelText) != null;
    }

    private WebElement scrollToTextInputWithPlaceholder(String placeholder) {
        scrollFormToTop();
        for (int scrollCount = 0; scrollCount <= MAX_SCROLLS; scrollCount++) {
            for (WebElement field : driver.findElements(getLocator("form.text.inputs"))) {
                if (field.getText().trim().equals(placeholder)) {
                    return field;
                }
            }
            if (!scrollForm("down")) {
                return null;
            }
        }
        return null;
    }

    // Checks every half second until "NN remaining" shows the expected number
    private void waitForRemainingCount(int expected, String labelText) {
        long endTime = System.currentTimeMillis() + waitSeconds * 1000L;
        while (System.currentTimeMillis() < endTime) {
            if (getRemainingCount() == expected) {
                return;
            }
            pause(POLL_INTERVAL_MS);
        }
        fail("After answering '" + labelText + "' the remaining count did not change to " + expected
                + " (it may already have been answered).");
    }

    // ============================================================
    // Matching controls to questions (the form is flat)
    // ============================================================

    // Visible question labels with exactly this text
    private List<WebElement> findQuestionLabels(String labelText) {
        List<WebElement> matches = new ArrayList<>();
        for (WebElement label : driver.findElements(getLocator("form.question.labels"))) {
            if (label.getText().trim().equals(labelText)) {
                matches.add(label);
            }
        }
        return matches;
    }

    // Scrolls the form until the label is on screen with room below it for its controls
    private WebElement scrollToQuestionLabel(String labelText) {
        scrollFormToTop();
        int screenHeight = driver.manage().window().getSize().getHeight();
        for (int scrollCount = 0; scrollCount <= MAX_SCROLLS; scrollCount++) {
            List<WebElement> matches = findQuestionLabels(labelText);
            if (matches.size() > 1) {
                fail(matches.size() + " questions are labelled '" + labelText + "'. Cannot tell them apart.");
            }
            // Use it only if it is in the upper 70% of the screen, so its controls are visible too
            if (matches.size() == 1 && matches.get(0).getRect().getY() < screenHeight * 7 / 10) {
                return matches.get(0);
            }
            if (!scrollForm("down")) {
                if (matches.size() == 1) {
                    return matches.get(0); // end of the form - use it where it is
                }
                break;
            }
        }
        fail("Question '" + labelText + "' was not found on the inspection form.");
        return null;
    }

    // The screen area that belongs to a question: from its label down to the next label
    // (or the Submit button / the bottom of the screen). Returns {top, bottom}.
    private int[] getQuestionRegion(WebElement label) {
        int top = label.getRect().getY();
        int bottom = driver.manage().window().getSize().getHeight();

        for (WebElement other : driver.findElements(getLocator("form.question.labels"))) {
            int otherTop = other.getRect().getY();
            if (otherTop > top && otherTop < bottom) {
                bottom = otherTop;
            }
        }
        for (WebElement submit : driver.findElements(getLocator("form.submit.button"))) {
            int submitTop = submit.getRect().getY();
            if (submitTop > top && submitTop < bottom) {
                bottom = submitTop;
            }
        }
        return new int[] {top, bottom};
    }

    // Controls (from the locator key) whose vertical centre lies inside the region
    private List<WebElement> findControlsInRegion(String controlsKey, int[] region) {
        List<WebElement> inside = new ArrayList<>();
        for (WebElement control : driver.findElements(getLocator(controlsKey))) {
            Rectangle box = control.getRect();
            int centerY = box.getY() + box.getHeight() / 2;
            if (centerY >= region[0] && centerY < region[1]) {
                inside.add(control);
            }
        }
        return inside;
    }

    // ============================================================
    // Dropdown overlay list
    // ============================================================

    // Finds the option with exactly this text, scrolling the list if needed. Null if not found.
    private WebElement findDropdownOption(String optionText) {
        for (int scrollCount = 0; scrollCount <= MAX_SCROLLS; scrollCount++) {
            for (WebElement option : driver.findElements(getLocator("dropdown.option.texts"))) {
                if (option.getText().trim().equals(optionText)) {
                    return option;
                }
            }
            if (!scrollElement("dropdown.list", "dropdown.option.texts", "down")) {
                return null;
            }
        }
        return null;
    }

    private List<String> readDropdownOptions() {
        List<String> options = new ArrayList<>();
        for (WebElement option : driver.findElements(getLocator("dropdown.option.texts"))) {
            options.add(option.getText().trim());
        }
        return options;
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
            System.out.println("WARNING: the dropdown list did not close.");
        }
    }

    // ============================================================
    // Scrolling the form
    // Every loop stops when the item is found, when a scroll changes nothing (end),
    // or after MAX_SCROLLS scrolls (safety limit).
    // ============================================================

    private boolean scrollForm(String direction) {
        return scrollElement("form.scroll.container", "form.question.labels", direction);
    }

    private void scrollFormToTop() {
        for (int scrollCount = 0; scrollCount < MAX_SCROLLS; scrollCount++) {
            if (!scrollForm("up")) {
                return;
            }
        }
    }

    // Scrolls inside the container. Returns false if the items did not move (end reached).
    private boolean scrollElement(String containerKey, String itemsKey, String direction) {
        List<WebElement> containers = driver.findElements(getLocator(containerKey));
        if (containers.isEmpty()) {
            return false;
        }
        String before = getPositionsSignature(itemsKey);

        Map<String, Object> params = new HashMap<>();
        params.put("elementId", getElementId(containers.get(0)));
        params.put("direction", direction);
        params.put("percent", 0.6);
        driver.executeScript("mobile: scrollGesture", params);

        return !getPositionsSignature(itemsKey).equals(before);
    }

    // Texts and positions of the items - if unchanged after a scroll, nothing moved
    private String getPositionsSignature(String itemsKey) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                StringBuilder signature = new StringBuilder();
                for (WebElement item : driver.findElements(getLocator(itemsKey))) {
                    signature.append(item.getText()).append("@").append(item.getRect().getY()).append("|");
                }
                return signature.toString();
            } catch (StaleElementReferenceException e) {
                // The screen re-rendered while reading - read it again
            }
        }
        return "";
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

    // Text of the first matching element, or "" if there is none
    private String getFirstText(String locatorKey) {
        List<WebElement> elements = driver.findElements(getLocator(locatorKey));
        if (elements.isEmpty()) {
            return "";
        }
        return elements.get(0).getText().trim();
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

    // Short pause used only between two checks of the screen / after a swipe
    private void pause(int milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Converts "type:value" from the locator file into a Selenium/Appium By
    private By getLocator(String locatorKey) {
        String locator = locators.getProperty(locatorKey);
        if (locator == null) {
            throw new RuntimeException("Locator key not found in " + LOCATOR_FILE + ": " + locatorKey);
        }
        locator = locator.trim();
        int separator = locator.indexOf(':');
        String type = locator.substring(0, separator).trim();
        String value = locator.substring(separator + 1).trim();

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

    // Returns a required value from the "inspection" section, or SKIPS the test if it is empty
    private String getRequiredValue(String key) {
        String value = getString(inspectionData, key);
        if (value.isEmpty()) {
            throw new TestAbortedException("SKIPPED: '" + key + "' is empty in " + TEST_DATA_FILE + " (inspection section).");
        }
        return value;
    }

    // The configured questions: [{"questionLabel": "", "type": "", "response": ""}, ...]
    private List<JsonObject> getConfiguredQuestions() {
        List<JsonObject> questions = new ArrayList<>();
        if (inspectionData.has("questions")) {
            JsonArray array = inspectionData.getAsJsonArray("questions");
            for (int i = 0; i < array.size(); i++) {
                questions.add(array.get(i).getAsJsonObject());
            }
        }
        return questions;
    }
}
