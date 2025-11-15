package dev.ngspace.hudder.spotifier.spotifyapi;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONArray;
import org.json.JSONObject;

public class SpotifyAsyncApi {
    private SpotifyAsyncApi() {}

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
            CompletableFuture<boolean[]> fState = fetchPlayerState(fPlayer);
            CompletableFuture<NextSong[]> fNext = fetchQueue(fQueue);

            return CompletableFuture.allOf(fPlaylistName, fState, fNext)
                    .thenApply(__ -> {
                        String resolvedPlaylistName = fPlaylistName.join();
                        boolean[] sr = fState.join();
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
            throw new UnsupportedOperationException("HTTP " + code + " - " + snippet(resp.body()));
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
        return new Current(isPlaying, progress, item, context);
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

    private static CompletableFuture<String> resolvePlaylistName(String accessToken,
                                                                 String playlistId,
                                                                 String existingName) {
        if (playlistId == null || "collection:tracks".equals(playlistId)) {
            return CompletableFuture.completedFuture(existingName);
        }
        return fetchPlaylistName(accessToken, playlistId).exceptionally(ex -> null);
    }

    // --- Fetch helpers: player state + queue + playlist name ------------------

    private static CompletableFuture<boolean[]> fetchPlayerState(CompletableFuture<HttpResponse<String>> fPlayer) {
        return fPlayer.thenApply(resp -> {
            boolean shuffle = false;
            boolean repeat = false;
            if (resp != null && resp.statusCode() == 200) {
                JSONObject pj = new JSONObject(resp.body());
                shuffle = pj.optBoolean("shuffle_state", false);
                String repeatState = pj.optString("repeat_state", "off");
                repeat = !"off".equalsIgnoreCase(repeatState);
            }
            return new boolean[]{shuffle, repeat};
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

    // --- Assembly -------------------------------------------------------------

    private static NowPlaying buildNowPlaying(Current cur,
                                              TrackFields track,
                                              ContextFields ctx,
                                              String resolvedPlaylistName,
                                              boolean[] state,
                                              NextSong[] nextSongs) {
        boolean shuffle = state[0];
        boolean repeat  = state[1];

        // No playlist context
        if (ctx.playlistId == null) {
            return new NowPlaying(
                    cur.isPlaying, track.name, track.artists, track.album, track.trackUrl,
                    cur.progressMs, track.durationMs,
                    null, null, null,
                    track.albumType,
                    shuffle, repeat, nextSongs
            );
        }

        // Liked Songs
        if ("collection:tracks".equals(ctx.playlistId)) {
            return new NowPlaying(
                    cur.isPlaying, track.name, track.artists, track.album, track.trackUrl,
                    cur.progressMs, track.durationMs,
                    ctx.playlistId, "Liked Songs", URL_OPEN_LIKED,
                    track.albumType,
                    shuffle, repeat, nextSongs
            );
        }

        // Real playlist
        return new NowPlaying(
                cur.isPlaying, track.name, track.artists, track.album, track.trackUrl,
                cur.progressMs, track.durationMs,
                ctx.playlistId, resolvedPlaylistName, ctx.playlistUrl,
                track.albumType,
                shuffle, repeat, nextSongs
        );
    }

    // --- Utilities ------------------------------------------------------------

    private static String snippet(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 180 ? s.substring(0, 180) + "…" : s;
    }

    // --- Tiny carrier types to keep methods clean -----------------------------

    private static final class Current {
        final boolean isPlaying;
        final long progressMs;
        final JSONObject itemJson;
        final JSONObject contextJson;

        Current(boolean isPlaying, long progressMs, JSONObject itemJson, JSONObject contextJson) {
            this.isPlaying = isPlaying;
            this.progressMs = progressMs;
            this.itemJson = itemJson;
            this.contextJson = contextJson;
        }
    }

    private static final class TrackFields {
        final String name;
        final long durationMs;
        final String album;
        final String albumType;
        final String[] artists;
        final String trackUrl;

        TrackFields(String name, long durationMs, String album, String albumType, String[] artists, String trackUrl) {
            this.name = name;
            this.durationMs = durationMs;
            this.album = album;
            this.albumType = albumType;
            this.artists = artists;
            this.trackUrl = trackUrl;
        }
    }

    private static final class ContextFields {
        final String playlistId;   // null | "collection:tracks" | real ID
        final String playlistUrl;  // null | open.spotify.com URL
        final String playlistName; // null | "Liked Songs" | fetched name

        ContextFields(String playlistId, String playlistUrl, String playlistName) {
            this.playlistId = playlistId;
            this.playlistUrl = playlistUrl;
            this.playlistName = playlistName;
        }
    }
}
