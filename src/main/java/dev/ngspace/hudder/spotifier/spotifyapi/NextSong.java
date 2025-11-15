package dev.ngspace.hudder.spotifier.spotifyapi;

public record NextSong(
    String trackName,
    String[] artists,
    String albumName,
    String trackUrl,
    long durationMs,
    String albumType
) {}
