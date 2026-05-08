package com.protoevo.core;

import com.github.javafaker.Faker;
import com.protoevo.biology.cells.Protozoan;
import com.protoevo.biology.nn.NetworkGenome;
import com.protoevo.core.repl.REPL;
import com.protoevo.env.serialization.Serialization;
import com.protoevo.env.Environment;
import com.protoevo.settings.SimulationSettings;
import com.protoevo.utils.EnvironmentImageRenderer;
import com.protoevo.utils.FileIO;
import com.protoevo.utils.TimedEventsManager;
import com.protoevo.utils.Utils;

import java.awt.*;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Simulation implements Runnable
{
	protected Environment environment;
	protected ApplicationManager manager;
	private volatile boolean simulate, saveRequested = false, busyOnOtherThread = false;
	private static boolean paused = false;
	private float timeDilation = 1, timeSinceSave = 0, timeSinceSnapshot = 0, timeSinceAutoSave = 0;
	private TimedEventsManager timedEventsManager;
	
	public static Random RANDOM = new Random(Environment.settings.simulationSeed.get());
	private boolean debug = false;
	protected boolean initialised = false;

	protected Supplier<Environment> environmentLoader;
	private String name;
	private List<String> statsNames;
	private final REPL repl = new REPL(this);
	private SimulationHistory history;

	public Simulation() {
		this(Environment.settings.simulationSeed.get());
	}

	public Simulation(long seed)
	{
		RANDOM = new Random(seed);
		simulate = true;
		name = generateSimName();
		environmentLoader = this::newDefaultEnv;
		loadSettings();
	}

	public Simulation(String name) {
		this(Environment.settings.simulationSeed.get(), name);
	}

	public Simulation(String name, SimulationSettings settings) {
		RANDOM = new Random(settings.simulationSeed.get());
		this.name = name;
		simulate = true;
		environmentLoader = () -> newEnvironment(settings);
	}

	public Simulation(long seed, String name)
	{
		RANDOM = new Random(seed);
		simulate = true;
		this.name = name;

		environmentLoader = this::loadMostRecentEnv;
		loadSettings();
	}

	public Simulation(String name, String save) {
		this(Environment.settings.simulationSeed.get(), name, save);
	}

	public Simulation(long seed, String name, String save)
	{
		RANDOM = new Random(seed);
		simulate = true;
		this.name = name;

		environmentLoader = () -> loadEnv(FileIO.getSavesDir() + "/" + name + "/env/" + save);
		loadSettings();
	}

	private void loadSettings() {}

	public static void newSaveDir(String simulationName) {
		try {
			System.out.println("Created new simulation named: " + simulationName);
			Files.createDirectories(FileIO.getSavesDir());
			Files.createDirectories(Paths.get(FileIO.getSavesDir() + "/" + simulationName));
			Files.createDirectories(Paths.get(FileIO.getSavesDir() + "/" + simulationName + "/env"));
			Files.createDirectories(Paths.get(FileIO.getSavesDir() + "/" + simulationName + "/stats"));
			Files.createDirectories(Paths.get(FileIO.getSavesDir() + "/" + simulationName + "/stats/summaries"));
			Files.createDirectories(Paths.get(FileIO.getSavesDir() + "/" + simulationName + "/stats/protozoa-genomes"));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static String generateSimName() {
		Faker faker = new Faker();
		return String.format("%s-%s-%s",
				faker.ancient().primordial().toLowerCase().replaceAll(" ", "-"),
				faker.pokemon().name().toLowerCase().replaceAll(" ", "-"),
				faker.lorem().word().toLowerCase().replaceAll(" ", "-"));
	}

	public Environment newDefaultEnv()
	{
		newSaveDir(name);
		return new Environment();
	}

	public Environment newEnvironment(SimulationSettings settings)
	{
		newSaveDir(name);
		return new Environment(settings);
	}

	public Environment loadEnv(String filename)
	{
		try {
			Environment env = Serialization.reloadEnvironment(filename);
			System.out.println("Loaded tank at: " + filename);
			initialised = true;
			return env;
		} catch (Exception e) {
//			return newDefaultEnv();
			throw new RuntimeException(e);
		}
	}

	public void setManager(ApplicationManager manager) {
		this.manager = manager;
		repl.setManager(manager);
	}

	public static Stream<Path> getStatsPaths(String name) {
		Path dir = Paths.get(FileIO.getSavesDir() + "/" + name + "/stats/summaries");
		if (Files.exists(dir)) {
			try (Stream<Path> paths = Files.list(dir)){
				return paths.collect(Collectors.toList()).stream()
						.filter(path -> path.getFileName().toString().endsWith(".json"));
			} catch (IOException e) {
				System.out.println("Unable to find environment of given name: " + e.getMessage());
				System.exit(0);
				return Stream.empty();
			}
		}
		return Stream.empty();
	}

	public static Optional<Path> getClosestStatsPath(String name, Long time) {
		return getStatsPaths(name).min(
				Comparator.comparingLong(
					path -> Math.abs(time - path.toFile().lastModified())));
	}

	public static Stream<Path> getSavePaths(String name) {
		Path dir = Paths.get(FileIO.getSavesDir() + "/" + name + "/env");
		if (Files.exists(dir)) {
			try (Stream<Path> paths = Files.list(dir)){
				return paths.collect(Collectors.toList()).stream().filter(Files::isDirectory);
			} catch (IOException e) {
				System.out.println("Unable to find environment of given name: " + e.getMessage());
				System.exit(0);
				return Stream.empty();
			}
		}
		return Stream.empty();
	}

	public static Long saveModifiedTime(Path path) {
		return Paths.get(path.toString() + "/environment.dat")
				.toFile().lastModified();
	}

	public static Optional<Path> getMostRecentSave(String name) {
		return getSavePaths(name).max(Comparator.comparingLong(Simulation::saveModifiedTime));
	}

	public Environment loadMostRecentEnv() {
		Optional<String> lastFilePath = getMostRecentSave(name).map(Path::toString);
		if (lastFilePath.isPresent())
			return loadEnv(lastFilePath.get());
		else {
			System.out.println("Unable to find environment of given name.");
			return newDefaultEnv();
		}
	}

	public void prepare()
	{
		paused = false;
		environment = environmentLoader.get();
		environment.setSimulationName(name);
		history = new SimulationHistory(getSaveFolder());

		if (!initialised) {
			environment.initialise();
			makeStatisticsSnapshot();
			initialised = true;
		}
		new Thread(repl).start();

		if (manager != null) {
			manager.notifySimulationReady();
		}

		timedEventsManager = new TimedEventsManager();
		timedEventsManager.add(
			t -> t >= Environment.settings.misc.timeBetweenHistoricalSaves.get() || saveRequested,
			this::save
		);
		timedEventsManager.add(
			Environment.settings.misc.statisticsSnapshotTime::get,
			this::makeStatisticsSnapshot
		);
		timedEventsManager.add(
			Environment.settings.misc.timeBetweenAutoSaves::get,
			this::createAutoSave
		);
		timedEventsManager.add(
			() -> homeostasisInterval,
			this::homeostasisTick
		);
	}

	// ===== Homeostatic difficulty controller =====
	// Periodically nudges plant/meat energy density and the cell energy decay
	// rate so the protozoa population gravitates toward a target. When the pop
	// is below target the controller relaxes (more food, slower decay) so a
	// crashing run can recover. When the pop exceeds target it tightens to
	// preserve evolutionary pressure (selection only happens when surviving is
	// non-trivial).
	//
	// `scale = sqrt(clamp(pop/target, lo, hi))` is a smooth, asymmetric-bias-free
	// adjustment: a 4× pop swing only causes a 2× setting swing, avoiding
	// runaway oscillation. The square root also makes adjustments biggest near
	// the target where derivative matters and small at the extremes.
	private boolean homeostasisEnabled = true;
	private int homeostasisTargetPop = 500;
	private float homeostasisInterval = 5f; // sim-seconds between adjustments
	private float homeostasisLastLoggedScale = -1f;

	// Reference values: must match the in-Java defaults set in CellSettings /
	// SimulationSettings. Adjustments are computed as multipliers of these.
	private static final float HOMEO_BASE_DECAY = 0.025f;
	private static final float HOMEO_BASE_PLANT_ED = 2e5f;
	private static final float HOMEO_BASE_MEAT_ED = 6e5f;
	private static final float HOMEO_BASE_START_E = 50f;

	private void homeostasisTick() {
		if (!homeostasisEnabled || environment == null) return;
		int pop = environment.numberOfProtozoa();
		if (pop <= 0) return; // already extinct; nothing for the controller to do
		float ratio = (float) pop / homeostasisTargetPop;
		ratio = Math.max(0.2f, Math.min(2.5f, ratio));
		float scale = (float) Math.sqrt(ratio);

		// scale > 1 ⇒ population above target: tighten (less food, faster decay).
		// scale < 1 ⇒ population below target: relax (more food, slower decay).
		Environment.settings.cell.energyDecayRate.set(HOMEO_BASE_DECAY * scale);
		Environment.settings.plantEnergyDensity.set(HOMEO_BASE_PLANT_ED / scale);
		Environment.settings.meatEnergyDensity.set(HOMEO_BASE_MEAT_ED / scale);
		Environment.settings.cell.startingAvailableCellEnergy.set(HOMEO_BASE_START_E / scale);

		// Log only when the controller swings meaningfully, so the console
		// doesn't get spammed when we're holding steady near the target.
		if (homeostasisLastLoggedScale < 0
				|| Math.abs(scale - homeostasisLastLoggedScale) > 0.15f) {
			System.out.printf(
					"[homeostat] pop=%d  target=%d  scale=%.2f  decay=%.4f  plantED=%.0f%n",
					pop, homeostasisTargetPop, scale,
					HOMEO_BASE_DECAY * scale, HOMEO_BASE_PLANT_ED / scale);
			homeostasisLastLoggedScale = scale;
		}
	}

	public boolean isHomeostasisEnabled() { return homeostasisEnabled; }
	public void setHomeostasisEnabled(boolean enabled) {
		homeostasisEnabled = enabled;
		if (!enabled) {
			// Restore the static defaults so disabling actually disables.
			Environment.settings.cell.energyDecayRate.set(HOMEO_BASE_DECAY);
			Environment.settings.plantEnergyDensity.set(HOMEO_BASE_PLANT_ED);
			Environment.settings.meatEnergyDensity.set(HOMEO_BASE_MEAT_ED);
			Environment.settings.cell.startingAvailableCellEnergy.set(HOMEO_BASE_START_E);
		}
		homeostasisLastLoggedScale = -1f;
		System.out.println("Homeostat: " + (enabled ? "ON" : "OFF (defaults restored)"));
	}
	public int getHomeostasisTargetPop() { return homeostasisTargetPop; }
	public void setHomeostasisTargetPop(int target) {
		homeostasisTargetPop = Math.max(10, target);
		System.out.println("Homeostat target population: " + homeostasisTargetPop);
	}
	// ===== end Homeostatic controller =====

	public void cancelPreparation() {}

	public void run() {
		while (simulate) {
			if (paused) {
				// Sleep briefly instead of busy-waiting. The original tight
				// `if (paused) continue;` pinned a full CPU core whenever the
				// sim was paused, which starved the render thread enough that
				// opening a heavy modal screen could make Windows mark the
				// LWJGL window "not responding".
				try {
					Thread.sleep(20);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
				continue;
			}

			update();

			if (isFinished()) {
				simulate = false;
				System.out.println();
				System.out.println("Finished simulation. All protozoa died.");
				printStats();
			}
		}
	}

	public boolean isFinished() {
		return environment.hasStarted() && environment.numberOfProtozoa() <= 0
				&& Environment.settings.finishOnProtozoaExtinction.get();
	}

	public void requestSave() {
		saveRequested = true;
	}

	public void printStats() {
		environment.getStats().forEach(
			stat -> System.out.println(stat.toString())
		);
	}

	public void update()
	{
		if (isPaused() || busyOnOtherThread || environment == null)
			return;

		try {
			// Substep when timeDilation > 1: each substep uses the full safe dt,
			// so physics/NN stay stable while wall-clock sim time advances faster.
			// timeDilation < 1 just shrinks dt for slow-mo.
			float baseDt = Environment.settings.simulationUpdateDelta.get();
			float td = Math.max(0f, timeDilation);
			int fullSteps = (int) td;
			float frac = td - fullSteps;
			int cap = 64; // safety cap so a runaway dial doesn't lock the thread
			int toRun = Math.min(fullSteps, cap);

			try {
				// Run the full env update (including chemicals) on every
				// substep with the small safe dt. Earlier "skip chemicals on
				// intermediate substeps" optimizations turned out to break
				// the chemical field: plant deposits scale fine with delta,
				// but protozoan EXTRACTION in cellChemicalIO scales linearly
				// with delta, so batching N substeps' worth into one call
				// drained surrounding cells to zero in a single tick. Net
				// effect was an empty plant gradient and population collapse.
				for (int i = 0; i < toRun; i++) {
					environment.update(baseDt);
					timedEventsManager.update(baseDt);
				}
				if (frac > 0f && toRun < cap) {
					float dt = baseDt * frac;
					environment.update(dt);
					timedEventsManager.update(dt);
				}
			} catch (Exception e) {
				writeCrashReport(e);
				e.printStackTrace();
				System.out.println("Error occurred during simulation. Saving and exiting.");
				save();
				repl.close();
				throw e;
			}

		} catch (Exception e) {
			writeCrashReport(e);
			System.exit(0);
		}
	}

	public void writeCrashReport(Exception e) {
		String crashFolder = getSaveFolder() + "/crash_" + Utils.getTimeStampString();
		try {
			Files.createDirectories(Paths.get(crashFolder));
			FileWriter fileWriter = new FileWriter(crashFolder + "/report.txt");
			PrintWriter printWriter = new PrintWriter(fileWriter);
			printWriter.println("Timestamp: " + new Date());
			printWriter.println("Application version: " + ApplicationManager.APPLICATION_VERSION);
			printWriter.println("Operating system: " + System.getProperty("os.name"));
			printWriter.println("Operating system version: " + System.getProperty("os.version"));
			printWriter.println("Java version: " + System.getProperty("java.version"));
			Runtime runtime = Runtime.getRuntime();
			printWriter.println("Free memory: " + runtime.freeMemory());
			printWriter.println("Max memory: " + runtime.maxMemory());
			printWriter.println("Total memory: " + runtime.totalMemory());
			printWriter.println("Processors: " + runtime.availableProcessors());
			printWriter.println();
			printWriter.println("Stack Trace:");
			e.printStackTrace(printWriter);
			printWriter.close();
			System.out.println("Wrote crash report to: " + crashFolder);
		} catch (IOException ioException) {
			System.err.println("Failed to create crash report: " + ioException + "\nWhen handling exception: " + e);
		}
	}

	public void saveOnOtherThread() {
		onOtherThread(this::save);
	}

	public void onOtherThread(Runnable runnable) {
		if (busyOnOtherThread)
			return;
		busyOnOtherThread = true;
		new Thread(() -> {
			runnable.run();
			busyOnOtherThread = false;
		}).start();
	}

	public boolean isBusyOnOtherThread() {
		return busyOnOtherThread;
	}

	public void interruptSimulationLoop() {
		simulate = false;
	}

	public void close() {
		simulate = false;
		System.out.println("\nClosing simulation.");
		String saveFile = save();
		System.out.println("Saved environment to: " + saveFile);
		repl.close();
	}

	public void dispose() {
		if (environment != null) {
			environment.dispose();
			environment = null;
		}
	}

	public String save() {
		if (environment == null)
			return null;

		EnvironmentImageRenderer renderer = new EnvironmentImageRenderer(1024, 1024, environment);
		renderer.render(getSaveFolder() + "/screenshots");
		System.out.println("Created screenshot in directory: " + getSaveFolder() + "/screenshots");

		String timeStamp = Utils.getTimeStampString();
		String fileName = getSaveFolder() + "/env/" + timeStamp;
		Serialization.saveEnvironment(environment, fileName);
		return fileName;
	}

	public void createAutoSave() {
		if (environment == null)
			return;

		EnvironmentImageRenderer renderer = new EnvironmentImageRenderer(1024, 1024, environment);
		renderer.render(getSaveFolder() + "/screenshots");
		System.out.println("Created screenshot in directory: " + getSaveFolder() + "/screenshots");

		String fileName = getSaveFolder() + "/env/autosave";
		Serialization.saveEnvironment(environment, fileName);
	}

	public void makeStatisticsSnapshot() {
		history.makeStatisticsSnapshot(environment);
//		Statistics stats = new Statistics(environment.getStats());
//		stats.putAll(environment.getDebugStats());
//		stats.putAll(environment.getPhysicsDebugStats());
//		stats.putAll(environment.getProtozoaSummaryStats(true, false, true));
//
//		String timeStamp = Utils.getTimeStampString();
//
//		FileIO.writeJson(stats, getSaveFolder() + "/stats/summaries/" + timeStamp);
//
//		if (Environment.settings.misc.writeGenomes.get()) {
//			List<NetworkGenome> protozoaGenomes = environment.getCells().stream()
//					.filter(cell -> cell instanceof Protozoan)
//					.map(cell -> ((Protozoan) cell).getGeneExpressionFunction().getGRNGenome())
//					.collect(Collectors.toList());
//			FileIO.writeJson(protozoaGenomes, getSaveFolder() + "/stats/protozoa-genomes/" + timeStamp);
//		}

//		PythonRunner.runPython("pyprotoevo.create_plots", "--quiet --simulation " + name);
	}

	public SimulationHistory getHistory() {
		return history;
	}

	public void toggleDebug() {
		debug = !debug;
	}

	public void togglePause() {
		paused = !paused;
	}

	public void setPaused(boolean paused) {
		Simulation.paused = paused;
	}

	public boolean inDebugMode() {
		return debug;
	}

	public Environment getEnv() { return environment; }

	public float getElapsedTime() { return environment.getElapsedTime(); }

	public float getTimeDilation() { return timeDilation; }

	public void setTimeDilation(float td) { timeDilation = td; }

    public static boolean isPaused() {
		return paused;
    }

	public boolean isReady() {
		return initialised;
	}

	public String getSaveFolder() {
		return FileIO.getSavesDir() + "/" + name;
	}

	public void openSaveFolderOnDesktop() {
		try {
			Desktop.getDesktop().open(new File(getSaveFolder()));
		} catch (IOException e) {
			System.out.println("\nFailed to open folder: " + e.getMessage() + "\n");
		}
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public void toggleTimeDilation() {
		if (timeDilation <= 1f)
			timeDilation = 2f;
		else if (timeDilation <= 2f)
			timeDilation = 5f;
//		else if (timeDilation <= 5f)
//			timeDilation = 10f;
		else
			timeDilation = 1f;
	}

	public String getLoadingStatus() {
		if (initialised)
			return "Ready to Simulate";
		else if (environment == null)
			return "Creating Environment";
		else
			return environment.getLoadingStatus();
	}
}
