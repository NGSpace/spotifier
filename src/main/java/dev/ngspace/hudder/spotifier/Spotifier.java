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

import dev.ngspace.hudder.spotifier.auth.SpotifyAuth;
import dev.ngspace.hudder.spotifier.config.SpotifierConfig;
import dev.ngspace.hudder.spotifier.spotifyapi.NowPlaying;
import dev.ngspace.hudder.spotifier.spotifyapi.SpotifyAsyncApi;
import io.github.ngspace.hudder.data_management.api.DataVariable;
import io.github.ngspace.hudder.data_management.api.DataVariableRegistry;
import io.github.ngspace.hudder.data_management.api.VariableTypes;
import io.github.ngspace.hudder.main.HudCompilationManager;
import io.github.ngspace.hudder.utils.ValueGetter;
import net.fabricmc.api.ModInitializer;
import net.minecraft.Util;

public class Spotifier implements ModInitializer {
	
	public static final String MOD_ID = "spotifier";
	public static final String[] SCOPES = {"user-read-currently-playing", "user-read-playback-state", "playlist-read-private"};

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	
	private static final Object AUTH_LOCK = new Object();
	private static SpotifyAuth auth;
	private NowPlaying playing;
	
	private Instant lastRefresh = Instant.now();

	@Override
	public void onInitialize() {
		LOGGER.info("Loading Spotifier");
		DataVariableRegistry.registerVariable(k->true, VariableTypes.BOOLEAN, "has_spotifier");
		DataVariableRegistry.registerVariable(k->isValid(), VariableTypes.BOOLEAN, "spotifier_connected");
		DataVariableRegistry.registerVariable(k->playing, VariableTypes.OBJECT, "spotifier");

		registerVariable(k->!playing.isPlaying(), VariableTypes.BOOLEAN, "spotifier_paused");
		registerVariable(k->!playing.isPlaying(), VariableTypes.BOOLEAN, "spotifier_repeat");
		registerVariable(k->!playing.isPlaying(), VariableTypes.BOOLEAN, "spotifier_shuffle");
		
		registerVariable(k->playing.trackName(), VariableTypes.STRING, "spotifier_track");
		registerVariable(k->playing.albumName(), VariableTypes.STRING, "spotifier_album");
		registerVariable(k->playing.albumType(), VariableTypes.STRING, "spotifier_album_type");
		registerVariable(k->playing.albumType(), VariableTypes.STRING, "spotifier_playlist");
		
		registerVariable(k->playing.artists(), VariableTypes.OBJECT, "spotifier_artists");

		registerVariable(k->playing.progressMs(), VariableTypes.NUMBER, "spotifier_progress");
		registerVariable(k->playing.durationMs(), VariableTypes.NUMBER, "spotifier_duration");
		
		registerVariable(k->Arrays.stream(playing.nextSongs())
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
				.toArray(), VariableTypes.OBJECT, "spotifier_queue");
		SpotifierConfig.read();
		
		try {
			if (SpotifierConfig.refresh_token!=null)
				reauth();
		} catch (IOException e) {
			log("Failed to auth with refresh token");
			e.printStackTrace();
		}
		
		RateLimitedVariable<Optional<NowPlaying>> getther = new RateLimitedVariable<Optional<NowPlaying>>(()-> {
			synchronized (AUTH_LOCK) {
				if (isValid())
					return SpotifyAsyncApi.fetchAndReturnPrevious(auth.getAccessToken());
				return Optional.empty();
			}
		});
		
		HudCompilationManager.addPreCompilerListener(com->{
			if (isValid())
				playing=getther.get().orElse(null);
			if (Duration.between(lastRefresh, Instant.now()).toMinutes()>=10) {
				try {
					reauth();
				} catch (IOException e) {
					e.printStackTrace();
				}
				lastRefresh = Instant.now();
			}
		});
	}

	public void registerVariable(DataVariable<Object> variable, VariableTypes.Type<?> type, String... names) {
		DataVariableRegistry.registerVariable(key->{
			if (SpotifierConfig.client_id==null||!isValid())
				throw new NullPointerException("Client ID not set");
			if (playing==null)
				return null;
			return variable.getValue(key);
		}, type, names);
	}
	
	public static void log(Object obj) {LOGGER.info(String.valueOf(obj));}

	public static void refreshAllTokens() {
		synchronized (AUTH_LOCK) {
			log("Getting new tokens");
			
			auth = new SpotifyAuth(SpotifierConfig.client_id, SpotifierConfig.uri, SpotifierConfig.port);

			URI url = auth.getAuthURI(SCOPES);
			log("Spotifier auth url:\n" + url);
			Util.getPlatform().openUri(url);
			
			new Thread(()->{
				try {
					auth.fetchTokenFromClientID(auth.awaitAuth());
				} catch (Exception e) {
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