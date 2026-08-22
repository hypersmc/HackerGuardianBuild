package me.hackerguardian.api.settings;

import com.sun.net.httpserver.HttpExchange;
import me.hackerguardian.api.HgApiAuth;
import me.hackerguardian.api.HgApiHttp;

import java.util.Map;
import java.util.function.Supplier;

/** GET /v1/settings. The supplier must return operational values only. */
public final class SafeSettingsApiHandler {

    private final HgApiAuth auth;
    private final Supplier<Map<String, Object>> settingsSupplier;

    public SafeSettingsApiHandler(HgApiAuth auth, Supplier<Map<String, Object>> settingsSupplier) {
        this.auth = auth;
        this.settingsSupplier = settingsSupplier;
    }

    public void handle(HttpExchange exchange) {
        try {
            HgApiHttp.AuthenticatedRequest request = HgApiHttp.authenticate(exchange, auth);
            if (!HgApiHttp.requireAuthenticated(exchange, request)) return;
            if (!HgApiHttp.requireGet(exchange, request)) return;
            HgApiHttp.writeOk(exchange, 200, settingsSupplier.get());
        } catch (java.io.IOException tooLarge) {
            HgApiHttp.writeError(exchange, 413, "REQUEST_TOO_LARGE", tooLarge.getMessage());
        } catch (Exception e) {
            HgApiHttp.writeError(exchange, 500, "INTERNAL_ERROR", "Unable to read operational settings");
        }
    }
}
