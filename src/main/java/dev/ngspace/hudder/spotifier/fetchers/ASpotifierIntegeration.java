package dev.ngspace.hudder.spotifier.fetchers;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import dev.ngspace.hudder.spotifier.config.SpotifierConfig;
import dev.ngspace.hudder.spotifier.spotifyapi.NowPlaying;

public abstract class ASpotifierIntegeration {
	
	Instant lastupdate = Instant.now();
	Optional<NowPlaying> data;

	public Optional<NowPlaying> getNowPlaying() {
		Instant now = Instant.now();
		if (Duration.between(lastupdate, now).toMillis()>SpotifierConfig.pull_rate) {//Has the data timed out?
			data = fetch();
			lastupdate = Instant.now();
		}
		return data;
	}
	
	protected abstract Optional<NowPlaying> fetch();

	public abstract boolean isValid();

	public abstract boolean canReauth();

	public abstract void reauth() throws IOException;

	public abstract boolean canReadData();

	public abstract void refreshAllTokens();

	public abstract void init();
}
