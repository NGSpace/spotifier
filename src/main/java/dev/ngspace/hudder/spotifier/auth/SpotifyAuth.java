package dev.ngspace.hudder.spotifier.auth;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.json.JSONObject;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import dev.ngspace.hudder.spotifier.SpotifierUtil;
import dev.ngspace.hudder.spotifier.config.SpotifierConfig;

/**
 * Minimal, reusable Spotify Authorization Code + PKCE helper.
 * - No client secret required
 * - Local HTTP callback server
 * - Token exchange + refresh
 */
public class SpotifyAuth {

    private final String clientId;
    private final URI redirectUri;        // e.g., http://127.0.0.1:8888/callback
    private final int listenPort;         // e.g., 8888
    private final Duration callbackWait = Duration.ofMinutes(5);  // max time to wait for the redirect

    // PKCE + CSRF state
    private String codeVerifier;
    private String codeChallenge;
    private String state;
	private SpotifyToken tokens;

    public SpotifyAuth(String clientId, URI redirectUri, int listenPort) {
        this.clientId = clientId;
        this.redirectUri = redirectUri;
        this.listenPort = listenPort;
        this.codeVerifier = Verifier.generateCodeVerifier();
        this.codeChallenge = Verifier.generateCodeChallenge(this.codeVerifier);
        this.state = Verifier.randomUrlSafe(24);
    }

    /** Build the authorization URL you can show/open in a browser. */
    public URI getAuthURI(String[] scopes) {
        String scopeStr = String.join(" ", scopes == null ? new String[0] : scopes);
        String base = "https://accounts.spotify.com/authorize" +
                "?response_type=code" +
                "&client_id=" + SpotifierUtil.encode(clientId) +
                "&redirect_uri=" + SpotifierUtil.encode(redirectUri.toString()) +
                "&scope=" + SpotifierUtil.encode(scopeStr) +
                "&code_challenge_method=S256" +
                "&code_challenge=" + SpotifierUtil.encode(codeChallenge) +
                "&state=" + SpotifierUtil.encode(state);
        return URI.create(base);
    }

    /**
     * Starts a lightweight local HTTP server on listenPort, waits for the redirect,
     * validates state, returns the "code". Throws on timeout or error.
     * @throws IOException 
     * @throws InterruptedException 
     */
    public String awaitAuth() throws IOException, InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        final StringBuilder resultCode = new StringBuilder();
        final StringBuilder resultError = new StringBuilder();
        final String expectedState = this.state;

        HttpServer server = HttpServer.create(new InetSocketAddress(listenPort), 0);
        server.createContext(redirectUri.getPath(), new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String query = exchange.getRequestURI().getQuery();
                Map<String, String> params = SpotifierUtil.splitQuery(query);
                String code = params.get("code");
                String error = params.get("error");
                String gotState = params.get("state");

                String html;
                if (error != null) {
                    resultError.append(error);
                    html = "Authentication failed: " + error;
                } else if (code == null) {
                    resultError.append("missing_code");
                    html = "Authentication failed: missing authorization code.";
                } else if (expectedState != null && !expectedState.equals(gotState)) {
                    resultError.append("state_mismatch");
                    html = "Authentication failed: state mismatch.";
                } else {
                    resultCode.append(code);
                    html = "Authentication successful.";
                }
                
                String response = "<script>setTimeout(function() {window.close();}, 2000);</script>" + html;
                
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes(StandardCharsets.UTF_8));
                } finally {
                    latch.countDown();
                }
            }
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        boolean wasontime = latch.await(callbackWait.toMillis(), TimeUnit.MILLISECONDS);
        server.stop(0);

        if (!wasontime)
            throw new IOException("Timed out waiting for authorization redirect on " + redirectUri);
        if (!resultError.isEmpty())
            throw new IOException("Authorization error: " + resultError);
        if (resultCode.isEmpty())
            throw new IOException("Authorization failed: no code received.");
        return resultCode.toString();
    }

    public void fetchTokenFromClientID(String code) throws IOException {
        String postData = "grant_type=authorization_code" +
                "&code=" + SpotifierUtil.encode(code) +
                "&redirect_uri=" + SpotifierUtil.encode(redirectUri.toString()) +
                "&client_id=" + SpotifierUtil.encode(clientId) +
                "&code_verifier=" + SpotifierUtil.encode(codeVerifier);

        JSONObject json = SpotifierUtil.postForm("https://accounts.spotify.com/api/token", postData);
        this.tokens = SpotifyToken.fromJSONObject(json);
        SpotifierConfig.refresh_token = tokens.refreshToken();
        SpotifierConfig.save();
    }

    public void refreshAccessToken(String refreshToken) throws IOException {
        String postData = "grant_type=refresh_token" +
                "&refresh_token=" + SpotifierUtil.encode(refreshToken) +
                "&client_id=" + SpotifierUtil.encode(clientId);

        JSONObject json = SpotifierUtil.postForm("https://accounts.spotify.com/api/token", postData);
        this.tokens = SpotifyToken.fromJSONObject(json);
        SpotifierConfig.refresh_token = tokens.refreshToken();
        SpotifierConfig.save();
    }
    
    public String getAccessToken() {
    	return tokens.accessToken();
    }
}