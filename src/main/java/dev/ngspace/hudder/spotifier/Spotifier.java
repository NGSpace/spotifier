package dev.ngspace.hudder.spotifier;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.ngspace.hudder.api.variableregistry.DataVariable;
import dev.ngspace.hudder.api.variableregistry.DataVariableRegistry;
import dev.ngspace.hudder.spotifier.auth.SpotifyAuth;
import dev.ngspace.hudder.spotifier.config.SpotifierConfig;
import dev.ngspace.hudder.spotifier.spotifyapi.NowPlaying;
import dev.ngspace.hudder.spotifier.spotifyapi.SpotifyAPI;
import dev.ngspace.hudder.utils.ValueGetter;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.util.Util;

public class Spotifier implements ModInitializer {
	
	public static final String MOD_ID = "spotifier";
	public static final String[] SCOPES = {"user-read-currently-playing", "user-read-playback-state", "playlist-read-private"};

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	
	private static final Object AUTH_LOCK = new Object();
	private static SpotifyAuth auth;
	private NowPlaying playing;
	
	private Instant lastRefresh = Instant.now();
	
	RateLimitedVariable<Optional<NowPlaying>> apifetcher = new RateLimitedVariable<Optional<NowPlaying>>(()-> {
		synchronized (AUTH_LOCK) {
			if (isValid())
				return SpotifyAPI.fetchAndReturnPrevious(auth.getAccessToken());
			return Optional.empty();
		}
	});

	@Override
	public void onInitialize() {
		LOGGER.info("Loading Spotifier");
		DataVariableRegistry.registerBooleanVariable(_->true, "has_spotifier");
		DataVariableRegistry.registerBooleanVariable(_->isValid(), "spotifier_connected");
		DataVariableRegistry.registerObjectVariable(_->playing, "spotifier");

		
		DataVariableRegistry.registerBooleanVariable(wrap(_->!playing.isPlaying()), "spotifier_paused");
		DataVariableRegistry.registerBooleanVariable(wrap(_->playing.shuffle()), "spotifier_shuffle");

		DataVariableRegistry.registerStringVariable(wrap(_->playing.repeat()), "spotifier_repeat");
		DataVariableRegistry.registerStringVariable(wrap(_->playing.trackName()), "spotifier_track");
		DataVariableRegistry.registerStringVariable(wrap(_->playing.albumName()), "spotifier_album");
		DataVariableRegistry.registerStringVariable(wrap(_->playing.albumType()), "spotifier_album_type");
		DataVariableRegistry.registerStringVariable(wrap(_->playing.playlistName()), "spotifier_playlist");
		
		DataVariableRegistry.registerObjectVariable(wrap(_->playing.artists()), "spotifier_artists");

		DataVariableRegistry.registerNumberVariable(wrap(_->playing.progressMs()), "spotifier_progress");
		DataVariableRegistry.registerNumberVariable(wrap(_->playing.durationMs()), "spotifier_duration");
		DataVariableRegistry.registerNumberVariable(wrap(_->Duration.between(playing.pullTime(), Instant.now()).toMillis()), "spotifier_data_age");
		
		DataVariableRegistry.registerObjectVariable(wrap(_->Arrays.stream(playing.nextSongs())
				.map(song -> (ValueGetter) key -> 
					switch (key) {
						case "track" -> song.trackName();
						case "artists" -> song.artists();
						case "album" -> song.albumName();
						case "duration" -> song.durationMs();
						case "album_type" -> song.albumType();
						default -> null;
					}
				)
				.toArray()), "spotifier_queue");
		SpotifierConfig.read();
		
		try {
			if (SpotifierConfig.refresh_token!=null)
				reauth();
		} catch (IOException e) {
			log("Failed to auth with refresh token");
			e.printStackTrace();
		}

		ClientTickEvents.START_CLIENT_TICK.register(_->{
			try {
				if (isValid())
					playing=apifetcher.get().orElse(null);
				if (Duration.between(lastRefresh, Instant.now()).toMinutes()>=30) {
					try {
						reauth();
					} catch (IOException e) {
						e.printStackTrace();
					}
					lastRefresh = Instant.now();
				}
			} catch (RuntimeException e) {
				e.printStackTrace();
				throw e;
			}
		});
	}

	public <T> DataVariable<T> wrap(DataVariable<T> variable) {
		return key->{
			if (SpotifierConfig.client_id==null||!isValid())
				throw new SpotifierException("Client ID not set");
			if (playing==null)
				return null;
			return variable.getValue(key);
		};
	}
	
	public static void log(Object obj) {LOGGER.info(String.valueOf(obj));}

	public static void refreshAllTokens() {
		synchronized (AUTH_LOCK) {
			log("Getting new tokens");
			
			if (SpotifierConfig.client_id==null||SpotifierConfig.client_id.isBlank())
				throw new SpotifierException("Client ID is null or empty");
			
			auth = new SpotifyAuth(SpotifierConfig.client_id, SpotifierConfig.uri, SpotifierConfig.port);

			URI url = auth.getAuthURI(SCOPES);
			log("Spotifier auth url:\n" + url);
			Util.getPlatform().openUri(url);
			
			new Thread(()->{
				try {
					auth.fetchTokenFromClientID(auth.awaitAuth());
				} catch (Exception e) {
					auth = null;
					e.printStackTrace();
				}
			}).start();
		}
	}

	public static void reauth(String refreshToken) throws IOException {
		synchronized (AUTH_LOCK) {
			log("Refreshing Spotify access token");
			SpotifyAuth newauth = new SpotifyAuth(SpotifierConfig.client_id, SpotifierConfig.uri, SpotifierConfig.port);
			newauth.refreshAccessToken(refreshToken);
			auth = newauth;
		}
	}
	
	private static void reauth() throws IOException {
		reauth(SpotifierConfig.refresh_token);
	}
	
	public static boolean isValid() {
		return auth!=null;
	}
	
	/**
	 * I was originally using LimitedRefreshSpeedData<T> but I don't want to rely too heavily on Hudder...
	 * So I just copy pasted it here and made some changes
	 */
	static class RateLimitedVariable<T> {
		
		Instant lastupdate = Instant.now();
		T data;
		Supplier<T> updater;
		
		public RateLimitedVariable(Supplier<T> updater) {
			this.updater = updater;
			this.data = updater.get();
		}
		
		public T get() {
			Instant now = Instant.now();
			if (Duration.between(lastupdate, now).toMillis()>SpotifierConfig.pull_rate) {//Has the data timed out?
				data = updater.get();
				lastupdate = Instant.now();
			}
			return data;
		}
	}
}