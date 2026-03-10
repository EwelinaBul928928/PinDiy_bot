package com.pindiy.bot;

import okhttp3.*;
import org.json.JSONObject;

public class AICommentGenerator {
    private static final String OPENAI_API_KEY = "YOUR_OPENAI_API_KEY";
    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";

    public static String generateComment(String postContent) throws Exception {
        OkHttpClient client = new OkHttpClient();

        JSONObject message = new JSONObject();
        message.put("role", "user");
        message.put("content", "Przeczytaj ten post i napisz uprzejmy komentarz po polsku:\n" + postContent);

        JSONObject payload = new JSONObject();
        payload.put("model", "gpt-4");
        payload.put("messages", new org.json.JSONArray().put(message));
        payload.put("max_tokens", 100);

        RequestBody body = RequestBody.create(payload.toString(), MediaType.get("application/json"));
        Request request = new Request.Builder()
                .url(OPENAI_URL)
                .addHeader("Authorization", "Bearer " + OPENAI_API_KEY)
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            String json = response.body().string();
            JSONObject obj = new JSONObject(json);
            String comment = obj.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content");
            return comment.trim();
        }
    }
}
