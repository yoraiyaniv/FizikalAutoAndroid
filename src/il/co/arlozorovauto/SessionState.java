package il.co.arlozorovauto;

import android.content.Context;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/** Process-wide in-memory session (access token) backed by the on-disk
 *  refresh token in DbHelper. A single instance is shared by the UI and the
 *  background registration service since they can run in the same process. */
public class SessionState {

    private static SessionState instance;

    public String phone;
    public String accessToken;
    public String refreshToken;
    public long accessTokenExpiryMillis; // epoch millis, 0 if unknown

    public static synchronized SessionState get(Context ctx) {
        if (instance == null) {
            instance = new SessionState();
            instance.loadFromDisk(ctx);
        }
        return instance;
    }

    private void loadFromDisk(Context ctx) {
        DbHelper db = new DbHelper(ctx);
        String[] session = db.loadSession();
        phone = session[0];
        refreshToken = session[1];
    }

    public boolean isLoggedIn() {
        return refreshToken != null;
    }

    public void setTokens(String access, String refresh) {
        accessToken = access;
        refreshToken = refresh;
        accessTokenExpiryMillis = decodeExpiryMillis(access);
    }

    public long secondsUntilExpiry() {
        if (accessTokenExpiryMillis == 0) return 0;
        return (accessTokenExpiryMillis - System.currentTimeMillis()) / 1000;
    }

    public void logout(Context ctx) {
        phone = null;
        accessToken = null;
        refreshToken = null;
        accessTokenExpiryMillis = 0;
        new DbHelper(ctx).clearSession();
    }

    /** Refreshes the access token if it looks close to expiring, based on
     *  locally decoding the JWT. Used for UI-triggered calls (MainActivity)
     *  where being a bit conservative is harmless. NOT used by the background
     *  registration flow any more - the server tolerates a token well past
     *  its stated "exp" (confirmed live), so deciding "expired" client-side
     *  was cutting things off unnecessarily early; see tryRefreshNow(). */
    public boolean ensureFreshToken(Context ctx) {
        if (!isLoggedIn()) return false;
        if (secondsUntilExpiry() > 180) return true;
        return tryRefreshNow(ctx);
    }

    /** Unconditionally attempts a silent refresh right now, regardless of
     *  what the locally-decoded expiry says. Meant to be called reactively,
     *  after the server has already told us (via a 401 "Invalid token")
     *  that the current access token is actually dead. Returns false if no
     *  fresh token could be obtained (caller should prompt re-login). */
    public boolean tryRefreshNow(Context ctx) {
        if (!isLoggedIn()) return false;
        String[] tokens = FizikalApi.refreshToken(refreshToken);
        if (tokens == null) return false;
        setTokens(tokens[0], tokens[1]);
        new DbHelper(ctx).saveSession(phone, refreshToken);
        return true;
    }

    private static long decodeExpiryMillis(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            byte[] payloadBytes = Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            JSONObject payload = new JSONObject(new String(payloadBytes, StandardCharsets.UTF_8));
            return payload.getLong("exp") * 1000L;
        } catch (Exception e) {
            return 0;
        }
    }
}
