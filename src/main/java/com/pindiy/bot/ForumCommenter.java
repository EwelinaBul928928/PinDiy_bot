package com.pindiy.bot;

import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import javax.swing.JOptionPane;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class ForumCommenter {

    private static final String HOME_URL  = "https://www.pindiy.com/";
    private static final String FORUM_URL = "https://www.pindiy.com/forum-CrochetPR-1.html";

    private static final By LOGIN_INPUT        = By.id("ls_username");
    private static final By PASSWORD_INPUT     = By.id("ls_password");
    private static final By LOGGED_IN_INDICATOR = By.cssSelector(
        "#um_avatar, .avatar, [href*='space-uid'], .pm_notifier, #nav [href*='member']"
    );
    private static final By FAST_REPLY_BOX = By.id("fastpostmessage");

    // ─────────────────────────────────────────────────────── //

    private static List<String> findThreadUrls(ChromeDriver driver) {
        @SuppressWarnings("unchecked")
        List<String> urls = (List<String>) ((JavascriptExecutor) driver).executeScript(
            "return Array.from(document.querySelectorAll('a[href]'))" +
            ".map(a => a.href)" +
            ".filter(h => /thread-\\d/.test(h) && h.endsWith('.html'))" +
            ".filter((v,i,a) => a.indexOf(v) === i);"
        );
        return urls != null ? urls : new ArrayList<>();
    }

    private static String getPostContent(ChromeDriver driver) {
        try {
            WebElement post = driver.findElement(By.cssSelector(".postmessage"));
            return post.getText();
        } catch (Exception e) {
            return driver.getTitle();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        String username = Config.get("pindiy.login");
        String password = Config.get("pindiy.password");

        CommentLog log = CommentLog.load();
        System.out.println("[INFO]  Total commented so far: " + log.totalCommented());
        System.out.println();

        ChromeDriver driver = BotDriver.create();
        WebDriverWait wait  = new WebDriverWait(driver, Duration.ofSeconds(30));

        int minutes = askForDuration();
        long deadline = System.currentTimeMillis() + (long) minutes * 60_000;
        System.out.println("[INFO]  Bot will run for " + minutes + " minute(s).");
        System.out.println("[INFO]  Stop time: " + new java.util.Date(deadline));
        System.out.println();

        try {
            driver.get(HOME_URL);
            waitForCloudflare(driver);
            login(driver, wait, username, password);

            driver.get(FORUM_URL);
            waitForCloudflare(driver);
            Thread.sleep(2000);

            List<String> threadUrls = findThreadUrls(driver);
            if (threadUrls.isEmpty()) {
                System.out.println("[WARN]  No threads found!");
                return;
            }
            System.out.println("[INFO]  Found " + threadUrls.size() + " threads.\n");

            int posted = 0, skipped = 0, errors = 0;

            for (int i = 0; i < threadUrls.size(); i++) {
                if (System.currentTimeMillis() >= deadline) {
                    System.out.println("[INFO]  Time limit reached – stopping.");
                    break;
                }

                String url = threadUrls.get(i);
                System.out.printf("[%d/%d] Processing thread...%n", i + 1, threadUrls.size());

                if (!isLoggedIn(driver)) {
                    driver.get(HOME_URL);
                    waitForCloudflare(driver);
                    login(driver, wait, username, password);
                }

                driver.get(url);
                waitForCloudflare(driver);
                Thread.sleep(1500);

                String title = driver.getTitle();

                if (log.isBlacklisted(url)) {
                    System.out.println("[SKIP]  Blacklisted: " + url);
                    skipped++;
                    continue;
                }

                if (log.alreadyCommented(url)) {
                    System.out.println("[SKIP]  Already commented: " + title);
                    skipped++;
                    continue;
                }

                if (isDuplicate(driver, username)) {
                    System.out.println("[SKIP]  Already commented (page): " + title);
                    log.record(url, title);
                    skipped++;
                    continue;
                }

                try {
                    long waitMs = log.msToCooldownEnd();
                    if (waitMs > 0) {
                        System.out.printf("[INFO]  Cooldown: waiting %d s...%n", waitMs / 1000);
                        Thread.sleep(waitMs);
                    }

                    String postContent = getPostContent(driver);
                    String comment;
                    try {
                        comment = AICommentGenerator.generateComment(postContent);
                        System.out.println("[INFO]  AI generated comment.");
                    } catch (Exception aiEx) {
                        comment = Config.get("pindiy.comment");
                        System.out.println("[WARN]  AI failed, using default comment: " + aiEx.getMessage());
                    }

                    if (comment == null || comment.isBlank()) {
                        comment = Config.get("pindiy.comment");
                    }

                    postComment(driver, wait, comment);
                    log.record(url, title);
                    System.out.println("[OK]    Commented: " + title);
                    posted++;

                    long extra = 5_000 + (long)(Math.random() * 10_000);
                    System.out.printf("[INFO]  Extra delay: %d s...%n", extra / 1000);
                    Thread.sleep(extra);
                } catch (Exception e) {
                    System.out.println("[ERR]   Failed: " + e.getMessage());
                    errors++;
                }
            }

            System.out.println();
            System.out.println("───────────────────────────");
            System.out.println("  Commented : " + posted);
            System.out.println("  Skipped   : " + skipped);
            System.out.println("  Errors    : " + errors);
            System.out.println("───────────────────────────");

        } catch (Exception e) {
            System.out.println("[FATAL] " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ─── Duration dialog ───────────────────────────────── //

    private static int askForDuration() {
        while (true) {
            String input = JOptionPane.showInputDialog(
                null, "Przez ile minut ma działać bot?",
                "PinDIY Forum Commenter", JOptionPane.QUESTION_MESSAGE
            );
            if (input == null) { System.exit(0); }
            try {
                int v = Integer.parseInt(input.trim());
                if (v > 0) return v;
            } catch (NumberFormatException ignored) {}
            JOptionPane.showMessageDialog(null, "Podaj liczbę całkowitą > 0.", "Błąd", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ─── Login ─────────────────────────────────────────── //

    static void login(ChromeDriver driver, WebDriverWait wait, String username, String password)
            throws InterruptedException {
        System.out.println("[INFO]  Logging in as: " + username);
        wait.until(ExpectedConditions.visibilityOfElementLocated(LOGIN_INPUT));
        WebElement u = driver.findElement(LOGIN_INPUT);
        u.click(); u.clear(); humanType(u, username);
        WebElement p = driver.findElement(PASSWORD_INPUT);
        p.click(); p.clear(); humanType(p, password);
        p.sendKeys(Keys.ENTER);
        wait.until(ExpectedConditions.visibilityOfElementLocated(LOGGED_IN_INDICATOR));
        System.out.println("[INFO]  Logged in.");
    }

    private static boolean isLoggedIn(ChromeDriver driver) {
        try { return !driver.findElements(LOGGED_IN_INDICATOR).isEmpty(); }
        catch (Exception e) { return false; }
    }

    // ─── Duplicate check ───────────────────────────────── //

    private static boolean isDuplicate(ChromeDriver driver, String username) {
        List<WebElement> authors = driver.findElements(By.cssSelector(
            ".authi a, .pls .pi a, .plhin .plinf a, .postinfo strong"
        ));
        for (WebElement el : authors)
            if (username.equalsIgnoreCase(el.getText().trim())) return true;
        return false;
    }

    // ─── Post comment ──────────────────────────────────── //

    private static void postComment(ChromeDriver driver, WebDriverWait wait, String comment)
            throws InterruptedException {

        JavascriptExecutor js = (JavascriptExecutor) driver;
        js.executeScript("window.scrollTo(0, document.body.scrollHeight);");
        Thread.sleep(1500);

        List<WebElement> boxes = driver.findElements(FAST_REPLY_BOX);

        if (boxes.isEmpty() || !boxes.get(0).isDisplayed()) {
            List<WebElement> toggle = driver.findElements(By.cssSelector(
                "a[onclick*='fastReply'], a[id*='fastreplyopen'], .replybtn, a.fastbtn"
            ));
            if (!toggle.isEmpty()) { toggle.get(0).click(); Thread.sleep(1000); }
            boxes = driver.findElements(FAST_REPLY_BOX);
        }

        if (!boxes.isEmpty() && boxes.get(0).isDisplayed()) {
            WebElement box = boxes.get(0);
            js.executeScript("arguments[0].scrollIntoView(true);", box);
            Thread.sleep(600);
            box.click(); Thread.sleep(300);
            box.clear();
            humanType(box, comment);
            Thread.sleep(400);

            // Try to find and click the submit button
            List<WebElement> submitBtns = driver.findElements(By.cssSelector(
                "#fastreply input[type='submit'], #fastreply button[type='submit'], " +
                "input[name='fastpostsubmit'], input[name='postsubmit'], " +
                "button[name='fastpostsubmit'], input[id='fastpostsubmit']"
            ));
            WebElement btn = submitBtns.stream().filter(WebElement::isDisplayed).findFirst().orElse(null);

            if (btn != null) {
                js.executeScript("arguments[0].click();", btn);
                System.out.println("[INFO]  Clicked submit button.");
            } else {
                Boolean submitted = (Boolean) js.executeScript(
                    "var el=document.getElementById('fastpostmessage');" +
                    "if(el&&el.form){el.form.submit();return true;}return false;"
                );
                if (!submitted) box.sendKeys(Keys.ENTER);
            }
            Thread.sleep(2500);
            return;
        }

        // Fallback: full reply page
        List<WebElement> replyBtn = driver.findElements(By.cssSelector(
            "a[href*='action=reply'], a.replythread, em a[href*='reply']"
        ));
        if (replyBtn.isEmpty()) throw new RuntimeException("Reply form not found.");
        replyBtn.get(0).click();
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("messagetext")));
        Thread.sleep(800);
        WebElement msgBox = driver.findElement(By.id("messagetext"));
        msgBox.click(); msgBox.clear();
        humanType(msgBox, comment);
        Thread.sleep(400);
        List<WebElement> fullSubmit = driver.findElements(By.cssSelector(
            "input[type='submit'][name='postsubmit'], input[id='postsubmit']"
        ));
        WebElement fb = fullSubmit.stream().filter(WebElement::isDisplayed).findFirst().orElse(null);
        if (fb != null) js.executeScript("arguments[0].click();", fb);
        else msgBox.sendKeys(Keys.ENTER);
        Thread.sleep(2000);
    }

    // ─── Helpers ───────────────────────────────────────── //

    static void waitForCloudflare(ChromeDriver driver) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            String t = driver.getTitle().toLowerCase();
            if (!t.contains("just a moment") && !t.contains("security") && !t.contains("verification")) break;
            System.out.println("[INFO]  Cloudflare challenge active, waiting...");
            Thread.sleep(2000);
        }
    }

    private static void humanType(WebElement field, String text) throws InterruptedException {
        for (char c : text.toCharArray()) {
            field.sendKeys(String.valueOf(c));
            Thread.sleep(60 + (long)(Math.random() * 60));
        }
    }
}
