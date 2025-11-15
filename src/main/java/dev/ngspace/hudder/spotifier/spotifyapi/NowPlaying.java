package dev.ngspace.hudder.spotifier.spotifyapi;

public record NowPlaying(
    boolean isPlaying,
    String trackName,
    String[] artists,
    String albumName,
    String trackUrl,
    long progressMs,
    long durationMs,
    String playlistId,
    String playlistName,
    String playlistUrl,
    String albumType,
    boolean shuffle,
    String repeat,
    NextSong[] nextSongs
) {}
