package dev.ngspace.hudder.spotifier.fetchers;

import java.io.IOException;
import java.net.URI;
import java.util.Optional;

import dev.ngspace.hudder.spotifier.Spotifier;
import dev.ngspace.hudder.spotifier.SpotifierException;
import dev.ngspace.hudder.spotifier.auth.SpotifyAuth;
import dev.ngspace.hudder.spotifier.config.SpotifierConfig;
import dev.ngspace.hudder.spotifier.spotifyapi.NowPlaying;
import dev.ngspace.hudder.spotifier.spotifyapi.SpotifyAPI;
import net.minecraft.util.Util;

public class SpotifyIntegeration extends ASpotifierIntegeration {
	
	public static final ASpotifierIntegeration INSTANCE = new SpotifyIntegeration();
	
	private final Object AUTH_LOCK;
	
	private SpotifyIntegeration() {
		AUTH_LOCK = new Object();
		this.data = fetch();
	}
	
	public final String[] SCOPES = {"user-read-currently-playing", "user-read-playback-state", "playlist-read-private"};
	
	private SpotifyAuth auth;

	@Override
	protected Optional<NowPlaying> fetch() {
		synchronized (AUTH_LOCK) {
			if (isValid())
				return SpotifyAPI.fetchAndReturnPrevious(auth.getAccessToken());
			return Optional.empty();
		}
	}

	public void reauth(String refreshToken) throws IOException {
		synchronized (AUTH_LOCK) {
			Spotifier.log("Refreshing Spotify access token");
			SpotifyAuth newauth = new SpotifyAuth(SpotifierConfig.client_id.trim(), SpotifierConfig.uri, SpotifierConfig.port);
			newauth.refreshAccessToken(refreshToken);
			auth = newauth;
		}
	}

	public void refreshAllTokens() {
		synchronized (AUTH_LOCK) {
			Spotifier.log("Getting new tokens");
			
			if (SpotifierConfig.client_id==null||SpotifierConfig.client_id.isBlank())
				throw new SpotifierException("Client ID is null or empty");
			
			auth = new SpotifyAuth(SpotifierConfig.client_id.trim(), SpotifierConfig.uri, SpotifierConfig.port);

			URI url = auth.getAuthURI(SCOPES);
			Spotifier.log("Spotifier auth url:\n" + url);
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

	public boolean canReauth() {
		return SpotifierConfig.refresh_token!=null;
	}
	
	public void reauth() throws IOException {
		reauth(SpotifierConfig.refresh_token);
	}
	
	public boolean isValid() {
		return auth!=null;
	}

	public boolean canReadData() {
		return isValid()&&SpotifierConfig.client_id!=null;
	}

	@Override
	public void init() {
		try {
			if (canReauth())
				reauth();
		} catch (IOException e) {
			Spotifier.log("Failed to auth with refresh token");
			e.printStackTrace();
		}
	}
	
}
