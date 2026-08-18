package me.snowmii.dlss.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Small, eagerly loaded user configuration. JVM properties remain explicit overrides. */
public final class ClientConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	public static final ClientConfig INSTANCE = new ClientConfig(configFile());

	private final Path file;

	private static Path configFile() {
		try {
			return FabricLoader.getInstance().getConfigDir().resolve("mc-dlss").resolve("config.json");
		} catch (RuntimeException error) {
			// Plain unit tests have no Fabric game directory; persistence is unavailable there.
			return null;
		}
	}
	private boolean enabled = true;
	private String qualityMode = "quality";
	private String renderPreset = "m";
	private boolean frameGeneration;

	private ClientConfig(final Path file) {
		this.file = file;
		load();
	}

	public boolean enabled() {
		return enabled;
	}

	public String qualityMode() {
		return qualityMode;
	}

	public String renderPreset() {
		return renderPreset;
	}

	public boolean frameGeneration() {
		return frameGeneration;
	}

	public void setEnabled(final boolean value) {
		enabled = value;
		save();
	}

	public void setQualityMode(final String value) {
		qualityMode = value;
		save();
	}

	public void setRenderPreset(final String value) {
		renderPreset = value;
		save();
	}

	public void setFrameGeneration(final boolean value) {
		frameGeneration = value;
		save();
	}

	/** Adds file-backed startup mode/preset without replacing launch-time overrides. */
	public Properties withSystemOverrides(final Properties overrides) {
		final Properties merged = new Properties();
		merged.putAll(overrides);
		merged.putIfAbsent(ModConfig.MODE_PROPERTY, qualityMode);
		merged.putIfAbsent(ModConfig.PRESET_PROPERTY, renderPreset);
		return merged;
	}

	private void load() {
		if (file == null || !Files.isRegularFile(file)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			final Data data = GSON.fromJson(reader, Data.class);
			if (data != null) {
				enabled = data.enabled == null || data.enabled;
				qualityMode = data.qualityMode == null ? qualityMode : data.qualityMode;
				renderPreset = switch (data.renderPreset) {
					case "k", "l", "m" -> data.renderPreset;
					case null, default -> renderPreset;
				};
				frameGeneration = data.frameGeneration != null && data.frameGeneration;
			}
		} catch (IOException | RuntimeException ignored) {
			// Keep safe defaults when an older or manually edited file is invalid.
		}
	}

	private void save() {
		if (file == null) {
			return;
		}
		final Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
				GSON.toJson(new Data(enabled, qualityMode, renderPreset, frameGeneration), writer);
			}
			Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException ignored) {
			// Values remain effective for this session if persistence fails.
		} finally {
			try {
				Files.deleteIfExists(temporary);
			} catch (IOException ignored) {
			}
		}
	}

	private record Data(Boolean enabled, String qualityMode, String renderPreset, Boolean frameGeneration) {
	}
}
