package dev.ngspace.hudder.spotifier.config;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;

import org.json.JSONObject;

import net.fabricmc.loader.api.FabricLoader;

public class SpotifierConfig {private SpotifierConfig() {}

	public static String client_id = "";
	public static String refresh_token = null;
	public static long pull_rate = 1250;
	
	public static URI uri = URI.create("http://127.0.0.1:8888/callback");
	public static int port = 8888;
	
	public static final File CONFIG_FILE = new File(FabricLoader.getInstance().getConfigDir().toString()
			+ File.separator + "spotifier.json");
	
	public static void save() {
		JSONObject group = new JSONObject();
		group.put("client_id", client_id);
		group.put("refresh_token", refresh_token);
		group.put("msdiff", pull_rate);
		
        try (FileWriter file = new FileWriter(CONFIG_FILE)) {
            file.write(group.toString(1));
            file.flush();
        } catch (IOException e) {
            e.fillInStackTrace();
        } catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public static void read() {
		try {
			String json = new String(Files.readAllBytes(CONFIG_FILE.toPath()));
			JSONObject obj = new JSONObject(json);
			client_id = obj.getString("client_id");
			refresh_token = obj.optString("refresh_token", null);
			pull_rate = obj.optLong("msdiff", 1250);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
}
