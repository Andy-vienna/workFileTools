package org.andy.filemover;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URISyntaxException;
import java.nio.channels.FileLock;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.stream.Collectors;

public class FileMover {

	private static final Logger logger = Logger.getLogger(FileMover.class.getName());

	private static Properties config = new Properties();
	private static Path folderToWatch = null;
	private static Path targetDir = null;
	private static String[] fileExtensions = null;
	private static Set<String> artifactExtensions;

	// ###################################################################################################################################################
	// ###################################################################################################################################################

	/**
	 * @param args
	 */
	public static void main(String[] args) {

		try {
			setupLogger();
			config = loadConfig();
		} catch (IOException | URISyntaxException e) {
			e.printStackTrace();
		}

		folderToWatch = Paths.get(config.getProperty("watch.path"));
		targetDir = Paths.get(config.getProperty("target.path"));
		fileExtensions = config.getProperty("watch.extension").split(",");

		try {
			TraySupport.initTray(); // Tray anzeigen
			new FileMover().startWatching();
			logger.info("FileMover überwacht: " + folderToWatch);
		} catch (IOException | InterruptedException e) {
			logger.severe("Fehler bei Start der Überwachung: " + e.getMessage());
		}
	}

	/**
	 * @throws IOException
	 */
	public FileMover() throws IOException {
		// Setze artifactExtensions im Konstruktor
		FileMover.artifactExtensions = loadArtifactExtensions();
	}

	// ###################################################################################################################################################
	// ###################################################################################################################################################

	/**
	 * @throws IOException
	 * @throws InterruptedException
	 */
	private void startWatching() throws IOException, InterruptedException {

		WatchService watchService = FileSystems.getDefault().newWatchService();
		folderToWatch.register(watchService, ENTRY_CREATE);

		while (true) {
			WatchKey key = watchService.take();

			for (WatchEvent<?> event : key.pollEvents()) {
				WatchEvent.Kind<?> kind = event.kind();
				if (kind == OVERFLOW) {
					continue;
				}

				@SuppressWarnings("unchecked")
				WatchEvent<Path> ev = (WatchEvent<Path>) event;

				Path fileName = ev.context();
				String fileNameLower = fileName.toString().toLowerCase();

				boolean matchesExtension = Arrays.stream(fileExtensions).map(String::toLowerCase) // Konvertiere auch
						// die Endungen in
						// Kleinbuchstaben
						.anyMatch(fileNameLower::endsWith);

				if (matchesExtension) {
					Path sourcePath = folderToWatch.resolve(fileName);
					Path targetPath = targetDir.resolve(fileName);

					boolean bWaitSmo = isLocked(fileNameLower);
					while (bWaitSmo) {
						Thread.sleep(1000);
						bWaitSmo = isLocked(fileNameLower);
					}

					try {
						Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
						logger.info("Datei verschoben: " + fileName);

						// Überprüfe und lösche Artefakte im aktuellen Verzeichnis
						File dir = new File(System.getProperty("user.dir"));
						for (File f : dir.listFiles()) {
							if (f.isFile()) {
								// System.out.println("Gefundene Datei: " + f.getName() + ", Größe: " +
								// f.length());
								if (f.length() == 0 && isArtifact(f.getName())) {
									System.out.println("Artefakt gefunden: " + f.getName());
									boolean deleted = f.delete(); // Versuche, das Artefakt zu löschen
									if (deleted) {
										System.out.println("Artefakt gelöscht: " + f.getName());
									} else {
										System.out.println("Fehler beim Löschen von " + f.getName());
									}
								}
							}
						}

					} catch (IOException e) {
						logger.severe("Fehler beim Verschieben: " + e.getMessage());
					}
				}
			}

			if (!key.reset()) {
				logger.info("FileMover beendet.");
				break;
			}
		}
	}

	// ###################################################################################################################################################
	// ###################################################################################################################################################

	/**
	 * @throws IOException
	 */
	private static void setupLogger() throws IOException {
		LogManager.getLogManager().reset();
		Logger rootLogger = Logger.getLogger("");
		ConsoleHandler consoleHandler = new ConsoleHandler();
		consoleHandler.setLevel(Level.INFO);
		rootLogger.addHandler(consoleHandler);

		FileHandler fileHandler = new FileHandler("filemover.log", true);
		fileHandler.setLevel(Level.ALL);
		fileHandler.setFormatter(new SimpleFormatter());
		rootLogger.addHandler(fileHandler);
	}

	/**
	 * @return
	 * @throws IOException
	 * @throws URISyntaxException
	 */
	private static Properties loadConfig() throws IOException, URISyntaxException {
		var props = new Properties();

		// Ermittel die URI des JARs, das die FileWatcher-Klasse enthält.
		var jarUri = FileMover.class.getProtectionDomain().getCodeSource().getLocation().toURI();

		// Konvertiere die URI in einen Path – so werden Encodingprobleme (z. B.
		// führende Slash) automatisch behoben.
		var jarPath = Paths.get(jarUri);
		var jarDir = jarPath.getParent();

		var configPath = jarDir.resolve("config.properties");

		try (var in = Files.newInputStream(configPath)) {
			props.load(in);
		}

		return props;
	}

	/**
	 * @param fileName
	 * @return
	 */
	private static boolean isLocked(String fileName) {
		try (RandomAccessFile randomAccessFile = new RandomAccessFile(fileName, "rw");
				FileLock lock = randomAccessFile.getChannel().lock()) {
			return lock == null;
		} catch (IOException ex) {
			return true;
		}
	}

	/**
	 * @return
	 * @throws IOException
	 */
	private static Set<String> loadArtifactExtensions() throws IOException {
		String extensions = config.getProperty("watch.extension");

		// Debug-Ausgabe der geladenen Konfiguration
		// System.out.println("Geladene Konfiguration (watch.extension): " +
		// extensions);

		if (extensions == null || extensions.isEmpty()) {
			return Set.of(); // Falls keine Endungen angegeben sind, leer zurückgeben
		}

		// Entferne führende/abschließende Leerzeichen und stelle sicher, dass jede
		// Endung mit einem Punkt beginnt
		return Arrays.stream(extensions.split(",")).map(String::trim) // Entferne Leerzeichen
				.map(e -> e.startsWith(".") ? e : "." + e) // Stelle sicher, dass jede Endung mit einem Punkt beginnt
				.collect(Collectors.toSet());
	}

	/**
	 * @param fileName
	 * @return
	 */
	private boolean isArtifact(String fileName) {
		// Konvertiere den Dateinamen und die Endungen in Kleinbuchstaben
		String lowerCaseFileName = fileName.toLowerCase();

		// Debug-Ausgabe, um zu sehen, welche Endungen überprüft werden
		// System.out.println("Überprüfe Datei: " + fileName);

		for (String extension : artifactExtensions) {
			// Konvertiere die Endung ebenfalls in Kleinbuchstaben, um die Vergleichbarkeit
			// zu gewährleisten
			String lowerCaseExtension = extension.toLowerCase();

			// Debug-Ausgabe der Endungen
			// System.out.println("Vergleiche mit Endung: " + lowerCaseExtension);

			if (lowerCaseFileName.endsWith(lowerCaseExtension)) {
				// System.out.println("Passende Endung gefunden: " + lowerCaseExtension);
				return true;
			}
		}
		return false;
	}

}

