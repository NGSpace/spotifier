package dev.ngspace.hudder.spotifier;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.json.JSONObject;

public class SpotifierUtil {private SpotifierUtil() {}

    public static JSONObject postForm(String url, String form) throws MalformedURLException, IOException {
        byte[] postBytes = form.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Accept", "application/json");
        conn.setFixedLengthStreamingMode(postBytes.length);
        
        OutputStream os = conn.getOutputStream();
        os.write(postBytes);
        os.close();
        
        int resp = conn.getResponseCode();
        
        InputStream is = (resp / 100 == 2) ? conn.getInputStream() : conn.getErrorStream();
        String body = "";
        if (is != null) {
        	BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line; while ((line = br.readLine()) != null) sb.append(line).append('\n');
            body = sb.toString();
            br.close();
        }
        
        if (resp / 100 != 2)
            throw new IOException("Token endpoint error (" + resp + "): " + body);
        return new JSONObject(body);
    }
    
    

    public static Map<String, String> splitQuery(String query) throws UnsupportedEncodingException {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            String key = idx > 0 ? URLDecoder.decode(pair.substring(0, idx), "UTF-8") : pair;
            String value = idx > 0 && pair.length() > idx+1 ? URLDecoder.decode(pair.substring(idx + 1), "UTF-8") : "";
            map.put(key, value);
        }
        return map;
    }
    
    

    public static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
    
}
