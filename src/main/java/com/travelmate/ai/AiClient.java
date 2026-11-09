package com.travelmate.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.travelmate.model.AiSettings;
import com.travelmate.util.JsonUtil;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal client for OpenAI-compatible APIs (POST {baseUrl}/chat/completions,
 * GET {baseUrl}/models). Works with OpenRouter, OpenAI, Groq, Gemini's OpenAI
 * endpoint, Ollama, LM Studio and most lab/experimental endpoints.
 */
public class AiClient {

    /** Raised for any failure talking to the model, with a message fit to show the user. */
    public static class AiException extends Exception {
        public AiException(String message) {
            super(message);
        }
    }

    private final AiSettings settings;
    private final HttpClient http;

    public AiClient(AiSettings settings) {
        this(settings, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    public AiClient(AiSettings settings, HttpClient http) {
        this.settings = settings;
        this.http = http != null ? http : HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Sends a system + user message and returns the assistant's text. */
    public String chat(String system, String user) throws AiException {
        JsonObject body = new JsonObject();
        body.addProperty("model", settings.model);
        JsonArray messages = new JsonArray();
        messages.add(message("system", system));
        messages.add(message("user", user));
        body.add("messages", messages);
        body.addProperty("temperature", settings.temperature);
        if (settings.maxTokens > 0) body.addProperty("max_tokens", settings.maxTokens);
        if (settings.jsonMode) {
            JsonObject format = new JsonObject();
            format.addProperty("type", "json_object");
            body.add("response_format", format);
        }

        JsonObject response = send(request("/chat/completions")
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtil.GSON.toJson(body))));
        JsonArray choices = response.has("choices") && response.get("choices").isJsonArray()
                ? response.getAsJsonArray("choices") : null;
        if (choices == null || choices.isEmpty() || !choices.get(0).isJsonObject()) {
            throw new AiException("The model returned no choices: " + sanitize(snippet(response.toString())));
        }
        JsonObject first = choices.get(0).getAsJsonObject();
        String finishReason = first.has("finish_reason") && !first.get("finish_reason").isJsonNull()
                ? first.get("finish_reason").getAsString() : "unknown";
        JsonObject msg = first.has("message") && first.get("message").isJsonObject()
                ? first.getAsJsonObject("message") : null;
        String text = msg == null ? null : contentText(msg.get("content"));
        if ("length".equalsIgnoreCase(finishReason)) {
            throw new AiException("The AI provider cut off the itinerary because its output limit was reached. "
                    + "Choose a model with a larger output limit or reduce the trip length.");
        }
        if (text == null || text.isBlank()) {
            throw new AiException("The model returned an empty reply (finish_reason " + finishReason
                    + "). Check the selected model and its provider limits.");
        }
        return text;
    }

    /** Model ids offered by the provider (empty when it has no /models endpoint). */
    public List<String> listModels() throws AiException {
        JsonObject response = send(request("/models").GET());
        List<String> ids = new ArrayList<>();
        JsonElement data = response.get("data");
        if (data != null && data.isJsonArray()) {
            for (JsonElement el : data.getAsJsonArray()) {
                if (el.isJsonObject() && el.getAsJsonObject().has("id")) {
                    JsonElement idEl = el.getAsJsonObject().get("id");
                    if (idEl != null && idEl.isJsonPrimitive()) {
                        ids.add(idEl.getAsString());
                    }
                }
            }
        }
        ids.sort(String::compareToIgnoreCase);
        return ids;
    }

    private HttpRequest.Builder request(String path) throws AiException {
        String base = settings.baseUrl == null ? "" : settings.baseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            throw new AiException("Base URL must start with http:// or https://");
        }
        HttpRequest.Builder b;
        try {
            b = HttpRequest.newBuilder(URI.create(base + path));
        } catch (IllegalArgumentException e) {
            throw new AiException("Invalid base URL: " + e.getMessage());
        }
        b.timeout(Duration.ofSeconds(Math.max(settings.timeoutSeconds, 1)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        if (settings.apiKey != null && !settings.apiKey.isBlank()) {
            b.header("Authorization", "Bearer " + settings.apiKey.trim());
        }
        if (settings.extraHeaders != null) {
            for (String line : settings.extraHeaders.split("\\R")) {
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if (name.isEmpty() || name.equalsIgnoreCase("Authorization") || name.equalsIgnoreCase("Content-Type")) continue;
                try {
                    b.header(name, value);
                } catch (IllegalArgumentException e) {
                    throw new AiException("Invalid extra header \"" + name + "\": " + e.getMessage());
                }
            }
        }
        return b;
    }

    private JsonObject send(HttpRequest.Builder builder) throws AiException {
        HttpResponse<String> resp;
        try {
            resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new AiException("The AI server did not answer within " + settings.timeoutSeconds + " seconds.");
        } catch (java.net.ConnectException e) {
            throw new AiException("Could not connect to " + settings.baseUrl + " (is the server running / URL correct?)");
        } catch (IOException e) {
            throw new AiException("Network error talking to the AI server: " + sanitize(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException("Request interrupted");
        }
        String text = resp.body() == null ? "" : resp.body();
        JsonObject json = null;
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (parsed.isJsonObject()) json = parsed.getAsJsonObject();
        } catch (RuntimeException ignored) {
            // handled below
        }
        if (resp.statusCode() / 100 != 2) {
            String detail = json != null ? errorMessage(json) : snippet(text);
            String hint = switch (resp.statusCode()) {
                case 401, 403 -> " — check the API key.";
                case 404 -> " — check the base URL and the model id.";
                case 429 -> " — rate limit or no credits left on this key.";
                default -> "";
            };
            throw new AiException(sanitize("AI server returned HTTP " + resp.statusCode() + ": " + detail + hint));
        }
        if (json == null) throw new AiException(sanitize("AI server reply was not JSON: " + snippet(text)));
        if (json.has("error")) throw new AiException(sanitize("AI server error: " + errorMessage(json)));
        return json;
    }

    private String sanitize(String text) {
        if (text == null) return "";
        if (settings.apiKey != null && !settings.apiKey.isBlank()) {
            return text.replace(settings.apiKey.trim(), "••••");
        }
        return text;
    }

    private static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    /** Content may be a plain string or a list of {type:"text", text:"..."} parts. */
    private static String contentText(JsonElement content) {
        if (content == null || content.isJsonNull()) return null;
        if (content.isJsonPrimitive()) return content.getAsString();
        if (content.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement part : content.getAsJsonArray()) {
                if (part.isJsonObject() && part.getAsJsonObject().has("text")) sb.append(part.getAsJsonObject().get("text").getAsString());
            }
            return sb.toString();
        }
        return null;
    }

    private static String errorMessage(JsonObject json) {
        JsonElement err = json.get("error");
        if (err != null && err.isJsonObject() && err.getAsJsonObject().has("message")) {
            return err.getAsJsonObject().get("message").getAsString();
        }
        if (err != null && err.isJsonPrimitive()) return err.getAsString();
        if (json.has("message")) return json.get("message").getAsString();
        return snippet(json.toString());
    }

    private static String snippet(String s) {
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
