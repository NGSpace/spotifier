package dev.ngspace.hudder.spotifier.spotifyapi;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONArray;
import org.json.JSONObject;

public class SpotifyAPI {
    private SpotifyAPI() {}

    // --- HTTP + caching state -------------------------------------------------

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final AtomicReference<Optional<NowPlaying>> CACHE =
            new AtomicReference<>(Optional.empty());
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);

    private static final String URL_CURRENTLY_PLAYING = "https://api.spotify.com/v1/me/player/currently-playing";
    private static final String URL_PLAYER            = "https://api.spotify.com/v1/me/player";
    private static final String URL_QUEUE             = "https://api.spotify.com/v1/me/player/queue";
    private static final String URL_PLAYLIST_BASE     = "https://api.spotify.com/v1/playlists/";
    private static final String URL_OPEN_PLAYLIST     = "https://open.spotify.com/playlist/";
    private static final String URL_OPEN_LIKED        = "https://open.spotify.com/collection/tracks";

    // --- Public API -----------------------------------------------------------

    /** Returns cached NowPlaying immediately and asynchronously fetches new data. */
    public static Optional<NowPlaying> fetchAndReturnPrevious(String accessToken) {
        Optional<NowPlaying> previous = CACHE.get();

        if (IN_FLIGHT.compareAndSet(false, true)) {
            requestAsync(accessToken)
            		.exceptionally(ex -> null)
                    .thenAccept(CACHE::set)
                    .whenComplete((__, ___) -> IN_FLIGHT.set(false));
        }

        return previous;
    }

    // --- Orchestration --------------------------------------------------------

    private static CompletableFuture<Optional<NowPlaying>> requestAsync(String accessToken) {

        // Fire requests (player/queue = best-effort)
        CompletableFuture<HttpResponse<String>> fCurrent = sendAsyncString(buildGet(accessToken, URL_CURRENTLY_PLAYING));
        CompletableFuture<HttpResponse<String>> fPlayer  = sendAsyncStringSafe(buildGet(accessToken, URL_PLAYER));
        CompletableFuture<HttpResponse<String>> fQueue   = sendAsyncStringSafe(buildGet(accessToken, URL_QUEUE));

        // Compose results
        return fCurrent.thenCompose(respCur -> {
            Optional<String> curBody = validateCurrentlyPlaying(respCur);
            if (curBody.isEmpty()) return CompletableFuture.completedFuture(Optional.empty());

            // Parse the "currently playing" response into structured bits
            Current parse = parseCurrentlyPlaying(curBody.get());
            if (parse.itemJson == null) return CompletableFuture.completedFuture(Optional.empty());

            TrackFields track = extractTrack(parse.itemJson);
            ContextFields ctx = extractContext(parse.contextJson);

            // Possibly fetch playlist name if it's a real playlist
            CompletableFuture<String> fPlaylistName =
                    resolvePlaylistName(accessToken, ctx.playlistId, ctx.playlistName);

            // Fetch player state and queue
            CompletableFuture<PlayerState> fState = fetchPlayerState(fPlayer);
            CompletableFuture<NextSong[]> fNext = fetchQueue(fQueue);

            return CompletableFuture.allOf(fPlaylistName, fState, fNext)
                    .thenApply(__ -> {
                        String resolvedPlaylistName = fPlaylistName.join();
                        PlayerState sr = fState.join();
                        NextSong[] nextSongs = fNext.join();

                        return Optional.of(buildNowPlaying(parse, track, ctx, resolvedPlaylistName, sr, nextSongs));
                    });
        });
    }

    // --- HTTP helpers ---------------------------------------------------------

    private static HttpRequest buildGet(String accessToken, String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
    }

    private static CompletableFuture<HttpResponse<String>> sendAsyncString(HttpRequest req) {
        return CLIENT.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static CompletableFuture<HttpResponse<String>> sendAsyncStringSafe(HttpRequest req) {
        return sendAsyncString(req).exceptionally(ex -> null);
    }

    private static Optional<String> validateCurrentlyPlaying(HttpResponse<String> resp) {
        if (resp == null) return Optional.empty();
        int code = resp.statusCode();
        if (code == 204) return Optional.empty();
        if (code != 200) {
            throw new UnsupportedOperationException("HTTP " + code + " - " + resp.body());
        }
        return Optional.ofNullable(resp.body());
    }

    // --- Parsing: currently-playing ------------------------------------------

    private static Current parseCurrentlyPlaying(String body) {
        JSONObject json = new JSONObject(body);
        boolean isPlaying = json.optBoolean("is_playing", false);
        long progress = json.optLong("progress_ms", 0);
        JSONObject item = json.optJSONObject("item");
        JSONObject context = json.optJSONObject("context");
        return new Current(isPlaying, progress, item, context, Instant.now());
    }

    private static TrackFields extractTrack(JSONObject item) {
        String name = item.optString("name", null);
        long duration = item.optLong("duration_ms", 0);

        JSONObject albumObj = item.optJSONObject("album");
        String album = albumObj != null ? albumObj.optString("name", "") : "";
        String albumType = albumObj != null ? albumObj.optString("album_type", "") : "";

        JSONArray artistsArr = item.optJSONArray("artists");
        String[] artists = parseArtists(artistsArr);

        String trackUrl = null;
        JSONObject ext = item.optJSONObject("external_urls");
        if (ext != null) trackUrl = ext.optString("spotify", null);

        return new TrackFields(name, duration, album, albumType, artists, trackUrl);
    }

    private static String[] parseArtists(JSONArray artistsArr) {
        if (artistsArr == null) return new String[0];
        String[] out = new String[artistsArr.length()];
        for (int i = 0; i < out.length; i++) {
            out[i] = artistsArr.getJSONObject(i).optString("name", "");
        }
        return out;
    }

    // --- Parsing: context (playlist vs liked songs) ---------------------------

    private static ContextFields extractContext(JSONObject context) {
        if (context == null) return new ContextFields(null, null, null);

        String ctxType = context.optString("type", "");
        String ctxUri  = context.optString("uri", "");
        String ctxHref = context.optString("href", null);

        // Playlist
        if ("playlist".equalsIgnoreCase(ctxType)) {
            String playlistId = findPlaylistId(ctxUri, ctxHref);
            if (playlistId != null) {
                return new ContextFields(playlistId, URL_OPEN_PLAYLIST + playlistId, null);
            }
        }

        // Liked Songs "collection"
        boolean isCollection = "collection".equalsIgnoreCase(ctxType)
                || (ctxUri != null && (ctxUri.startsWith("spotify:collection") || ctxUri.contains(":collection")));
        if (isCollection) {
            return new ContextFields("collection:tracks", URL_OPEN_LIKED, "Liked Songs");
        }

        // Unknown or no context
        return new ContextFields(null, null, null);
    }

    private static String findPlaylistId(String ctxUri, String ctxHref) {
        if (ctxUri != null && ctxUri.startsWith("spotify:playlist:")) {
            return ctxUri.substring("spotify:playlist:".length());
        }
        if (ctxHref != null) {
            int idx = ctxHref.lastIndexOf("/playlists/");
            if (idx >= 0) {
                return ctxHref.substring(idx + "/playlists/".length());
            }
        }
        return null;
    }

    private static CompletableFuture<String> resolvePlaylistName(String accessToken, String playlistId, String name) {
        if (playlistId == null || "collection:tracks".equals(playlistId)) {
            return CompletableFuture.completedFuture(name);
        }
        return fetchPlaylistName(accessToken, playlistId).exceptionally(ex -> null);
    }

    // --- Fetch helpers: player state + queue + playlist name ------------------

    private static CompletableFuture<PlayerState> fetchPlayerState(CompletableFuture<HttpResponse<String>> fPlayer) {
        return fPlayer.thenApply(resp -> {
            boolean shuffle = false;
            String repeat = "off";
            if (resp != null && resp.statusCode() == 200) {
                JSONObject pj = new JSONObject(resp.body());
                shuffle = pj.optBoolean("shuffle_state", false);
                repeat = pj.optString("repeat_state", "off");
            }
            return new PlayerState(shuffle, repeat);
        });
    }

    private static CompletableFuture<NextSong[]> fetchQueue(CompletableFuture<HttpResponse<String>> fQueue) {
        return fQueue.thenApply(resp -> {
            if (resp == null || resp.statusCode() != 200) return new NextSong[0];

            JSONObject q = new JSONObject(resp.body());
            JSONArray queueArr = q.optJSONArray("queue");
            if (queueArr == null) return new NextSong[0];

            NextSong[] out = new NextSong[queueArr.length()];
            for (int i = 0; i < queueArr.length(); i++) {
                JSONObject t = queueArr.optJSONObject(i);
                if (t == null) continue;

                String tName = t.optString("name", null);
                long tDuration = t.optLong("duration_ms", 0);

                JSONObject tAlbumObj = t.optJSONObject("album");
                String tAlbum = tAlbumObj != null ? tAlbumObj.optString("name", "") : "";
                String tAlbumType = tAlbumObj != null ? tAlbumObj.optString("album_type", "") : "";

                String[] tArtists = parseArtists(t.optJSONArray("artists"));

                String tUrl = null;
                JSONObject tExt = t.optJSONObject("external_urls");
                if (tExt != null) tUrl = tExt.optString("spotify", null);

                out[i] = new NextSong(tName, tArtists, tAlbum, tUrl, tDuration, tAlbumType);
            }
            return out;
        });
    }

    private static CompletableFuture<String> fetchPlaylistName(String accessToken, String playlistId) {
        HttpRequest req = buildGet(accessToken, URL_PLAYLIST_BASE + playlistId);
        return CLIENT.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) {
                        throw new IllegalArgumentException("Playlist fetch failed: HTTP " + resp.statusCode());
                    }
                    JSONObject json = new JSONObject(resp.body());
                    return json.optString("name", null);
                });
    }

    private static NowPlaying buildNowPlaying(Current cur, TrackFields track, ContextFields ctx,
    		String resolvedPlaylistName, PlayerState state, NextSong[] nextSongs) {
        boolean shuffle = state.shuffle();
        String repeat  = state.repeat();
        
        String playlistID = null;
        String playlistName = null;
        String playlistURL = null;

        if (ctx.playlistId != null) {
            if ("collection:tracks".equals(ctx.playlistId)) {
            	playlistID = ctx.playlistId;
            	playlistName = "Liked Songs";
            	playlistURL = URL_OPEN_LIKED;
            } else {
            	playlistID = ctx.playlistId;
            	playlistName = resolvedPlaylistName;
            	playlistURL = ctx.playlistUrl;
            }
        }

        return new NowPlaying(
                cur.isPlaying, track.name, track.artists, track.album, track.trackUrl,
                cur.progressMs, track.durationMs,
                playlistID, playlistName, playlistURL,
                track.albumType,
                shuffle, repeat, nextSongs,
                cur.pullTime()
        );
    }

    // --- Tiny carrier types to keep methods clean -----------------------------
    
    public static record PlayerState(boolean shuffle, String repeat) {}
    public static record Current(boolean isPlaying, long progressMs, JSONObject itemJson, JSONObject contextJson, Instant pullTime) {}
    public static record TrackFields(String name, long durationMs, String album, String albumType, String[] artists, String trackUrl) {}
    public static record ContextFields(String playlistId, String playlistUrl, String playlistName) {}
}
