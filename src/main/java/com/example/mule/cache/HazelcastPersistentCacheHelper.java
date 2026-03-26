package com.example.mule.cache;

import com.hazelcast.config.*;
import com.hazelcast.core.*;
import com.hazelcast.map.IMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Persistent Hazelcast Cache Helper for Mule 4
 *
 * - Stores all values as plain String (no binary serialization) - Persists to
 * .txt files on disk (human-readable) - Handles Mule 4 streaming
 * (ManagedCursorStreamProvider) - Data survives app restarts and server reboots
 */
public class HazelcastPersistentCacheHelper {

	private static final Logger LOG = LoggerFactory.getLogger(HazelcastPersistentCacheHelper.class);

	private static final String DEFAULT_MAP = "mule-cache";

	private static final String STORE_DIR = System.getProperty("mule.cache.store.dir",
			System.getProperty("user.home") + "/mule-cache-store");

	private static final HazelcastInstance hz;

	static {
		try {
			Files.createDirectories(Paths.get(STORE_DIR));
			LOG.info("Cache persistence directory: {}", STORE_DIR);
		} catch (IOException e) {
			throw new RuntimeException("Failed to create cache store dir: " + STORE_DIR, e);
		}

		Config config = new Config();
		config.setClusterName("dev");

		// ── MapStore config → plain text .txt files ──────────
		MapStoreConfig mapStoreConfig = new MapStoreConfig();
		mapStoreConfig.setEnabled(true);
		mapStoreConfig.setClassName("com.example.mule.cache.FileBasedMapStore");
		mapStoreConfig.setWriteDelaySeconds(0);
		mapStoreConfig.setWriteBatchSize(1);
		mapStoreConfig.setInitialLoadMode(MapStoreConfig.InitialLoadMode.EAGER);
		mapStoreConfig.setProperty("storeDir", STORE_DIR);

		MapConfig mapConfig = new MapConfig(DEFAULT_MAP);
		mapConfig.setMapStoreConfig(mapStoreConfig);

		EvictionConfig evictionConfig = new EvictionConfig();
		evictionConfig.setMaxSizePolicy(MaxSizePolicy.PER_NODE);
		evictionConfig.setSize(10000);
		evictionConfig.setEvictionPolicy(EvictionPolicy.LRU);
		mapConfig.setEvictionConfig(evictionConfig);
		mapConfig.setTimeToLiveSeconds(0);

		config.addMapConfig(mapConfig);
		hz = Hazelcast.newHazelcastInstance(config);
		LOG.info("Hazelcast started – map: {}, store: {}", DEFAULT_MAP, STORE_DIR);
	}

	public static String put(String key, Object value) {
		validateKey(key);
		if (value == null) {
			throw new IllegalArgumentException("Cache value must not be null");
		}
		String stringValue = convertToString(value);
		getMap().put(key, stringValue);
		LOG.info("PUT key={} length={} inputType={} (persisted as .txt)", key, stringValue.length(),
				value.getClass().getName());
		return stringValue;
	}

	public static String putWithTtl(String key, Object value, int ttlSeconds) {
		validateKey(key);
		if (value == null) {
			throw new IllegalArgumentException("Cache value must not be null");
		}
		String stringValue = convertToString(value);
		getMap().put(key, stringValue, ttlSeconds, TimeUnit.SECONDS);
		LOG.info("PUT key={} ttl={}s (persisted as .txt)", key, ttlSeconds);
		return stringValue;
	}

	public static String get(String key) {
		if (key == null || key.trim().isEmpty())
			return null;
		String val = getMap().get(key);
		LOG.debug("GET key={} hit={}", key, val != null);
		return val;
	}

	public static Map<String, Object> getWithMeta(String key) {
		validateKey(key);
		String val = getMap().get(key);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("found", val != null);
		result.put("key", key);
		result.put("value", val);
		result.put("persistent", true);
		result.put("storeDir", STORE_DIR);
		return result;
	}

	public static String evict(String key) {
		validateKey(key);
		getMap().remove(key);
		LOG.debug("EVICT key={}", key);
		return key;
	}

	public static void clearAll() {
		getMap().clear();
		LOG.info("CLEAR ALL");
	}

	public static int size() {
		return getMap().size();
	}

	public static boolean containsKey(String key) {
		if (key == null)
			return false;
		return getMap().containsKey(key);
	}

	public static Map<String, Object> persistenceStatus() {
		Map<String, Object> status = new LinkedHashMap<>();
		status.put("persistent", true);
		status.put("fileFormat", ".txt (plain text)");
		status.put("storeDir", STORE_DIR);
		status.put("entriesInMemory", getMap().size());
		try {
			long filesOnDisk = Files.list(Paths.get(STORE_DIR)).filter(p -> p.toString().endsWith(".txt")).count();
			status.put("filesOnDisk", filesOnDisk);
		} catch (IOException e) {
			status.put("diskError", e.getMessage());
		}
		status.put("healthy", true);
		return status;
	}

	private static String convertToString(Object value) {
		if (value instanceof String) {
			return (String) value;
		}
		if (value instanceof byte[]) {
			return new String((byte[]) value, StandardCharsets.UTF_8);
		}
		if (value instanceof InputStream) {
			return readStream((InputStream) value);
		}

		// Mule CursorStreamProvider / ManagedCursorStreamProvider
		String className = value.getClass().getName();
		LOG.info("convertToString: type={}", className);

		if (className.contains("Cursor") || className.contains("Stream") || className.contains("streaming")) {
			return resolveStreamProvider(value);
		}

		if (value instanceof Reader) {
			return readReader((Reader) value);
		}

		return value.toString();
	}

	private static String resolveStreamProvider(Object provider) {
		InputStream stream = tryMethod(provider, "openCursor");
		if (stream == null)
			stream = tryMethod(provider, "getInputStream");
		if (stream == null)
			stream = tryMethod(provider, "get");

		if (stream != null) {
			return readStream(stream);
		}
		if (provider instanceof InputStream) {
			return readStream((InputStream) provider);
		}

		LOG.warn("Cannot open stream from {}, using toString()", provider.getClass().getName());
		return provider.toString();
	}

	private static InputStream tryMethod(Object obj, String methodName) {
		try {
			Object result = obj.getClass().getMethod(methodName).invoke(obj);
			if (result instanceof InputStream)
				return (InputStream) result;
			return null;
		} catch (Exception e) {
			return null;
		}
	}

	private static String readStream(InputStream is) {
		try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
			StringBuilder sb = new StringBuilder(4096);
			char[] buf = new char[4096];
			int len;
			while ((len = r.read(buf)) != -1)
				sb.append(buf, 0, len);
			return sb.toString();
		} catch (IOException e) {
			throw new RuntimeException("Cannot read stream: " + e.getMessage(), e);
		}
	}

	private static String readReader(Reader reader) {
		try (BufferedReader r = (reader instanceof BufferedReader) ? (BufferedReader) reader
				: new BufferedReader(reader)) {
			StringBuilder sb = new StringBuilder(4096);
			char[] buf = new char[4096];
			int len;
			while ((len = r.read(buf)) != -1)
				sb.append(buf, 0, len);
			return sb.toString();
		} catch (IOException e) {
			throw new RuntimeException("Cannot read reader: " + e.getMessage(), e);
		}
	}

	private static IMap<String, String> getMap() {
		return hz.getMap(DEFAULT_MAP);
	}

	private static void validateKey(String key) {
		if (key == null || key.trim().isEmpty())
			throw new IllegalArgumentException("Cache key must not be null or empty");
	}
}
