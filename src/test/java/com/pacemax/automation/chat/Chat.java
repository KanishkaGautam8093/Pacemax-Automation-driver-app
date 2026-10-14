package com.pacemax.automation.chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pacemax.automation.utils.ConfigReader;
import com.pacemax.automation.utils.DriverManager;
import io.appium.java_client.AppiumBy;
import io.appium.java_client.android.AndroidDriver;
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
import io.appium.java_client.remote.AppiumWebElement;
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
 * Chat tab automation for the Pacemax Driver App.
 *
 * Locators   -> locators/chat.properties
 * Test data  -> test-data/chat.json
 * Settings   -> config/config.properties
 *
 * How elements are matched:
 *   Locators never contain a chat name, message or category. Java finds the elements
 *   with fixed locators, reads their text, and compares it with the test data.
 *   This way names/messages with apostrophes or other special characters are safe.
 *
 * IMPORTANT: some tests CHANGE data (send a message, pin/unpin chats, change filters).
 * Each of those tests is SKIPPED when its test data in chat.json is empty,
 * and pin/unpin tests are SKIPPED while their locators are still PENDING.
 *
 * Run all Chat tests:  mvn test -Dtest=Chat
 * Run one test:        mvn test -Dtest=Chat#openChatTabTest
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Chat {

    private static final String LOCATOR_FILE = "locators/chat.properties";
    private static final String TEST_DATA_FILE = "test-data/chat.json";
    private static final String SCREENSHOT_FOLDER = "target/screenshots";

    // Scrolling always stops at the end of the list, or after this many scrolls at most
    private static final int MAX_SCROLLS = 30;

    // How far a row is swiped to show Pin/Unpin (0.5 = half the row width).
    // PENDING: confirm the right distance on the live app.
    private static final double SWIPE_PERCENT = 0.5;

    // How often polling loops check the screen again (milliseconds)
    private static final int POLL_INTERVAL_MS = 500;

    // True after this test run scrolled the chat list down. Scrolling UP is only done when
    // this is true, so the list is never "pulled down" at the top (which can reload it).
    private static boolean listScrolledDown = false;

    private static AndroidDriver driver;
    private static WebDriverWait wait;
    private static WebDriverWait shortWait;
    private static int waitSeconds;
    private static Properties locators;
    private static JsonObject testData;

    // Chats pinned by THIS test run. Only these are unpinned automatically,
    // so chats that were already pinned before the run are never touched.
    private static List<String> chatsPinnedByThisRun = new ArrayList<>();

    // ============================================================
    // Setup and cleanup (runs once for the whole class)
    // ============================================================

    @BeforeAll
    static void setUp() {
        locators = loadLocators();
        testData = loadTestData();
        driver = DriverManager.startDriver();
        waitSeconds = ConfigReader.getInt("explicit.wait.seconds");
        wait = new WebDriverWait(driver, Duration.ofSeconds(waitSeconds));
        shortWait = new WebDriverWait(driver, Duration.ofSeconds(5));
    }

    @AfterAll
    static void tearDown() {
        if (!chatsPinnedByThisRun.isEmpty()) {
            System.out.println("NOTE: these chats were pinned by this run and are left pinned: "
                    + chatsPinnedByThisRun);
        }
        DriverManager.quitDriver();
    }

    // ============================================================
    // Main Chat flow - runs in this exact order (one Appium session for all tests):
    //   1 Open Chat -> 2 Search + open conversation -> 3 Send message -> (back to list)
    //   -> 4 Pin by SWIPE -> 5 Unpin by LONG PRESS -> 6 Pinned section -> 7 Recent section -> 8 Filters
    //
    // Dependencies between tests (reported in the skip messages):
    //   - Test 3 sends ONLY if test 2 opened the conversation of targetChatName.
    //   - Tests 4 and 5 are INDEPENDENT: Pin uses only "chatsToPin", Unpin uses only "chatsToUnpin".
    //     Neither restores the other's change, so the pinned chat stays pinned on purpose.
    // ============================================================

    // The conversation opened by the Search test (null = none). Test 3 only sends into this chat.
    private static String conversationOpenedBySearch = null;

    @Test
    @Order(1)
    void openChatTabTest() {
        try {
            openChatTab();
            System.out.println("STEP Chat Open: PASS - Chat page is displayed.");
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("openChatTabTest");
            throw error;
        }
    }

    @Test
    @Order(2)
    void searchChatTest() {
        String chatName = getTestDataValue("targetChatName");
        assumeTrue(!chatName.isEmpty(), "SKIPPED: targetChatName is empty in " + TEST_DATA_FILE);

        try {
            openChatTab();
            searchChat(chatName);

            // Safety check: exactly one chat must have this exact name
            int matches = countExactMatches(chatName);
            if (matches == 0) {
                fail("Search found no chat with the exact name '" + chatName + "'.");
            }
            if (matches > 1) {
                fail("Search found " + matches + " chats named '" + chatName + "' - cannot tell them apart.");
            }

            openChat(chatName); // also checks the conversation header
            conversationOpenedBySearch = chatName;
            System.out.println("STEP Search: PASS - '" + chatName + "' found and its conversation is open.");
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("searchChatTest");
            throw error;
        }
    }

    @Test
    @Order(3)
    void sendMessageTest() {
        String chatName = getTestDataValue("targetChatName");
        String message = getTestDataValue("messageText");
        String expectedMessage = getTestDataValue("expectedMessage");
        if (expectedMessage.isEmpty()) {
            expectedMessage = message;
        }

        // Never send an empty message, and never send into a chat the Search step did not open
        assumeTrue(!chatName.isEmpty(), "SKIPPED: targetChatName is empty in " + TEST_DATA_FILE + " - message NOT sent.");
        assumeTrue(!message.isEmpty(), "SKIPPED: messageText is empty in " + TEST_DATA_FILE + " - message NOT sent.");
        assumeTrue(chatName.equals(conversationOpenedBySearch),
                "SKIPPED: the Search step did not open '" + chatName + "' (it failed or was skipped) - message NOT sent.");

        boolean sendTapped = false;
        try {
            // Independent check right before typing: the open conversation must be the target chat
            String headerName = waitForElement("chat.conversation.header.title").getText().trim();
            if (!headerName.equals(chatName)) {
                fail("The open conversation is '" + headerName + "', not '" + chatName + "'. Message NOT sent.");
            }

            sendTapped = true; // from here on, Send may have been tapped - it is NEVER tapped again
            sendMessage(message, expectedMessage);
            System.out.println("STEP Send Message: PASS - message sent ONCE to '" + chatName + "' and shown in the conversation.");
        } catch (RuntimeException | AssertionError error) {
            System.out.println(sendTapped
                    ? "Message status: Send WAS tapped once, but the message could not be confirmed. It will NOT be re-sent."
                    : "Message status: NOT sent.");
            takeScreenshot("sendMessageTest");
            throw error;
        } finally {
            leaveConversationIfOpen();
        }
    }

    @Test
    @Order(4)
    void pinBySwipeTest() {
        String chatName = getPinTestChatName();
        try {
            openChatTab();
            closeSearchIfOpen();

            int matches = countExactMatches(chatName);
            if (matches != 1) {
                fail("Found " + matches + " chats named '" + chatName + "' (need exactly 1). Nothing was pinned.");
            }

            // Record the original state; never toggle a chat that was already pinned
            if (isChatPinned(chatName)) {
                throw new TestAbortedException("SKIPPED: '" + chatName + "' was already pinned before this run - left unchanged.");
            }

            pinUsingSwipe(chatName);
            assertTrue(waitForPinState(chatName, true),
                    "After tapping Pin, '" + chatName + "' did not appear in Pinned Chats.");
            System.out.println("STEP Swipe Pin: PASS - '" + chatName + "' is in Pinned Chats.");
        } catch (TestAbortedException skipped) {
            throw skipped;
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("pinBySwipeTest");
            throw error;
        }
    }

    @Test
    @Order(5)
    void unpinByLongPressTest() {
        // Target comes ONLY from "chatsToUnpin" - independent of the Pin test
        String chatName = getUnpinTestChatName();

        // Never unpin the chat the Pin test is meant to pin
        assumeTrue(!getTestDataList("chatsToPin").contains(chatName),
                "SKIPPED: '" + chatName + "' is in both chatsToPin and chatsToUnpin - not unpinned.");
        try {
            openChatTab();
            closeSearchIfOpen();
            expandPinnedSectionIfCollapsed(); // a collapsed section would hide the pinned chat

            int matches = countExactMatches(chatName);
            if (matches != 1) {
                fail("Found " + matches + " chats named '" + chatName + "' (need exactly 1). Nothing was unpinned.");
            }
            assumeTrue(isChatPinned(chatName),
                    "SKIPPED: '" + chatName + "' is not pinned - nothing to unpin (left unchanged).");

            unpinUsingLongPress(chatName);
            assertTrue(waitForPinState(chatName, false),
                    "After tapping the Unpin icon, '" + chatName + "' is still in Pinned Chats.");
            System.out.println("STEP Long Press Unpin: PASS - '" + chatName + "' was removed from Pinned Chats.");
        } catch (TestAbortedException skipped) {
            throw skipped;
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("unpinByLongPressTest");
            throw error;
        }
    }

    @Test
    @Order(6)
    void verifyPinnedChatsSectionTest() {
        // Does NOT pin anything: checks the section only if some chat is already pinned
        try {
            openChatTab();
            closeSearchIfOpen();
            if (scrollToFindElement("chat.pinned.section.header") == null) {
                throw new TestAbortedException("SKIPPED: no Pinned Chats section is shown (no chat is pinned).");
            }
            verifySectionCollapseAndExpand("Pinned", "chat.pinned.section.header", "chat.pinned.section.rows");
            System.out.println("STEP Sections (Pinned): PASS");
        } catch (TestAbortedException skipped) {
            throw skipped; // a skip is not a failure, so no screenshot
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("verifyPinnedChatsSectionTest");
            throw error;
        }
    }

    @Test
    @Order(7)
    void verifyRecentChatsSectionTest() {
        try {
            openChatTab();
            closeSearchIfOpen();
            verifyRecentChatsSection();
            System.out.println("STEP Sections (Recent): PASS");
        } catch (TestAbortedException skipped) {
            throw skipped; // a skip is not a failure, so no screenshot
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("verifyRecentChatsSectionTest");
            throw error;
        }
    }

    @Test
    @Order(8)
    void applyChatFiltersTest() {
        List<String> wantedCategories = getTestDataList("filterCategories");
        assumeTrue(!wantedCategories.isEmpty(), "SKIPPED: filterCategories is empty in " + TEST_DATA_FILE);

        try {
            openChatTab();
            closeSearchIfOpen();
            applyChatFilters(wantedCategories);
            System.out.println("STEP Filters: PASS");
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("applyChatFiltersTest");
            throw error;
        }
    }

    // The FIRST name in "chatsToPin" (chat.json), or SKIP if it is empty. Used ONLY by the Pin test.
    private String getPinTestChatName() {
        List<String> chatsToPin = getTestDataList("chatsToPin");
        assumeTrue(!chatsToPin.isEmpty(), "SKIPPED: chatsToPin is empty in " + TEST_DATA_FILE);
        return chatsToPin.get(0);
    }

    // The FIRST name in "chatsToUnpin" (chat.json), or SKIP if it is empty. Used ONLY by the Unpin test.
    private String getUnpinTestChatName() {
        List<String> chatsToUnpin = getTestDataList("chatsToUnpin");
        assumeTrue(!chatsToUnpin.isEmpty(), "SKIPPED: chatsToUnpin is empty in " + TEST_DATA_FILE);
        return chatsToUnpin.get(0);
    }

    // ============================================================
    // Older, redundant Pin/Unpin tests - kept but DISABLED so a normal run does
    // only ONE pin/unpin cycle (pinBySwipeTest + unpinByLongPressTest above).
    // Each one pins and unpins the chat itself. Remove @Disabled to run one on demand.
    // ============================================================

    @Test
    @Order(20)
    @Disabled("Redundant: replaced by pinBySwipeTest. Kept for on-demand runs.")
    void pinUsingSwipeTest() {
        String chatName = prepareUnpinnedTestChat();
        try {
            pinUsingSwipe(chatName);
            assertTrue(waitForPinState(chatName, true),
                    "After tapping Pin, '" + chatName + "' did not appear in Pinned Chats.");
            System.out.println("PASS (Swipe Pin): '" + chatName + "' is in Pinned Chats.");
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("pinUsingSwipeTest");
            throw error;
        } finally {
            restoreUnpinned(chatName);
        }
    }

    @Test
    @Order(21)
    @Disabled("Redundant: extra pin/unpin cycle (Unpin by swipe). Kept for on-demand runs.")
    void unpinUsingSwipeTest() {
        String chatName = prepareUnpinnedTestChat();
        try {
            // Setup: pin the chat first so there is something to unpin
            pinUsingSwipe(chatName);
            assertTrue(waitForPinState(chatName, true), "Setup failed: could not pin '" + chatName + "'.");

            unpinUsingSwipe(chatName);
            assertTrue(waitForPinState(chatName, false),
                    "After tapping Unpin, '" + chatName + "' is still in Pinned Chats.");
            System.out.println("PASS (Swipe Unpin): '" + chatName + "' is no longer in Pinned Chats.");
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("unpinUsingSwipeTest");
            throw error;
        } finally {
            restoreUnpinned(chatName);
        }
    }

    @Test
    @Order(22)
    @Disabled("Redundant: extra pin/unpin cycle (Pin by long press). Kept for on-demand runs.")
    void pinUsingLongPressTest() {
        String chatName = prepareUnpinnedTestChat();
        try {
            pinUsingLongPress(chatName);
            assertTrue(waitForPinState(chatName, true),
                    "After tapping the Pin icon, '" + chatName + "' did not appear in Pinned Chats.");
            System.out.println("PASS (Long Press Pin): '" + chatName + "' is in Pinned Chats.");
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("pinUsingLongPressTest");
            throw error;
        } finally {
            restoreUnpinned(chatName);
        }
    }

    @Test
    @Order(23)
    @Disabled("Redundant: replaced by unpinByLongPressTest. Kept for on-demand runs.")
    void unpinUsingLongPressTest() {
        String chatName = prepareUnpinnedTestChat();
        try {
            // Setup: pin the chat first so there is something to unpin
            pinUsingLongPress(chatName);
            assertTrue(waitForPinState(chatName, true), "Setup failed: could not pin '" + chatName + "'.");

            unpinUsingLongPress(chatName);
            assertTrue(waitForPinState(chatName, false),
                    "After tapping the Unpin icon, '" + chatName + "' is still in Pinned Chats.");
            System.out.println("PASS (Long Press Unpin): '" + chatName + "' is no longer in Pinned Chats.");
        } catch (RuntimeException | AssertionError error) {
            takeScreenshot("unpinUsingLongPressTest");
            throw error;
        } finally {
            restoreUnpinned(chatName);
        }
    }

    // ============================================================
    // Chat list actions
    // ============================================================

    // Shows the Chat page. Taps the Chat tab ONLY if the Chat page is not already shown,
    // so the list is not reloaded by tapping the tab again and again.
    public void openChatTab() {
        if (isPresent("chat.page.title") && isPresent("chat.list.container")) {
            return; // already on the Chat page
        }
        System.out.println("Opening the Chat tab...");
        click("chat.tab");
        waitForElement("chat.page.title");
        listScrolledDown = false; // freshly opened - the list starts at the top
    }

    // Returns to the Chat list if a conversation is open (used after sending)
    private void leaveConversationIfOpen() {
        if (isPresent("chat.conversation.header.title")) {
            returnToChatList();
        }
        conversationOpenedBySearch = null;
    }

    // Opens the search box (if needed), types the chat name and hides the keyboard
    public void searchChat(String chatName) {
        System.out.println("Searching for chat: " + chatName);
        if (!isPresent("chat.search.input")) {
            click("chat.search.icon");
        }
        WebElement searchBox = waitForElement("chat.search.input");
        searchBox.clear();
        searchBox.sendKeys(chatName);
        hideKeyboard();

        // Give the results a moment to update. If the chat is not visible yet,
        // the scrolling methods will look for it.
        try {
            shortWait.until(ExpectedConditions.presenceOfElementLocated(getLocator("chat.rows")));
        } catch (TimeoutException e) {
            System.out.println("No chat rows visible after searching for: " + chatName);
        }
    }

    // Clears the search text and removes focus (the search box stays open)
    public void clearSearch() {
        List<WebElement> searchBoxes = driver.findElements(getLocator("chat.search.input"));
        if (searchBoxes.size() > 0) {
            searchBoxes.get(0).clear();
        }
        hideKeyboard();
    }

    // Makes sure no old search text is filtering the list
    public void closeSearchIfOpen() {
        if (isPresent("chat.search.input")) {
            clearSearch();
        }
    }

    // Opens the chat with this exact name and checks the correct conversation opened
    public void openChat(String chatName) {
        WebElement row = scrollToFindChat(chatName);
        if (row == null) {
            fail("Chat not found in the list: " + chatName);
        }
        System.out.println("Opening chat: " + chatName);
        row.click();

        String headerName = waitForElement("chat.conversation.header.title").getText().trim();
        assertEquals(chatName, headerName, "A different conversation was opened");
        System.out.println("Correct conversation opened: " + headerName);
    }

    // Types the message, taps Send and checks a NEW outgoing bubble with the text appears
    public void sendMessage(String message, String expectedMessage) {
        // Count matching bubbles first, so an older identical message is not mistaken for the new one
        int countBefore = countOutgoingMessages(expectedMessage);

        WebElement messageBox = waitForElement("chat.message.input");
        messageBox.click();
        messageBox.sendKeys(message);
        System.out.println("Sending message: " + message);
        click("chat.send.button");

        // Check the screen every half second until the new bubble appears or time runs out
        boolean messageAppeared = false;
        long endTime = System.currentTimeMillis() + waitSeconds * 1000L;
        while (System.currentTimeMillis() < endTime) {
            if (countOutgoingMessages(expectedMessage) > countBefore) {
                messageAppeared = true;
                break;
            }
            pause(POLL_INTERVAL_MS);
        }

        if (!messageAppeared) {
            fail("The message '" + expectedMessage + "' did not appear as a new outgoing message after tapping Send.");
        }
        System.out.println("PASS: '" + expectedMessage + "' is shown as an outgoing message with a tick icon.");
        // PENDING: the tick colour (grey vs blue) is the only difference between sent/delivered/read,
        // and colours cannot be read from the UI hierarchy, so the exact delivery status is not verified.
        System.out.println("NOTE: Exact delivery status (delivered/read) cannot be read from the UI - not verified.");
    }

    // Counts visible outgoing bubbles whose text is exactly the given message
    private int countOutgoingMessages(String messageText) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                int count = 0;
                for (WebElement bubble : driver.findElements(getLocator("chat.message.outgoing.bubbles"))) {
                    for (WebElement text : bubble.findElements(getLocator("chat.message.bubble.texts"))) {
                        if (text.getText().trim().equals(messageText)) {
                            count++;
                            break;
                        }
                    }
                }
                return count;
            } catch (StaleElementReferenceException e) {
                // The conversation re-rendered while reading - read it again
            }
        }
        return 0;
    }

    // Taps the back arrow in the conversation and waits for the Chat list
    public void returnToChatList() {
        System.out.println("Going back to the Chat list...");
        click("chat.conversation.back.button");
        waitForElement("chat.page.title");
        waitForElement("chat.list.container");
        System.out.println("PASS: Back on the Chat list.");
    }

    // ============================================================
    // Reading chat rows
    // ============================================================

    // Returns the chat name shown in a row.
    // A row can contain: initials (inside the avatar), the name, the last message (optional)
    // and the date (right side). The order of these texts is not always the same.
    //   1. ignore texts that start inside the avatar   -> the initials
    //   2. ignore texts that start on the right half   -> the date
    //   3. the top-most text left over is the name     (the last message is below it)
    // Verified on rows with and without a last message, in Pinned and Recent sections.
    // PENDING: confirm on a group chat with an image avatar.
    public String getChatNameFromRow(WebElement row) {
        Rectangle rowBox = row.getRect();
        int rowCenterX = rowBox.getX() + rowBox.getWidth() / 2;

        // Right edge of the avatar area = small ViewGroups at the left end of the row
        // (the avatar circle and its online dot). If there are none, nothing is skipped.
        int quarterWidth = rowBox.getWidth() / 4;
        int avatarRightEdge = rowBox.getX();
        for (WebElement group : row.findElements(getLocator("chat.row.groups"))) {
            Rectangle groupBox = group.getRect();
            boolean isSmall = groupBox.getWidth() < quarterWidth;
            boolean isAtLeftEnd = groupBox.getX() < rowBox.getX() + quarterWidth;
            if (isSmall && isAtLeftEnd) {
                avatarRightEdge = Math.max(avatarRightEdge, groupBox.getX() + groupBox.getWidth());
            }
        }

        WebElement nameText = null;
        int smallestTop = Integer.MAX_VALUE;
        for (WebElement text : row.findElements(getLocator("chat.row.texts"))) {
            Rectangle textBox = text.getRect();
            if (textBox.getX() < avatarRightEdge) {
                continue; // initials
            }
            if (textBox.getX() > rowCenterX) {
                continue; // date
            }
            if (textBox.getY() < smallestTop) {
                smallestTop = textBox.getY();
                nameText = text;
            }
        }
        if (nameText == null) {
            return "";
        }
        return nameText.getText().trim();
    }

    // Returns the visible rows (from the given locator) whose chat name is exactly chatName
    private List<WebElement> findVisibleChatRows(String rowsLocatorKey, String chatName) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                List<WebElement> matchingRows = new ArrayList<>();
                for (WebElement row : driver.findElements(getLocator(rowsLocatorKey))) {
                    if (getChatNameFromRow(row).equals(chatName)) {
                        matchingRows.add(row);
                    }
                }
                return matchingRows;
            } catch (StaleElementReferenceException e) {
                System.out.println("The list changed while reading it - reading again...");
            }
        }
        return new ArrayList<>();
    }

    // ============================================================
    // Pin / Unpin
    // ============================================================

    // Checks the test chat before a Pin/Unpin test and returns its name.
    // Skips the test if chatsToPin is empty or the chat was already pinned
    // (so chats pinned before the test are never changed).
    private String prepareUnpinnedTestChat() {
        List<String> chatsToPin = getTestDataList("chatsToPin");
        assumeTrue(!chatsToPin.isEmpty(), "SKIPPED: chatsToPin is empty in " + TEST_DATA_FILE);
        String chatName = chatsToPin.get(0);

        openChatTab();
        closeSearchIfOpen();

        // Safety check: exactly one chat must have this exact name
        int matches = countExactMatches(chatName);
        if (matches != 1) {
            fail("Found " + matches + " chats named '" + chatName + "' (need exactly 1). Nothing was pinned.");
        }

        assumeTrue(!isChatPinned(chatName),
                "SKIPPED: '" + chatName + "' was already pinned before the test - left unchanged.");
        System.out.println("Test chat '" + chatName + "' is not pinned (starting state recorded).");
        return chatName;
    }

    // Puts the test chat back to its original state (not pinned). Never throws,
    // so it cannot hide the real test result.
    private void restoreUnpinned(String chatName) {
        try {
            exitSelectionModeIfOpen();
            openChatTab(); // make sure we check the pin state on the Chat page
            expandPinnedSectionIfCollapsed(); // a collapsed section would hide the pinned chat
            if (isChatPinned(chatName)) {
                System.out.println("Restoring: unpinning '" + chatName + "'...");
                unpinUsingSwipe(chatName);
                if (!waitForPinState(chatName, false)) {
                    System.out.println("WARNING: could not restore '" + chatName + "' - it is still pinned.");
                    return;
                }
            }
            chatsPinnedByThisRun.remove(chatName);
            System.out.println("Restored: '" + chatName + "' is not pinned (original state).");
        } catch (RuntimeException | AssertionError error) {
            System.out.println("WARNING: could not restore '" + chatName + "': " + error.getMessage());
        }
    }

    // Puts a chat that was pinned at the start back to pinned (and its section expanded).
    // Never throws, so it cannot hide the real test result.
    private void restorePinned(String chatName) {
        try {
            exitSelectionModeIfOpen();
            openChatTab();
            expandPinnedSectionIfCollapsed();
            if (!isChatPinned(chatName)) {
                System.out.println("Restoring: pinning '" + chatName + "' again...");
                pinUsingSwipe(chatName);
                chatsPinnedByThisRun.remove(chatName); // it was pinned before the test, not by it
                if (!waitForPinState(chatName, true)) {
                    System.out.println("WARNING: could not restore '" + chatName + "' - it is not pinned.");
                    return;
                }
            }
            System.out.println("Restored: '" + chatName + "' is pinned (original state).");
        } catch (RuntimeException | AssertionError error) {
            System.out.println("WARNING: could not restore '" + chatName + "': " + error.getMessage());
        }
    }

    // If the Pinned Chats section exists but is collapsed (no rows shown), tap its header to expand it
    private void expandPinnedSectionIfCollapsed() {
        if (scrollToFindElement("chat.pinned.section.header") == null) {
            return; // no Pinned section = nothing is pinned
        }
        if (driver.findElements(getLocator("chat.pinned.section.rows")).size() > 0) {
            return; // already expanded
        }
        System.out.println("Pinned Chats section is collapsed - expanding it.");
        click("chat.pinned.section.header");
        try {
            wait.until(ExpectedConditions.numberOfElementsToBeMoreThan(getLocator("chat.pinned.section.rows"), 0));
        } catch (TimeoutException e) {
            System.out.println("WARNING: Pinned Chats section did not expand.");
        }
    }

    // Swipe the chat row right-to-left, then tap the "Pin" button that appears
    public void pinUsingSwipe(String chatName) {
        WebElement row = scrollToFindChat(chatName);
        if (row == null) {
            fail("Chat not found in the list: " + chatName);
        }
        System.out.println("Swiping '" + chatName + "' and tapping Pin...");
        swipeRowLeft(row);
        click("chat.row.swipe.pin.button");
        chatsPinnedByThisRun.add(chatName);
    }

    // Swipe the PINNED chat row right-to-left, then tap the "Unpin" button that appears
    public void unpinUsingSwipe(String chatName) {
        WebElement row = scrollToFindChatIn("chat.pinned.section.rows", chatName);
        if (row == null) {
            fail("Chat not found in Pinned Chats: " + chatName);
        }
        System.out.println("Swiping '" + chatName + "' and tapping Unpin...");
        swipeRowLeft(row);
        click("chat.row.swipe.unpin.button");
    }

    // Long press the chat row, check the toolbar icon is "Pin", then tap it
    public void pinUsingLongPress(String chatName) {
        WebElement row = scrollToFindChat(chatName);
        if (row == null) {
            fail("Chat not found in the list: " + chatName);
        }
        System.out.println("Long pressing '" + chatName + "' and tapping the Pin icon...");
        longPressRow(row);
        waitForElement("chat.selection.title");

        // The same icon position is Pin OR Unpin, so check which one is shown before tapping
        if (isSelectionIconUnpin()) {
            exitSelectionModeIfOpen();
            fail("Expected the Pin icon, but the toolbar shows Unpin for '" + chatName + "'. Nothing was tapped.");
        }
        click("chat.selection.pin.icon");
        chatsPinnedByThisRun.add(chatName);
        waitForSelectionModeToClose();
    }

    // Long press the PINNED chat row, check the toolbar icon is "Unpin", then tap it
    public void unpinUsingLongPress(String chatName) {
        WebElement row = scrollToFindChatIn("chat.pinned.section.rows", chatName);
        if (row == null) {
            fail("Chat not found in Pinned Chats: " + chatName);
        }
        System.out.println("Long pressing '" + chatName + "' and tapping the Unpin icon...");
        longPressRow(row);
        waitForElement("chat.selection.title");

        if (!isSelectionIconUnpin()) {
            exitSelectionModeIfOpen();
            fail("Expected the Unpin icon, but the toolbar shows Pin for '" + chatName + "'. Nothing was tapped.");
        }
        click("chat.selection.pin.icon");
        waitForSelectionModeToClose();
    }

    // After tapping the toolbar icon, the app closes selection mode by itself.
    // Wait for that. Press Back ONLY if it is still open after the full wait -
    // pressing Back too early would leave the Chat tab (seen live: it opened the Dashboard).
    private void waitForSelectionModeToClose() {
        try {
            wait.until(ExpectedConditions.invisibilityOfElementLocated(getLocator("chat.selection.title")));
        } catch (TimeoutException e) {
            System.out.println("Selection mode did not close by itself - pressing Back.");
            exitSelectionModeIfOpen();
        }
    }

    // The toolbar icon is drawn from parts. As Appium sees them (verified live):
    //   Pin = 1 part, Unpin (crossed-out pin) = 3 parts.
    // Returns true for Unpin, false for Pin, and fails for anything unexpected.
    private boolean isSelectionIconUnpin() {
        waitForElement("chat.selection.pin.icon");
        int parts = driver.findElements(getLocator("chat.selection.icon.parts")).size();
        if (parts == 3) {
            return true;
        }
        if (parts == 1) {
            return false;
        }
        exitSelectionModeIfOpen();
        fail("Unknown toolbar icon (" + parts + " drawing parts) - cannot tell if it is Pin or Unpin. Nothing was tapped.");
        return false;
    }

    // Leaves selection mode with the Back key, but only if it is still open
    private void exitSelectionModeIfOpen() {
        if (isPresent("chat.selection.title")) {
            driver.navigate().back();
            try {
                wait.until(ExpectedConditions.invisibilityOfElementLocated(getLocator("chat.selection.title")));
            } catch (TimeoutException e) {
                System.out.println("WARNING: selection mode is still open after pressing Back.");
            }
        }
    }

    // Presses and holds a row (1.2 seconds, verified to open selection mode)
    public void longPressRow(WebElement row) {
        Map<String, Object> params = new HashMap<>();
        params.put("elementId", getElementId(row));
        params.put("duration", 1200);
        driver.executeScript("mobile: longClickGesture", params);
    }

    // True if the chat is listed in the Pinned Chats section (scrolls to look for it).
    // Fails if the Chat page is not open, so a wrong screen can never look like "not pinned".
    public boolean isChatPinned(String chatName) {
        if (!isPresent("chat.page.title")) {
            throw new RuntimeException("Cannot check the pin state: the Chat page is not open.");
        }
        return scrollToFindChatIn("chat.pinned.section.rows", chatName) != null;
    }

    // Checks every half second until the chat's pin state is as expected, or time runs out.
    // While waiting it only looks at the rows already on screen (no scrolling);
    // one full check (which may scroll a long list) is done at the end.
    private boolean waitForPinState(String chatName, boolean shouldBePinned) {
        long endTime = System.currentTimeMillis() + waitSeconds * 1000L;
        while (System.currentTimeMillis() < endTime) {
            boolean pinnedOnScreen = !findVisibleChatRows("chat.pinned.section.rows", chatName).isEmpty();
            if (pinnedOnScreen == shouldBePinned) {
                return true;
            }
            pause(POLL_INTERVAL_MS);
        }
        return isChatPinned(chatName) == shouldBePinned;
    }

    // Swipes one chat row from right to left, using that row's own position on screen
    public void swipeRowLeft(WebElement row) {
        Map<String, Object> params = new HashMap<>();
        params.put("elementId", getElementId(row));
        params.put("direction", "left");
        params.put("percent", SWIPE_PERCENT);
        driver.executeScript("mobile: swipeGesture", params);
    }

    // ============================================================
    // Sections (Pinned Chats / Recent Chats)
    // ============================================================

    // Checks the Pinned Chats section while chatName is pinned:
    // header shown, collapse/expand works, chat is listed under Pinned and NOT under Recent
    public void verifyPinnedChatsSection(String chatName) {
        if (scrollToFindElement("chat.pinned.section.header") == null) {
            fail("Pinned Chats section not found, but '" + chatName + "' is pinned.");
        }
        System.out.println("PASS: Pinned Chats section is shown.");

        verifySectionCollapseAndExpand("Pinned", "chat.pinned.section.header", "chat.pinned.section.rows");

        assertTrue(isChatPinned(chatName), "'" + chatName + "' is not listed in Pinned Chats.");
        System.out.println("PASS: '" + chatName + "' is listed in Pinned Chats.");

        // Verified live: a pinned chat moves out of Recent Chats
        WebElement recentRow = scrollToFindChatIn("chat.recent.section.rows", chatName);
        assertTrue(recentRow == null, "'" + chatName + "' is pinned but is still listed in Recent Chats.");
        System.out.println("PASS: '" + chatName + "' is not listed in Recent Chats.");
    }

    public void verifyRecentChatsSection() {
        if (scrollToFindElement("chat.recent.section.header") == null) {
            fail("Recent Chats section header was not found on the Chat page.");
        }
        verifySectionCollapseAndExpand("Recent", "chat.recent.section.header", "chat.recent.section.rows");
    }

    // Collapses a section, checks its rows are hidden, expands it again and checks rows are back
    private void verifySectionCollapseAndExpand(String sectionName, String headerKey, String rowsKey) {
        By rows = getLocator(rowsKey);

        int rowsBefore = driver.findElements(rows).size();
        System.out.println(sectionName + " section: " + rowsBefore + " rows visible before collapsing");
        assumeTrue(rowsBefore > 0,
                "SKIPPED: the " + sectionName + " section is empty (or already collapsed), so collapse cannot be checked");

        // Collapse
        click(headerKey);
        try {
            wait.until(ExpectedConditions.numberOfElementsToBe(rows, 0));
            System.out.println("PASS: " + sectionName + " section collapsed - rows are hidden.");
        } catch (TimeoutException e) {
            fail(sectionName + " section did not collapse: its rows are still shown.");
        }

        // Expand
        click(headerKey);
        try {
            wait.until(ExpectedConditions.numberOfElementsToBeMoreThan(rows, 0));
            System.out.println("PASS: " + sectionName + " section expanded - "
                    + driver.findElements(rows).size() + " rows visible again.");
        } catch (TimeoutException e) {
            fail(sectionName + " section did not expand: no rows are shown.");
        }
    }

    // ============================================================
    // Filters
    // ============================================================

    public void applyChatFilters(List<String> wantedCategories) {
        openFilterPanel();

        // Read every option the app actually shows (labels look like "Managers (3)")
        List<String> options = readFilterOptionNames();
        List<String> originallySelected = new ArrayList<>();
        System.out.println("Filter options on screen:");
        for (int i = 0; i < options.size(); i++) {
            boolean ticked = isFilterTicked(i);
            if (ticked) {
                originallySelected.add(options.get(i));
            }
            System.out.println("  " + options.get(i) + " -> " + (ticked ? "ticked" : "not ticked"));
        }

        // Every configured category must exist in the panel
        for (String category : wantedCategories) {
            if (!options.contains(category)) {
                click("chat.filter.close.button");
                fail("Filter category '" + category + "' is not offered by the app. Available: " + options);
            }
        }

        // Tick/untick only what is needed, then check the result BEFORE applying
        setFilterSelection(options, wantedCategories);
        for (int i = 0; i < options.size(); i++) {
            boolean shouldBeTicked = wantedCategories.contains(options.get(i));
            if (isFilterTicked(i) != shouldBeTicked) {
                click("chat.filter.close.button");
                fail("Could not set the filter '" + options.get(i) + "' to "
                        + (shouldBeTicked ? "ticked" : "not ticked")
                        + ". The app may change other options automatically (e.g. 'All'). Filters NOT applied.");
            }
        }

        click("chat.filter.apply.button");
        wait.until(ExpectedConditions.invisibilityOfElementLocated(getLocator("chat.filter.panel.title")));
        System.out.println("Filters applied: " + wantedCategories);

        // Read the filtered list (scrolling through all of it)
        List<String> chatNames = readAllChatNames();
        if (chatNames.isEmpty()) {
            System.out.println("Filtered list is empty - no chats match " + wantedCategories);
        } else {
            System.out.println("Filtered chats (" + chatNames.size() + "):");
            for (int i = 0; i < chatNames.size(); i++) {
                System.out.println("  " + (i + 1) + ". " + chatNames.get(i));
            }
        }
        // LIMITATION: chat rows do not show their category, so the automation cannot prove
        // that each chat belongs to the selected category. Results are reported, not judged.
        System.out.println("NOTE: Rows do not show a category, so category membership is not verified.");

        // Put the filters back the way they were
        System.out.println("Restoring the original filters: " + originallySelected);
        openFilterPanel();
        setFilterSelection(options, originallySelected);
        click("chat.filter.apply.button");
        wait.until(ExpectedConditions.invisibilityOfElementLocated(getLocator("chat.filter.panel.title")));
    }

    public void openFilterPanel() {
        click("chat.filter.icon");
        waitForElement("chat.filter.panel.title");
    }

    // Returns option names without the count: "Managers (3)" -> "Managers"
    public List<String> readFilterOptionNames() {
        List<String> names = new ArrayList<>();
        for (WebElement label : driver.findElements(getLocator("chat.filter.option.labels"))) {
            String text = label.getText().trim();
            int bracket = text.lastIndexOf(" (");
            if (bracket > 0) {
                text = text.substring(0, bracket);
            }
            names.add(text);
        }

        // Labels and checkboxes are matched by position, so the counts must be equal
        int checkboxCount = driver.findElements(getLocator("chat.filter.checkboxes")).size();
        if (checkboxCount != names.size()) {
            fail("Filter panel shows " + names.size() + " labels but " + checkboxCount + " checkboxes.");
        }
        return names;
    }

    // Returns the checkbox at this position (same order as the labels, top to bottom)
    private WebElement getFilterCheckbox(int position) {
        return driver.findElements(getLocator("chat.filter.checkboxes")).get(position);
    }

    // A ticked checkbox contains a tick image; an unticked one does not
    public boolean isFilterTicked(int position) {
        return getFilterCheckbox(position).findElements(getLocator("chat.filter.checkbox.tick")).size() > 0;
    }

    // Taps only the checkboxes whose state is different from what we want
    private void setFilterSelection(List<String> options, List<String> categoriesToTick) {
        for (int i = 0; i < options.size(); i++) {
            boolean shouldBeTicked = categoriesToTick.contains(options.get(i));
            if (isFilterTicked(i) != shouldBeTicked) {
                System.out.println("Tapping filter checkbox: " + options.get(i));
                getFilterCheckbox(i).click();
            }
        }
    }

    // ============================================================
    // Scrolling
    //
    // Every scrolling loop stops when ONE of these happens:
    //   1. the thing we are looking for is found
    //   2. a scroll no longer changes what is on screen (end of the list)
    //   3. MAX_SCROLLS scrolls have been done (safety limit)
    // ============================================================

    // Scrolls down the chat list until the chat with this exact name is visible.
    // Returns the row, or null if it is not in the list.
    public WebElement scrollToFindChat(String chatName) {
        return scrollToFindChatIn("chat.rows", chatName);
    }

    // Same as scrollToFindChat, but only looks at the rows matched by rowsLocatorKey
    // (for example only the rows inside the Pinned Chats section)
    private WebElement scrollToFindChatIn(String rowsLocatorKey, String chatName) {
        // 1. Look at what is on screen now - no scrolling
        List<WebElement> visible = findVisibleChatRows(rowsLocatorKey, chatName);
        if (visible.size() > 0) {
            return visible.get(0);
        }
        // 2. The whole list fits on the screen - it is simply not there
        if (!listNeedsScrolling()) {
            return null;
        }
        // 3. Long list: start at the top and scroll down (safety limit MAX_SCROLLS)
        scrollToTop();
        for (int scrollCount = 0; scrollCount <= MAX_SCROLLS; scrollCount++) {
            List<WebElement> rows = findVisibleChatRows(rowsLocatorKey, chatName);
            if (rows.size() > 0) {
                return rows.get(0);
            }
            if (!scrollDown()) {
                return null; // end of the list - not found
            }
        }
        System.out.println("Stopped after " + MAX_SCROLLS + " scrolls (safety limit) - chat not found: " + chatName);
        return null;
    }

    // Scrolls down until the element appears. Returns it, or null if not found.
    private WebElement scrollToFindElement(String locatorKey) {
        List<WebElement> visible = driver.findElements(getLocator(locatorKey));
        if (visible.size() > 0) {
            return visible.get(0); // already on screen - no scrolling
        }
        if (!listNeedsScrolling()) {
            return null; // whole list visible - it is not there
        }
        scrollToTop();
        for (int scrollCount = 0; scrollCount <= MAX_SCROLLS; scrollCount++) {
            List<WebElement> found = driver.findElements(getLocator(locatorKey));
            if (found.size() > 0) {
                return found.get(0);
            }
            if (!scrollDown()) {
                return null;
            }
        }
        System.out.println("Stopped after " + MAX_SCROLLS + " scrolls (safety limit) - not found: " + locatorKey);
        return null;
    }

    // Counts how many chats in the whole (searched) list have exactly this name
    public int countExactMatches(String chatName) {
        List<String> seenRows = new ArrayList<>();
        boolean longList = listNeedsScrolling();

        if (longList) {
            scrollToTop();
        }
        for (int scrollCount = 0; scrollCount <= MAX_SCROLLS; scrollCount++) {
            for (WebElement row : findVisibleChatRows("chat.rows", chatName)) {
                // The row label (initials, name, last message, date) identifies a row across scrolls
                String rowLabel = row.getAttribute("content-desc");
                if (!seenRows.contains(rowLabel)) {
                    seenRows.add(rowLabel);
                }
            }
            // A list that fits on the screen has been fully read - no scrolling needed
            if (!longList || !scrollDown()) {
                break;
            }
        }
        if (longList) {
            scrollToTop();
        }
        System.out.println("Exact matches for '" + chatName + "': " + seenRows.size());
        return seenRows.size();
    }

    // Reads every chat name in the list, scrolling to the end
    public List<String> readAllChatNames() {
        List<String> names = new ArrayList<>();
        boolean longList = listNeedsScrolling();
        if (longList) {
            scrollToTop();
        }
        for (int scrollCount = 0; scrollCount <= MAX_SCROLLS; scrollCount++) {
            for (WebElement row : driver.findElements(getLocator("chat.rows"))) {
                try {
                    String name = getChatNameFromRow(row);
                    if (!name.isEmpty() && !names.contains(name)) {
                        names.add(name);
                    }
                } catch (StaleElementReferenceException e) {
                    // Row re-rendered; it will be read again on the next pass
                }
            }
            if (!longList || !scrollDown()) {
                break;
            }
        }
        return names;
    }

    // Scrolls down once. Returns false when the screen did not change (end of the list).
    private boolean scrollDown() {
        String screenBefore = getVisibleListSignature();
        scrollList("down");
        if (!getVisibleListSignature().equals(screenBefore)) {
            listScrolledDown = true;
            return true;
        }
        // Nothing moved. More chats may still be loading at the bottom, so try once more.
        // PENDING: no loading indicator was seen with only 3 chats; re-check with a long list.
        scrollList("down");
        boolean moved = !getVisibleListSignature().equals(screenBefore);
        if (moved) {
            listScrolledDown = true;
        }
        return moved;
    }

    // Scrolls up until the screen stops changing (top of the list), with the same safety limit.
    // Does nothing if this run has not scrolled the list down: scrolling up while already at
    // the top is the "pull down" gesture that can make the list reload.
    private void scrollToTop() {
        if (!listScrolledDown) {
            return;
        }
        for (int scrollCount = 0; scrollCount < MAX_SCROLLS; scrollCount++) {
            String screenBefore = getVisibleListSignature();
            scrollList("up");
            if (getVisibleListSignature().equals(screenBefore)) {
                break;
            }
        }
        listScrolledDown = false;
    }

    // True if the chat list is longer than the screen (its last visible item reaches the
    // bottom edge of the list area), or if this run has already scrolled it down.
    // A short list is fully visible, so it never needs to be scrolled.
    private boolean listNeedsScrolling() {
        if (listScrolledDown) {
            return true;
        }
        List<WebElement> containers = driver.findElements(getLocator("chat.list.container"));
        if (containers.isEmpty()) {
            return false;
        }
        Rectangle list = containers.get(0).getRect();
        int listBottom = list.getY() + list.getHeight();
        int lowestItemBottom = 0;
        for (WebElement item : driver.findElements(getLocator("chat.list.items"))) {
            Rectangle box = item.getRect();
            lowestItemBottom = Math.max(lowestItemBottom, box.getY() + box.getHeight());
        }
        // 10 pixels of tolerance: an item cut off at the edge means there is more below
        return lowestItemBottom >= listBottom - 10;
    }

    // A short text describing what the list currently shows (labels of headers and rows).
    // If it is the same before and after a scroll, the list did not move.
    private String getVisibleListSignature() {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                StringBuilder signature = new StringBuilder();
                for (WebElement item : driver.findElements(getLocator("chat.list.items"))) {
                    signature.append(item.getAttribute("content-desc")).append("|");
                }
                return signature.toString();
            } catch (StaleElementReferenceException e) {
                // The list re-rendered while reading - read it again
            }
        }
        return "";
    }

    // Performs one scroll gesture inside the chat list container
    private void scrollList(String direction) {
        WebElement list = waitForElement("chat.list.container");
        Map<String, Object> params = new HashMap<>();
        params.put("elementId", getElementId(list));
        params.put("direction", direction);
        params.put("percent", 0.7);
        driver.executeScript("mobile: scrollGesture", params);
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

    // Appium gestures ("mobile: ...") need the element's internal id.
    // Appium java-client 11 returns AppiumWebElement objects, which provide getId().
    private String getElementId(WebElement element) {
        return ((AppiumWebElement) element).getId();
    }

    private boolean isPresent(String locatorKey) {
        return driver.findElements(getLocator(locatorKey)).size() > 0;
    }

    private void hideKeyboard() {
        try {
            driver.hideKeyboard();
        } catch (Exception e) {
            // Keyboard was not open - nothing to do
        }
    }

    // Short pause used only inside polling loops (between two checks of the screen)
    private void pause(int milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Skips the test (reported as Skipped, not Passed) while a locator is still PENDING
    private void skipIfPending(String locatorKey) {
        String value = locators.getProperty(locatorKey, "PENDING").trim();
        assumeTrue(!value.startsWith("PENDING"),
                "SKIPPED: locator '" + locatorKey + "' is PENDING verification in " + LOCATOR_FILE);
    }

    // Converts "type:value" from the locator file into a Selenium/Appium By
    private By getLocator(String locatorKey) {
        String locator = locators.getProperty(locatorKey);
        if (locator == null) {
            throw new RuntimeException("Locator key not found in " + LOCATOR_FILE + ": " + locatorKey);
        }
        locator = locator.trim();
        if (locator.startsWith("PENDING")) {
            throw new RuntimeException("Locator '" + locatorKey + "' is PENDING verification in " + LOCATOR_FILE);
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

    // Returns a text value from chat.json, or "" if it is missing
    private String getTestDataValue(String key) {
        if (testData.has(key)) {
            return testData.get(key).getAsString().trim();
        }
        return "";
    }

    // Returns a list from chat.json (empty entries are ignored)
    private List<String> getTestDataList(String key) {
        List<String> values = new ArrayList<>();
        if (testData.has(key)) {
            JsonArray array = testData.getAsJsonArray(key);
            for (int i = 0; i < array.size(); i++) {
                String value = array.get(i).getAsString().trim();
                if (!value.isEmpty()) {
                    values.add(value);
                }
            }
        }
        return values;
    }
}
