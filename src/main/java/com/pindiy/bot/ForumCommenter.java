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

    private static final String HOME_URL = "https://www.pindiy.com/";

    private static final By LOGIN_INPUT        = By.id("ls_username");
    private static final By PASSWORD_INPUT     = By.id("ls_password");
    private static final By LOGGED_IN_INDICATOR = By.cssSelector(
        "#um_avatar, .avatar, [href*='space-uid'], .pm_notifier, #nav [href*='member']"
    );
    private static final By FAST_REPLY_BOX = By.id("fastpostmessage");

    // ─────────────────────────────────────────────────────── //

    // Zbiera linki do sekcji forum (format Discuz!: forum-XXX-1.html)
    private static List<String> findForumSections(ChromeDriver driver) {
        @SuppressWarnings("unchecked")
        List<String> urls = (List<String>) ((JavascriptExecutor) driver).executeScript(
            "return Array.from(document.querySelectorAll('a[href]'))" +
            ".map(a => a.href)" +
            ".filter(h => /\\/forum-[^/]+-1\\.html/.test(h))" +
            ".filter((v,i,a) => a.indexOf(v) === i);"
        );
        return urls != null ? urls : new ArrayList<>();
    }

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

    // Przechodzi do ostatniej strony wątku jeśli jest paginacja
    private static void goToLastPage(ChromeDriver driver) throws InterruptedException {
        List<WebElement> pageLinks = driver.findElements(By.cssSelector(".pg a[href], .pages a[href]"));
        String lastHref = null;
        int maxPage = 1;
        for (WebElement link : pageLinks) {
            try {
                int p = Integer.parseInt(link.getText().trim());
                if (p > maxPage) { maxPage = p; lastHref = link.getAttribute("href"); }
            } catch (NumberFormatException ignored) {}
        }
        if (lastHref != null) {
            driver.get(lastHref);
            waitForCloudflare(driver);
            Thread.sleep(1000);
        }
    }

    private static String getPostContent(ChromeDriver driver) {
        // Discuz! używa td.t_f lub .t_f jako główny kontener treści posta
        String[] selectors = {"td.t_f", ".t_f", ".postmessage", ".pcb", "#postlist td"};

        // 1. Próbuj pobrać treść OP (pierwszy element)
        for (String sel : selectors) {
            try {
                List<WebElement> els = driver.findElements(By.cssSelector(sel));
                if (!els.isEmpty()) {
                    String text = els.get(0).getText().trim();
                    if (text.length() > 20) return text;
                }
            } catch (Exception ignored) {}
        }

        // 2. Fallback: zbierz tekst z kolejnych elementów (komentarze innych)
        for (String sel : selectors) {
            try {
                List<WebElement> els = driver.findElements(By.cssSelector(sel));
                if (els.size() > 1) {
                    StringBuilder sb = new StringBuilder();
                    int count = 0;
                    for (WebElement el : els) {
                        String t = el.getText().trim();
                        if (t.length() > 10) {
                            sb.append(t).append(" ");
                            if (++count >= 3) break;
                        }
                    }
                    String result = sb.toString().trim();
                    if (result.length() > 20) return result;
                }
            } catch (Exception ignored) {}
        }

        return "";
    }

    private static boolean isPostHidden(ChromeDriver driver) {
        String pageSource = driver.getPageSource().toLowerCase();
        return pageSource.contains("post visible to author only") ||
               pageSource.contains("this post is only visible to") ||
               pageSource.contains("hidden content");
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

            // Zbierz wszystkie sekcje forum z głównej strony
            List<String> sections = findForumSections(driver);
            if (sections.isEmpty()) sections.add(HOME_URL); // fallback
            System.out.println("[INFO]  Found " + sections.size() + " forum sections.");

            // Scan każdej sekcji i zbierz unikalne wątki (po znormalizowanym ID)
            List<String> threadUrls = new ArrayList<>();
            java.util.Set<String> seenIds = new java.util.HashSet<>();
            for (String section : sections) {
                if (System.currentTimeMillis() >= deadline) break;
                driver.get(section);
                waitForCloudflare(driver);
                Thread.sleep(1000);
                for (String tUrl : findThreadUrls(driver)) {
                    if (seenIds.add(CommentLog.normalize(tUrl))) threadUrls.add(tUrl);
                }
            }

            if (threadUrls.isEmpty()) {
                System.out.println("[WARN]  No threads found!");
                return;
            }
            java.util.Collections.shuffle(threadUrls);
            System.out.println("[INFO]  Found " + threadUrls.size() + " unique threads total (shuffled).\n");

            int posted = 0, skipped = 0, errors = 0;

            for (int i = 0; i < threadUrls.size(); i++) {
                if (System.currentTimeMillis() >= deadline) {
                    System.out.println("[INFO]  Time limit reached – stopping.");
                    break;
                }

                String url = threadUrls.get(i);
                System.out.printf("[%d/%d] Processing thread...%n", i + 1, threadUrls.size());

                if (log.isBlacklisted(url)) {
                    System.out.println("[SKIP]  Blacklisted: " + url);
                    skipped++;
                    continue;
                }

                if (log.alreadyCommented(url)) {
                    System.out.println("[SKIP]  Already commented (log): " + url);
                    skipped++;
                    continue;
                }

                if (!isLoggedIn(driver)) {
                    driver.get(HOME_URL);
                    waitForCloudflare(driver);
                    login(driver, wait, username, password);
                }

                // Otwórz stronę 1 żeby pobrać tytuł i treść posta
                driver.get(url);
                waitForCloudflare(driver);
                Thread.sleep(1500);
                String title = driver.getTitle();

                if (isPostHidden(driver)) {
                    System.out.println("[SKIP]  Post hidden/locked: " + title);
                    log.record(url, title);
                    skipped++;
                    continue;
                }

                // Pobierz treść posta ze strony 1 (OP lub komentarze jako fallback)
                String postContent = getPostContent(driver);
                if (postContent.isBlank()) {
                    // Ostatni fallback: tytuł wątku jako kontekst dla AI
                    String cleanTitle = title.replaceAll(" - PinDIY\\.com.*", "").trim();
                    if (cleanTitle.length() > 10) {
                        postContent = cleanTitle;
                        System.out.println("[INFO]  Using title as context: " + cleanTitle);
                    } else {
                        System.out.println("[SKIP]  No readable content: " + title);
                        skipped++;
                        continue;
                    }
                } else {
                    System.out.println("[INFO]  Content read (" + Math.min(postContent.length(), 60) + " chars).");
                }

                // Przejdź na ostatnią stronę wątku (tam będziemy komentować)
                goToLastPage(driver);

                if (isDuplicate(driver, username)) {
                    System.out.println("[SKIP]  Already commented (page check): " + title);
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

                    String comment;
                    try {
                        comment = AICommentGenerator.generateComment(postContent);
                        System.out.println("[INFO]  AI generated comment.");
                    } catch (Exception aiEx) {
                        System.out.println("[SKIP]  AI failed, skipping: " + aiEx.getClass().getSimpleName() + ": " + aiEx.getMessage());
                        skipped++;
                        continue;
                    }

                    if (comment == null || comment.isBlank()) {
                        System.out.println("[SKIP]  AI returned empty comment, skipping.");
                        skipped++;
                        continue;
                    }

                    postComment(driver, wait, comment);
                    log.record(url, title);
                    System.out.println("[OK]    Commented: " + title);
                    System.out.println("[MSG]   >> " + comment);
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
