package il.co.arlozorovauto;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;

/** Thin client for the Fizikal (il.co.offline.arlozorov) backend, reverse
 *  engineered from the app's own decrypted network logs. Mirrors the Python
 *  reference implementation used to validate these endpoints. */
public class FizikalApi {

    public static final String BASE_URL = "https://api.fizikal.co.il/app/v1";

    // Identifies this account's organization/company/branch - captured from
    // one real login. Re-capture if used against a different gym/branch.
    public static final int ORGANIZATION_ID = 1;
    public static final int COMPANY_ID = 230;
    public static final int BRANCH_ID = 23;
    public static final int SOURCE_ID = 1;
    public static final int AUTHENTICATION_TYPE_ID = 1;

    public static final String VERSION_HEADER = "70.0.10226";
    public static final String LANGUAGE_HEADER = "he";

    // Set once at startup (MainActivity.onCreate / RegistrationService) from
    // this install's own ANDROID_ID via initDeviceId(). Previously this was a
    // literal DeviceId string copied from a different phone's captured
    // traffic - sending someone else's device identifier from every request
    // was needlessly sloppy fingerprinting hygiene, independent of whether it
    // was ever actually the cause of any server-side blocking.
    public static volatile String deviceId = "unknown";

    public static void initDeviceId(android.content.Context ctx) {
        if (!"unknown".equals(deviceId)) return; // already set
        try {
            String androidId = android.provider.Settings.Secure.getString(
                    ctx.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
            if (androidId != null && !androidId.isEmpty()) {
                deviceId = androidId;
            }
        } catch (Exception ignored) {
            // keep "unknown" fallback
        }
    }

    /** True if a response body is the server's "access token expired/invalid"
     *  rejection ({"success":false,"statusCode":401,"exception":{"message":
     *  "Invalid token"}}). Confirmed live: the server tolerates the token for
     *  some grace period past the JWT's own "exp" claim (a Dart client was
     *  observed reusing one 23s past exp successfully) before finally
     *  rejecting it this way - so callers should react to THIS, not to
     *  locally decoding the JWT's exp, which is unreliable as a cutoff. */
    public static boolean isInvalidToken(JSONObject body) {
        return body.optInt("statusCode", 0) == 401;
    }

    public static final String ACTION_OPEN = "AddRegistration";
    public static final String ACTION_WAITLIST = "AddWaitingList";
    public static final String ACTION_NOT_YET_OPEN = "AddReminder";
    public static final String ACTION_ALREADY_REGISTERED = "RemoveRegistration";

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String buildQuery(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(urlEncode(e.getKey())).append('=').append(urlEncode(e.getValue()));
        }
        return sb.toString();
    }

    private static JSONObject request(String method, String path, Map<String, String> params,
                                       Map<String, String> formBody, String bearerToken) throws IOException, org.json.JSONException {
        return request(method, path, params, formBody, null, bearerToken);
    }

    /** jsonBody, when non-null, is sent as an application/json request body
     *  (some endpoints, e.g. /classes/registration/remove, require JSON and
     *  reject form-urlencoded with 415). */
    private static JSONObject request(String method, String path, Map<String, String> params,
                                       Map<String, String> formBody, String jsonBody, String bearerToken) throws IOException, org.json.JSONException {
        String urlStr = BASE_URL + path;
        String query = params != null ? buildQuery(params) : "";
        if ("GET".equals(method) && !query.isEmpty()) {
            urlStr = urlStr + "?" + query;
        }
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Version", VERSION_HEADER);
        conn.setRequestProperty("DeviceId", deviceId);
        conn.setRequestProperty("Language", LANGUAGE_HEADER);
        // The real app's Dart/Flutter networking stack sends a "Dart/x.x
        // (dart:io)" User-Agent, not Java's default "Dalvik/..." one - trying
        // this in case a WAF rule on the OTP endpoint allowlists by UA pattern.
        conn.setRequestProperty("User-Agent", "Dart/3.9 (dart:io)");
        conn.setRequestProperty("Accept", "application/json");
        if (bearerToken != null) {
            conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        }

        if ("POST".equals(method)) {
            String body;
            if (jsonBody != null) {
                body = jsonBody;
                conn.setRequestProperty("Content-Type", "application/json");
            } else {
                body = formBody != null ? buildQuery(formBody) : "";
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            }
            byte[] bodyBytes = body.getBytes("UTF-8");
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(bodyBytes.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }
        }

        int status = conn.getResponseCode();
        InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String text = is != null ? readAll(is) : "";
        if (text.isEmpty()) {
            JSONObject fallback = new JSONObject();
            fallback.put("success", false);
            fallback.put("statusCode", status);
            return fallback;
        }
        return new JSONObject(text);
    }

    private static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = is.read(chunk)) != -1) buf.write(chunk, 0, n);
        return buf.toString("UTF-8");
    }

    // ---------------- auth ----------------

    public static void sendOtp(String phone) throws IOException, org.json.JSONException {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("OrganizationId", String.valueOf(ORGANIZATION_ID));
        p.put("companyId", String.valueOf(COMPANY_ID));
        p.put("branchId", String.valueOf(BRANCH_ID));
        p.put("sourceId", String.valueOf(SOURCE_ID));
        p.put("authenticationTypeId", String.valueOf(AUTHENTICATION_TYPE_ID));
        p.put("value", phone);
        JSONObject body = request("GET", "/login/Authentication", p, null, null);
        if (!body.optBoolean("success", false)) {
            throw new IOException(serverMessage(body, "Couldn't send the code. Please try again."));
        }
    }

    /** Pulls a human-readable message out of a failed response, falling back
     *  to a friendly default rather than dumping raw JSON to the user. */
    static String serverMessage(JSONObject body, String fallback) {
        String msg = body.optString("message", "");
        if (msg.isEmpty()) {
            JSONObject ex = body.optJSONObject("exception");
            if (ex != null) msg = ex.optString("message", "");
        }
        return msg.isEmpty() ? fallback : msg;
    }

    /** Returns {accessToken, refreshToken}. */
    public static String[] verifyOtp(String phone, String code) throws IOException, org.json.JSONException {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("verificationCode", code);
        p.put("OrganizationId", String.valueOf(ORGANIZATION_ID));
        p.put("companyId", String.valueOf(COMPANY_ID));
        p.put("branchId", String.valueOf(BRANCH_ID));
        p.put("sourceId", String.valueOf(SOURCE_ID));
        p.put("authenticationTypeId", String.valueOf(AUTHENTICATION_TYPE_ID));
        p.put("value", phone);
        JSONObject body = request("GET", "/login/Verification", p, null, null);
        if (!body.optBoolean("success", false)) {
            throw new IOException(serverMessage(body, "The code was wrong or expired. Please try again."));
        }
        JSONObject data = body.getJSONObject("data");
        return new String[]{data.getString("accessToken"), data.getString("refreshToken")};
    }

    /** Exchanges a refresh token for a fresh access token. Returns
     *  {accessToken, refreshToken} or null on failure.
     *
     *  Confirmed live against the real backend: this call succeeds with a
     *  plain HTTP request (the Dart User-Agent set in request() is required -
     *  without it the server returns a generic 503, same as the OTP
     *  endpoint). The response contains ONLY a new accessToken, no new
     *  refreshToken - and the original refresh token is reusable
     *  indefinitely - so we carry the existing refresh token forward. This is
     *  what makes "log in once, run unattended forever" actually work; App
     *  Check / Play Integrity is NOT required for this endpoint. */
    public static String[] refreshToken(String refreshToken) {
        try {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("refreshToken", refreshToken);
            form.put("OrganizationId", String.valueOf(ORGANIZATION_ID));
            form.put("companyId", String.valueOf(COMPANY_ID));
            form.put("branchId", String.valueOf(BRANCH_ID));
            form.put("sourceId", String.valueOf(SOURCE_ID));
            form.put("authenticationTypeId", String.valueOf(AUTHENTICATION_TYPE_ID));
            JSONObject body = request("POST", "/login/Token", null, form, null);
            if (!body.optBoolean("success", false)) return null;
            JSONObject data = body.getJSONObject("data");
            String newAccess = data.getString("accessToken");
            // Response usually omits refreshToken - reuse the current one.
            String newRefresh = data.optString("refreshToken", refreshToken);
            if (newRefresh == null || newRefresh.isEmpty()) newRefresh = refreshToken;
            return new String[]{newAccess, newRefresh};
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- data ----------------

    public static JSONArray getSchedule(String dateStr, String accessToken) throws IOException, org.json.JSONException {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("date", dateStr + " 00:00:00.000");
        JSONObject body = request("GET", "/classes/schedule/viewwithreplacements", p, null, accessToken);
        return body.getJSONObject("data").getJSONArray("list");
    }

    /** The customer(s) tied to this account that an entrance QR can be
     *  generated for - {success, data: {list: [{customerId, name}, ...]}}. */
    public static JSONObject getQrCustomersList(String accessToken) throws IOException, org.json.JSONException {
        return request("GET", "/qr/customerslist", null, null, accessToken);
    }

    /** Opens a new entrance-QR session for this customer -
     *  {success, data: {id: "<opaque token>"}} - the returned id is what
     *  gets encoded into the QR image and used as the first checkstatus id. */
    public static JSONObject createQr(int customerId, String accessToken) throws IOException, org.json.JSONException {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("id", String.valueOf(customerId));
        return request("GET", "/qr/createqr", p, null, accessToken);
    }

    /** Polls a live QR session. The server rotates the token on every call -
     *  {success, data: {id: "<next token>"}} - even while success stays
     *  false: the caller must display/poll with the returned id next, not
     *  the one it just sent. success flips true once the code is scanned
     *  at the door (not yet confirmed against a real scan). */
    public static JSONObject checkQrStatus(String qrId, int customerId, String accessToken) throws IOException, org.json.JSONException {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("id", qrId);
        p.put("customerId", String.valueOf(customerId));
        return request("GET", "/qr/checkstatus", p, null, accessToken);
    }

    /** Returns the raw response body (not just "data") so the caller can
     *  check success/statusCode itself - see isInvalidToken(). */
    public static JSONObject getClassDetailsRaw(int classId, String dateStr, String accessToken) throws IOException, org.json.JSONException {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("id", String.valueOf(classId));
        p.put("date", dateStr);
        return request("GET", "/classes/details", p, null, accessToken);
    }

    public static JSONObject register(int classId, String dateStr, int contractId, int purchaseId, String accessToken) throws IOException, org.json.JSONException {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("contractId", String.valueOf(contractId));
        p.put("purchaseId", String.valueOf(purchaseId));
        p.put("classId", String.valueOf(classId));
        p.put("classDate", dateStr);
        return request("GET", "/classes/registration/add", p, null, accessToken);
    }

    public static JSONObject joinWaitlist(int classId, String dateStr, String accessToken) throws IOException, org.json.JSONException {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("classId", String.valueOf(classId));
        p.put("classDate", dateStr);
        return request("GET", "/classes/waitinglist/add", p, null, accessToken);
    }

    /** Cancels an existing registration. Confirmed from the real app's
     *  traffic: POST /classes/registration/remove with form body id=<the
     *  registrationId> (the id returned when you registered, and present on
     *  registered classes in the schedule feed). Returns the raw response. */
    public static JSONObject removeRegistration(String registrationId, String accessToken) throws IOException, org.json.JSONException {
        String json = "{\"id\":" + registrationId + "}";
        return request("POST", "/classes/registration/remove", null, null, json, accessToken);
    }
}
