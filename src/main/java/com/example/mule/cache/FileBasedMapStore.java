package com.example.mule.cache;

import com.hazelcast.map.MapStore;
import com.hazelcast.map.MapLoaderLifecycleSupport;
import com.hazelcast.core.HazelcastInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class FileBasedMapStore implements MapStore<String, String>, MapLoaderLifecycleSupport {

	private static final Logger LOG = LoggerFactory.getLogger(FileBasedMapStore.class);

	/** Characters that are safe for filenames on all OS */
	private static final String SAFE_FILENAME_PATTERN = "^[a-zA-Z0-9._\\-]+$";

	private Path storeDir;

	@Override
	public void init(HazelcastInstance hazelcastInstance, Properties properties, String mapName) {
		String dir = properties.getProperty("storeDir", System.getProperty("user.home") + "/mule-cache-store");
		this.storeDir = Paths.get(dir);

		try {
			Files.createDirectories(storeDir);
			LOG.info("TextFile MapStore initialized – dir: {}, map: {}", storeDir, mapName);
		} catch (IOException e) {
			throw new RuntimeException("Cannot create store directory: " + storeDir, e);
		}
	}

	@Override
	public void destroy() {
		LOG.info("TextFile MapStore destroyed");
	}

	@Override
	public void store(String key, String value) {
		Path file = keyToFile(key);
		try {
			Files.write(file, value.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE,
					StandardOpenOption.TRUNCATE_EXISTING);
			LOG.debug("STORE key={} → {} ({} chars)", key, file.getFileName(), value.length());
		} catch (IOException e) {
			LOG.error("Failed to store key={}: {}", key, e.getMessage());
			throw new RuntimeException("Cache write failed for key: " + key, e);
		}
	}

	@Override
	public void storeAll(Map<String, String> map) {
		map.forEach(this::store);
		LOG.debug("STORE_ALL {} entries written to disk", map.size());
	}

	@Override
	public String load(String key) {
		Path file = keyToFile(key);
		if (!Files.exists(file)) {
			LOG.debug("LOAD key={} → not on disk", key);
			return null;
		}
		try {
			String value = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
			LOG.debug("LOAD key={} → restored from disk ({} chars)", key, value.length());
			return value;
		} catch (IOException e) {
			LOG.error("Failed to load key={}: {}", key, e.getMessage());
			return null;
		}
	}

	@Override
	public Map<String, String> loadAll(Collection<String> keys) {
		Map<String, String> result = new HashMap<>();
		for (String key : keys) {
			String val = load(key);
			if (val != null) {
				result.put(key, val);
			}
		}
		LOG.info("LOAD_ALL restored {}/{} entries from disk", result.size(), keys.size());
		return result;
	}

	@Override
	public Iterable<String> loadAllKeys() {
		try {
			List<String> keys = Files.list(storeDir).filter(p -> p.toString().endsWith(".txt")).map(this::fileToKey)
					.filter(Objects::nonNull).collect(Collectors.toList());
			LOG.info("LOAD_ALL_KEYS found {} .txt files on disk", keys.size());
			return keys;
		} catch (IOException e) {
			LOG.error("Failed to list store directory: {}", e.getMessage());
			return Collections.emptyList();
		}
	}

	@Override
	public void delete(String key) {
		Path file = keyToFile(key);
		try {
			boolean deleted = Files.deleteIfExists(file);
			LOG.debug("DELETE key={} file_deleted={}", key, deleted);
		} catch (IOException e) {
			LOG.error("Failed to delete key={}: {}", key, e.getMessage());
		}
	}

	@Override
	public void deleteAll(Collection<String> keys) {
		keys.forEach(this::delete);
		LOG.debug("DELETE_ALL {} entries removed from disk", keys.size());
	}

	private Path keyToFile(String key) {
		if (isSafeFilename(key)) {
			// Simple key — use as-is for readable filenames
			return storeDir.resolve(key + ".txt");
		} else {
			// Contains special chars — Base64 encode with prefix
			String encoded = Base64.getUrlEncoder().encodeToString(key.getBytes(StandardCharsets.UTF_8));
			return storeDir.resolve("_b64_" + encoded + ".txt");
		}
	}

	private String fileToKey(Path file) {
		try {
			String name = file.getFileName().toString();
			if (!name.endsWith(".txt"))
				return null;

			// Strip .txt extension
			String baseName = name.substring(0, name.length() - 4);

			if (baseName.startsWith("_b64_")) {
				// Decode Base64 key
				String encoded = baseName.substring(5); // strip _b64_ prefix
				return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
			} else {
				// Plain filename IS the key
				return baseName;
			}
		} catch (Exception e) {
			LOG.warn("Could not decode filename: {}", file.getFileName());
			return null;
		}
	}

	private boolean isSafeFilename(String key) {
		return key != null && key.matches(SAFE_FILENAME_PATTERN);
	}
}
