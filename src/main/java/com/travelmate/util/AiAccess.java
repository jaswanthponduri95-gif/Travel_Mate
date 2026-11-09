package com.travelmate.util;

import com.travelmate.model.User;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * Who may open the "AI Settings" page and change the model / API key.
 * The list of sign-in emails comes from the environment variable
 * TM_AI_ALLOWED_EMAILS,
 * else from ai.allowedEmails in ai.properties (comma, semicolon or space
 * separated).
 * Everyone else never sees the link and the server answers 403.
 */
public final class AiAccess {

    private static volatile Set<String> allowed;

    private AiAccess() {
    }

    public static boolean isAllowed(User user) {
        if (user == null || user.email == null)
            return false;
        return emails().contains(user.email.trim().toLowerCase(Locale.ROOT));
    }

    public static void reload() {
        allowed = null;
    }

    private static Set<String> emails() {
        Set<String> set = allowed;
        if (set == null) {
            set = load();
            allowed = set;
        }
        return set;
    }

    private static Set<String> load() {
        String raw = System.getProperty("tm.ai.allowedEmails");
        if (raw == null || raw.isBlank())
            raw = System.getProperty("TM_AI_ALLOWED_EMAILS");
        if (raw == null || raw.isBlank())
            raw = System.getenv("TM_AI_ALLOWED_EMAILS");
        if (raw == null || raw.isBlank())
            raw = EnvLoader.get("TM_AI_ALLOWED_EMAILS");
        if (raw == null || raw.isBlank()) {
            Properties props = new Properties();
            try (InputStream in = AiAccess.class.getClassLoader().getResourceAsStream("ai.properties")) {
                if (in != null)
                    props.load(in);
            } catch (IOException ignored) {
                // no list configured -> nobody gets access
            }
            raw = props.getProperty("ai.allowedEmails", "");
        }
        Set<String> set = new HashSet<>();
        for (String email : raw.split("[,;\\s]+")) {
            if (!email.isBlank())
                set.add(email.trim().toLowerCase(Locale.ROOT));
        }
        return set;
    }
}
