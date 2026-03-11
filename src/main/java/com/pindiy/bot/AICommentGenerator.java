package com.pindiy.bot;

import okhttp3.*;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

public class AICommentGenerator {
    // Google Gemini – zawsze darmowe (1500 req/dzień), rejestracja: https://aistudio.google.com
    private static final String GEMINI_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent?key=";

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    public static String generateComment(String postContent) throws Exception {
        String apiKey = Config.get("gemini.api_key");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("gemini.api_key not set in config.properties");
        }

        String prompt = "You are a friendly, enthusiastic girl on a crafting forum. Write ONE short comment (1 sentence, max 15 words) reacting to this post. Be warm, genuine and cute. No intro, no explanation, output only the comment:\n" + postContent;

        JSONObject part = new JSONObject().put("text", prompt);
        JSONObject content = new JSONObject().put("parts", new org.json.JSONArray().put(part));
        JSONObject payload = new JSONObject().put("contents", new org.json.JSONArray().put(content));

        RequestBody body = RequestBody.create(payload.toString(), MediaType.get("application/json"));
        Request request = new Request.Builder()
                .url(GEMINI_URL + apiKey)
                .post(body)
                .build();

        try (Response response = CLIENT.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            if (responseBody == null) throw new IllegalStateException("Empty response from Gemini");
            String json = responseBody.string();
            JSONObject obj = new JSONObject(json);
            String comment = obj.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text");
            return stripPreamble(comment.trim());
        }
    }

    // Usuwa wstępy w stylu "Sure!", "Here are my suggestions:", "Jasne, oto:" itp.
    private static String stripPreamble(String text) {
        String[] lines = text.split("\\n+");
        // Jeśli pierwsza linia kończy się dwukropkiem lub jest bardzo krótka (intro), pomijamy ją
        if (lines.length > 1) {
            String first = lines[0].trim();
            if (first.endsWith(":") || first.length() < 60 && (first.endsWith("!") || first.toLowerCase().startsWith("sure") || first.toLowerCase().startsWith("of course") || first.toLowerCase().startsWith("here") || first.toLowerCase().startsWith("jasne") || first.toLowerCase().startsWith("oto"))) {
                // zwróć resztę
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < lines.length; i++) {
                    if (!lines[i].isBlank()) { sb.append(lines[i].trim()).append(" "); }
                }
                String result = sb.toString().trim();
                if (!result.isBlank()) return result;
            }
        }
        // Weź tylko pierwsze zdania (do 2)
        String[] sentences = text.split("(?<=[.!?])\\s+");
        if (sentences.length <= 2) return text;
        return sentences[0] + " " + sentences[1];
    }
}
