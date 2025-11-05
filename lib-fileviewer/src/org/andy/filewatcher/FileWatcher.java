package org.andy.filewatcher;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.ServerSocket;
import java.net.URISyntaxException;
import java.nio.channels.FileLock;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.stream.Collectors;

public class FileWatcher {

	private static final Logger logger = Logger.getLogger(FileWatcher.class.getName());

	@SuppressWarnings("unused")
	private static ServerSocket lockSocket;
	private static Properties config = new Properties();
	private static Path folderToWatch = null;
	private static String[] fileExtensions = null;
	private static Set<String> artifactExtensions;

	static {
		try {
			artifactExtensions = loadArtifactExtensions();
		} catch (IOException e) {
			e.printStackTrace(); // Oder eine bessere Fehlerbehandlung
		}
	}

	// ###################################################################################################################################################
	// ###################################################################################################################################################

	/**
	 * @param args
	 */
	public static void main(String[] args) {

		if (isAlreadyRunning()) {
	        System.exit(0);
	    }
		
		try {
			setupLogger();
			config = loadConfig();
		} catch (IOException | URISyntaxException e) {
			e.printStackTrace();
		}

		folderToWatch = Paths.get(config.getProperty("watch.path"));
		fileExtensions = config.getProperty("watch.extension").split(",");

		try {
			TraySupport.initTray(); // Tray anzeigen
			new FileWatcher().startWatching();
			//logger.info("FileWatcher überwacht: " + folderToWatch);
		} catch (Exception e) {
			logger.severe("Fehler bei Start der Überwachung: " + e.getMessage());
		}

	}

	/** Konstruktor erzeugen
	 * @throws IOException
	 */
	public FileWatcher() throws IOException {
		// Setze artifactExtensions im Konstruktor
		FileWatcher.artifactExtensions = loadArtifactExtensions();
	}

	// ###################################################################################################################################################
	// ###################################################################################################################################################

	/**
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public void startWatching() throws IOException, InterruptedException {

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
				String fileNameLower = fileName.toString().toLowerCase(); // Hier konvertierst du den Dateinamen in
				// Kleinbuchstaben

				boolean matchesExtension = Arrays.stream(fileExtensions).map(String::toLowerCase) // Konvertiere auch
						// die Endungen in
						// Kleinbuchstaben
						.anyMatch(fileNameLower::endsWith);

				if (matchesExtension || isArtifact(fileNameLower)) { // Prüfe zusätzlich auf Artefakte
					Path fullPath = folderToWatch.resolve(fileName);

					boolean bWaitSmo = isLocked(fileNameLower);
					while (bWaitSmo) {
						Thread.sleep(1000);
						bWaitSmo = isLocked(fileNameLower);
					}

					try {
						String sResult = openFile(config.getProperty("file.viewer"), fullPath.toString());
						if (sResult != null) {
							logger.warning(sResult);
						} else {
							//logger.info("Datei erkannt und geöffnet: " + extractFileName(fullPath.toString()));
							break;
						}
					} catch (IOException e) {
						logger.severe("Fehler beim Öffnen der Datei: " + e.getMessage());
					}
				}
			}

			if (!key.reset()) {
				logger.info("FileWatcher beendet.");
				break;
			}
		}
	}

	// ###################################################################################################################################################
	// ###################################################################################################################################################

	/**
	 * @return
	 */
	private static boolean isAlreadyRunning() {
	    try {
	        lockSocket = new ServerSocket(54555);
	        return false;
	    } catch (IOException e) {
	    	logger.info("FileWatcher ist bereits gestartet.");
	        return true;
	    }
	}
	
	/**
	 * @throws IOException
	 */
	private static void setupLogger() throws IOException {
		LogManager.getLogManager().reset();
		Logger rootLogger = Logger.getLogger("");
		ConsoleHandler consoleHandler = new ConsoleHandler();
		consoleHandler.setLevel(Level.INFO);
		rootLogger.addHandler(consoleHandler);

		FileHandler fileHandler = new FileHandler("filewatcher.log", true);
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
		var jarUri = FileWatcher.class.getProtectionDomain().getCodeSource().getLocation().toURI();

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
	 * @param filePath
	 * @return
	 */
	@SuppressWarnings("unused")
	private static String extractFileName(String filePath) {
		return java.nio.file.Paths.get(filePath).getFileName().toString();
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

	/**
	 * @param viewerPath
	 * @param filePath
	 * @return
	 * @throws IOException
	 */
	private String openFile(String viewerPath, String filePath) throws IOException {
		File file = new File(filePath);

		if (!file.exists() || file.length() == 0) {
			return "Datei existiert nicht oder ist leer: " + filePath;
		}

		List<String> command = Arrays.asList(viewerPath, filePath);
		ProcessBuilder pb = new ProcessBuilder(command);
		pb.directory(new File(System.getProperty("java.io.tmpdir")));

		pb.start(); // Datei mit angegebenem Viewer öffnen

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
		return null;
	}

}
