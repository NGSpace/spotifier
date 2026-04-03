package dev.ngspace.hudder.spotifier;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.ngspace.hudder.api.variableregistry.DataVariable;
import dev.ngspace.hudder.api.variableregistry.DataVariableRegistry;
import dev.ngspace.hudder.api.variableregistry.VariableTypes;
import dev.ngspace.hudder.main.HudCompilationManager;
import dev.ngspace.hudder.spotifier.config.SpotifierConfig;
import dev.ngspace.hudder.spotifier.fetchers.ASpotifierIntegeration;
import dev.ngspace.hudder.spotifier.fetchers.SpotifyIntegeration;
import dev.ngspace.hudder.spotifier.spotifyapi.NowPlaying;
import dev.ngspace.hudder.utils.ValueGetter;
import net.fabricmc.api.ModInitializer;

public class Spotifier implements ModInitializer {
	
	public static final String MOD_ID = "spotifier";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	
	private NowPlaying playing;
	
	private Instant lastRefresh = Instant.now();
	
	public static ASpotifierIntegeration apifetcher;

	@Override
	public void onInitialize() {
		
		LOGGER.info("Loading Spotifier");
		DataVariableRegistry.registerVariable(_->true, VariableTypes.BOOLEAN, "has_spotifier");
		DataVariableRegistry.registerVariable(_->apifetcher.isValid(), VariableTypes.BOOLEAN, "spotifier_connected");
		DataVariableRegistry.registerVariable(_->playing, VariableTypes.OBJECT, "spotifier");

		
		registerVariable(_->!playing.isPlaying(), VariableTypes.BOOLEAN, "spotifier_paused");
		registerVariable(_->playing.shuffle(), VariableTypes.BOOLEAN, "spotifier_shuffle");

		registerVariable(_->playing.repeat(), VariableTypes.STRING, "spotifier_repeat");
		registerVariable(_->playing.trackName(), VariableTypes.STRING, "spotifier_track");
		registerVariable(_->playing.albumName(), VariableTypes.STRING, "spotifier_album");
		registerVariable(_->playing.albumType(), VariableTypes.STRING, "spotifier_album_type");
		registerVariable(_->playing.playlistName(), VariableTypes.STRING, "spotifier_playlist");
		
		registerVariable(_->playing.artists(), VariableTypes.OBJECT, "spotifier_artists");

		registerVariable(_->playing.progressMs(), VariableTypes.NUMBER, "spotifier_progress");
		registerVariable(_->playing.durationMs(), VariableTypes.NUMBER, "spotifier_duration");
		registerVariable(_->Duration.between(playing.pullTime(), Instant.now()).toMillis(), VariableTypes.NUMBER, "spotifier_data_age");
		
		registerVariable(_->Arrays.stream(playing.nextSongs())
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

		apifetcher = SpotifyIntegeration.INSTANCE;
		apifetcher.init();
		
		HudCompilationManager.addPreCompilerListener(_->{
			if (apifetcher.isValid())
				playing=apifetcher.getNowPlaying().orElse(null);
			if (Duration.between(lastRefresh, Instant.now()).toMinutes()>=10) {
				try {
					apifetcher.reauth();
				} catch (IOException e) {
					e.printStackTrace();
				}
				lastRefresh = Instant.now();
			}
		});
	}

	public void registerVariable(DataVariable<Object> variable, VariableTypes.Type<?> type, String... names) {
		DataVariableRegistry.registerVariable(key->{
			if (!apifetcher.canReadData())
				throw new SpotifierException("Client ID not set");
			if (playing==null)
				return null;
			return variable.getValue(key);
		}, type, names);
	}
	
	public static void log(Object obj) {LOGGER.info(String.valueOf(obj));}
}