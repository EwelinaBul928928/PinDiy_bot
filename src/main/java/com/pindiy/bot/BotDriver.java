package com.pindiy.bot;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class BotDriver {

    private static final String BOT_PROFILE =
        new File("").getAbsolutePath() + "/bot-profile";
    private static final String REAL_CHROME =
        System.getProperty("user.home") + "/Library/Application Support/Google/Chrome";

    public static ChromeDriver create() {
        copyCookies();

        WebDriverManager.chromedriver().setup();

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--user-data-dir=" + BOT_PROFILE);
        options.addArguments("--profile-directory=Default");
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.addArguments("--start-maximized");
        options.setExperimentalOption("excludeSwitches", new String[]{"enable-automation"});
        options.setExperimentalOption("useAutomationExtension", false);

        ChromeDriver driver = new ChromeDriver(options);

        // CDP stealth: hide webdriver flag
        driver.executeCdpCommand("Page.addScriptToEvaluateOnNewDocument",
            java.util.Map.of("source",
                "Object.defineProperty(navigator, 'webdriver', {get: () => undefined});"));

        return driver;
    }

    private static void copyCookies() {
        File src = new File(REAL_CHROME + "/Default/Cookies");
        File destDir = new File(BOT_PROFILE + "/Default");
        File dest = new File(destDir, "Cookies");

        if (!src.exists()) {
            System.out.println("[INFO]  Real Chrome cookies not found – skipping copy.");
            return;
        }
        destDir.mkdirs();
        try {
            Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[INFO]  Cookies copied from Chrome profile.");
        } catch (IOException e) {
            System.out.println("[WARN]  Could not copy cookies: " + e.getMessage());
        }
    }
}
