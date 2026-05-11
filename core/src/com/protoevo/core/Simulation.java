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

	// ===== Homeostatic difficulty controller (PID) =====
	//
	// Earlier versions used a static `scale = f(pop/target)` lookup that
	// snapped immediately and capped at fixed multipliers. That was too
	// rigid — sustained overpopulation didn't cause growing pressure, and
	// undercrowding didn't get an extra push to recover.
	//
	// This is now a proper PID controller on the normalized population error
	// e(t) = (pop - target) / target. The control signal u(t) =
	// Kp·e + Ki·∫e dt + Kd·de/dt  is then mapped through per-parameter
	// exponentials onto the four levers (decay, plant ED, meat ED, plant
	// contact death). Highlights of the design:
	//
	//   • No hard caps. Sustained over-target population grows the integral
	//     term over time, so pressure mounts continuously — exactly the
	//     "actively change grazing over time" behavior wanted. The
	//     parameters never reach zero/infinity because exp() is bounded by
	//     `MAX_LOG_DEVIATION`.
	//
	//   • Derivative damping. When pop is rapidly approaching target the
	//     derivative term softens the response, preventing the classic
	//     PI-only oscillate-and-overshoot pattern.
	//
	//   • Per-parameter gains. Plant contact-death is a fast lever (high
	//     gain) — it directly cuts the food supply at its source. Plant
	//     energy density is a medium lever. Decay rate is slow. Starting
	//     energy is the gentlest because it only affects future newborns.
	//
	//   • Integral anti-windup. The integral is clamped so a long-term
	//     extinction or runaway can't lock the controller into max output.
	//
	//   • Active equilibrium. At pop == target the controller does almost
	//     nothing; tiny errors are nudged away. Above/below, pressure
	//     gradually builds — this naturally creates the oscillating
	//     "active competition" the user wanted, with periods of grazing
	//     stress alternating with growth windows.
	private boolean homeostasisEnabled = true;
	private int homeostasisTargetPop = 250;
	private int homeostasisTargetPlants = 1000;
	private float homeostasisInterval = 5f; // sim-seconds between updates

	// PID gains. Levers are all *natural* parameters (food density, decay,
	// grazing pressure, chemical drip availability) — never a hard population
	// cap, so the population is shaped by selection, not gated.
	//
	// Tuning notes from extensive sim experience:
	//   • Earlier the integral was hard-capped at ±30. That worked as crude
	//     anti-windup but ruined fine tracking: once saturated, the lever
	//     stayed maxed even as pop crossed target, causing big undershoot
	//     after a big overshoot. Replaced with a *leaky* integral that
	//     decays a fixed fraction per tick; this prevents windup naturally
	//     and lets D actually do the fine-correction work.
	//   • MAX_LOG_DEVIATION was 7 (≈ 1100× lever swing). That capped
	//     plantED-style levers at 0.001 — not zero. Bumped to 18 so exp
	//     can produce effectively-zero multipliers when the controller
	//     needs to fully shut down a food source.
	//   • Kd doubled to 3.0 — damps the discrete-PID oscillation that
	//     comes from a 5-second tick observing a population that can
	//     change by 10%+ between ticks.
	private static final float PID_KP = 1.1f;
	private static final float PID_KI = 0.06f;
	private static final float PID_KD = 3.0f;
	private static final float PID_INTEGRAL_LEAK = 0.08f; // per tick
	private static final float MAX_LOG_DEVIATION = 18f;   // exp(-18) ≈ 1.5e-8 (effectively zero)

	// Per-parameter gains. Sign = direction (positive control = more pressure):
	//   over-target → less food, faster decay, gentler newborn energy.
	// All levers are *consumption-side* now. Earlier we also drove
	// `plant.collisionDestructionRate` (kill plants on grazer contact harder
	// when protozoa over target) — but that backfired: dead plants become
	// MEAT which then feeds the same overpopulated protozoa we're trying to
	// starve. It also caused thousands of plant deaths per second at high u
	// since the lever was unbounded. Plant abundance is now solely the plant
	// PID's job, leaving this controller to throttle food and accelerate
	// starvation.
	private static final float GAIN_DECAY        = 0.8f;
	private static final float GAIN_PLANT_ED     = -0.85f;
	private static final float GAIN_MEAT_ED      = -0.7f;
	private static final float GAIN_START_E      = -0.5f;
	// New lever: chemical extraction throttle. Plants saturate the chem
	// field regardless of food yield per mass, so even when plantED is at
	// 0.001× the drip still feeds protozoa hundreds of energy/sec — the
	// drip volume itself must be cut to actually starve the population.
	private static final float GAIN_CHEM_EXTRACT = -1.5f;

	private float pidIntegral = 0f;
	private float pidLastError = Float.NaN;
	private long pidLastLogMs = 0;

	// Plant-side PID. Same leaky-integral / high-Kd structure as the
	// protozoa controller. Slightly softer Kp (plants react slower than
	// protozoa to setting changes, so we want gentler proportional gain
	// to avoid chasing noise) and a stronger Kd (plant lifecycle is on
	// the same time-scale as the controller tick, so damping matters more).
	private static final float PLANT_PID_KP = 0.9f;
	private static final float PLANT_PID_KI = 0.05f;
	private static final float PLANT_PID_KD = 4.0f;
	private static final float PLANT_PID_INTEGRAL_LEAK = 0.10f;

	// Plant levers. Photosynthesis is the heavy lever — directly controls
	// energy income, which combined with the universal starvation damage
	// gives selection pressure with teeth. Construction rate limits how
	// much mass a plant has for growth/repair. Growth rate caps how fast
	// they reach split radius. Split-health threshold makes splitting
	// impossible when over-target (threshold > 1 → no plant can split).
	// The new plantSplitRate lever directly scales the probabilistic
	// split rate so even healthy mature plants slow their reproduction
	// when over-target, instead of the old "instant burst on reaching
	// maxRadius" that caused the divide-then-die churn.
	private static final float GAIN_PLANT_PHOTOSYNTHESIS = -1.0f;
	private static final float GAIN_PLANT_CONSTRUCTION   = -0.8f;
	private static final float GAIN_PLANT_GROWTH         = -0.7f;
	private static final float GAIN_PLANT_SPLIT_HEALTH   = 1.2f;
	private static final float GAIN_PLANT_SPLIT_RATE     = -1.3f;

	private float plantPidIntegral = 0f;
	private float plantPidLastError = Float.NaN;

	// Reference values: must match the in-Java defaults set in CellSettings /
	// SimulationSettings. Multipliers are computed relative to these.
	private static final float HOMEO_BASE_DECAY = 0.025f;
	private static final float HOMEO_BASE_PLANT_ED = 2e5f;
	private static final float HOMEO_BASE_MEAT_ED = 6e5f;
	private static final float HOMEO_BASE_START_E = 50f;
	private static final float HOMEO_BASE_CHEM_EXTRACT = 100f;
	private static final float HOMEO_BASE_PLANT_PHOTOSYNTHESIS = 300f;
	private static final float HOMEO_BASE_PLANT_CONSTRUCTION = 10f;
	private static final float HOMEO_BASE_PLANT_MAX_GROWTH = 1.5f;
	private static final float HOMEO_BASE_PLANT_MIN_GROWTH = 0f;
	private static final float HOMEO_BASE_PLANT_SPLIT_HEALTH = 0.15f;
	// Per-second probability a mature plant splits this update. Baseline
	// gives ~30 sim-sec adult lifetime before splitting; the PID scales
	// this to throttle reproduction without snapping it fully off.
	private static final float HOMEO_BASE_PLANT_SPLIT_RATE = 1f / 30f;


	private void homeostasisTick() {
		if (!homeostasisEnabled || environment == null) return;
		// Run the plant controller alongside the protozoa one — they target
		// different populations with different levers, so they can't interfere.
		plantHomeostasisTick();
		int pop = environment.numberOfProtozoa();
		if (pop <= 0) {
			// Extinct: don't update. Reset integral so when life returns
			// we don't yank the world into an over-tight state.
			pidIntegral = 0f;
			pidLastError = Float.NaN;
			return;
		}

		// Normalized error: positive = over target, negative = under.
		float error = ((float) pop - homeostasisTargetPop) / (float) homeostasisTargetPop;

		// Leaky integral: each tick decays the accumulated integral by a
		// fixed fraction, then adds current error. This bounds the integral
		// naturally (steady-state I = error * interval / leak) without the
		// hard clamp that caused undershoot after a big overshoot.
		pidIntegral = pidIntegral * (1f - PID_INTEGRAL_LEAK) + error * homeostasisInterval;

		// Derivative term — first call has no history, treat as zero.
		float derivative = 0f;
		if (!Float.isNaN(pidLastError))
			derivative = (error - pidLastError) / homeostasisInterval;
		pidLastError = error;

		float control = PID_KP * error + PID_KI * pidIntegral + PID_KD * derivative;

		// Apply per-parameter exponential mapping. This keeps multipliers
		// strictly positive and bounded (no zeros, no infinities).
		Environment.settings.cell.energyDecayRate.set(
				HOMEO_BASE_DECAY * mul(GAIN_DECAY * control));
		Environment.settings.plantEnergyDensity.set(
				HOMEO_BASE_PLANT_ED * mul(GAIN_PLANT_ED * control));
		Environment.settings.meatEnergyDensity.set(
				HOMEO_BASE_MEAT_ED * mul(GAIN_MEAT_ED * control));
		Environment.settings.cell.startingAvailableCellEnergy.set(
				HOMEO_BASE_START_E * mul(GAIN_START_E * control));
		Environment.settings.cell.chemicalExtractionFactor.set(
				HOMEO_BASE_CHEM_EXTRACT * mul(GAIN_CHEM_EXTRACT * control));

		// Log roughly every 30 seconds so an overnight run leaves a record
		// of how the controller reacted, without spamming during steady state.
		long now = System.currentTimeMillis();
		if (pidLastLogMs == 0 || now - pidLastLogMs > 30_000L) {
			int plantPop = environment.getCount(com.protoevo.biology.cells.PlantCell.class);
			float plantCtrl = PLANT_PID_KP * ((plantPop - homeostasisTargetPlants) / (float) homeostasisTargetPlants)
					+ PLANT_PID_KI * plantPidIntegral;
			System.out.printf(
					"[homeostat] proto=%d/%d err=%+.2f u=%+.2f decayx%.2f plantEDx%.3f chemDripx%.3f  "
					+ "plants=%d/%d uP=%+.2f photoSynx%.3f splitRatex%.3f%n",
					pop, homeostasisTargetPop, error, control,
					mul(GAIN_DECAY * control), mul(GAIN_PLANT_ED * control),
					mul(GAIN_CHEM_EXTRACT * control),
					plantPop, homeostasisTargetPlants, plantCtrl,
					mul(GAIN_PLANT_PHOTOSYNTHESIS * plantCtrl),
					mul(GAIN_PLANT_SPLIT_RATE * plantCtrl));
			pidLastLogMs = now;
		}
	}

	private void plantHomeostasisTick() {
		int plantPop = environment.getCount(com.protoevo.biology.cells.PlantCell.class);
		if (plantPop <= 0) {
			// All plants gone: clear integral so when seedlings return the
			// world isn't already pressed all the way to no-photosynthesis.
			plantPidIntegral = 0f;
			plantPidLastError = Float.NaN;
			return;
		}

		float error = ((float) plantPop - homeostasisTargetPlants) / (float) homeostasisTargetPlants;
		plantPidIntegral = plantPidIntegral * (1f - PLANT_PID_INTEGRAL_LEAK)
				+ error * homeostasisInterval;

		float derivative = 0f;
		if (!Float.isNaN(plantPidLastError))
			derivative = (error - plantPidLastError) / homeostasisInterval;
		plantPidLastError = error;

		float control = PLANT_PID_KP * error + PLANT_PID_KI * plantPidIntegral + PLANT_PID_KD * derivative;

		Environment.settings.plant.photosynthesizeEnergyRate.set(
				HOMEO_BASE_PLANT_PHOTOSYNTHESIS * mul(GAIN_PLANT_PHOTOSYNTHESIS * control));
		Environment.settings.plant.constructionRate.set(
				HOMEO_BASE_PLANT_CONSTRUCTION * mul(GAIN_PLANT_CONSTRUCTION * control));
		Environment.settings.plant.maxPlantGrowth.set(
				HOMEO_BASE_PLANT_MAX_GROWTH * mul(GAIN_PLANT_GROWTH * control));
		Environment.settings.plant.minPlantGrowth.set(
				HOMEO_BASE_PLANT_MIN_GROWTH * mul(GAIN_PLANT_GROWTH * control));
		Environment.settings.plant.minHealthToSplit.set(
				HOMEO_BASE_PLANT_SPLIT_HEALTH * mul(GAIN_PLANT_SPLIT_HEALTH * control));
		Environment.settings.plant.splitRate.set(
				HOMEO_BASE_PLANT_SPLIT_RATE * mul(GAIN_PLANT_SPLIT_RATE * control));
	}

	/** Map a log-deviation into a positive multiplier, clamped so neither
	 *  end can produce 0 or runaway values. */
	private static float mul(float x) {
		if (x >  MAX_LOG_DEVIATION) x =  MAX_LOG_DEVIATION;
		if (x < -MAX_LOG_DEVIATION) x = -MAX_LOG_DEVIATION;
		return (float) Math.exp(x);
	}

	public boolean isHomeostasisEnabled() { return homeostasisEnabled; }
	public void setHomeostasisEnabled(boolean enabled) {
		homeostasisEnabled = enabled;
		if (!enabled) {
			// Restore baselines AND reset PID state so re-enabling later
			// starts from a clean slate, not the integral we'd built up.
			Environment.settings.cell.energyDecayRate.set(HOMEO_BASE_DECAY);
			Environment.settings.plantEnergyDensity.set(HOMEO_BASE_PLANT_ED);
			Environment.settings.meatEnergyDensity.set(HOMEO_BASE_MEAT_ED);
			Environment.settings.cell.startingAvailableCellEnergy.set(HOMEO_BASE_START_E);
			Environment.settings.cell.chemicalExtractionFactor.set(HOMEO_BASE_CHEM_EXTRACT);
			Environment.settings.plant.photosynthesizeEnergyRate.set(HOMEO_BASE_PLANT_PHOTOSYNTHESIS);
			Environment.settings.plant.constructionRate.set(HOMEO_BASE_PLANT_CONSTRUCTION);
			Environment.settings.plant.maxPlantGrowth.set(HOMEO_BASE_PLANT_MAX_GROWTH);
			Environment.settings.plant.minPlantGrowth.set(HOMEO_BASE_PLANT_MIN_GROWTH);
			Environment.settings.plant.minHealthToSplit.set(HOMEO_BASE_PLANT_SPLIT_HEALTH);
			Environment.settings.plant.splitRate.set(HOMEO_BASE_PLANT_SPLIT_RATE);
			pidIntegral = 0f;
			pidLastError = Float.NaN;
			plantPidIntegral = 0f;
			plantPidLastError = Float.NaN;
			pidLastLogMs = 0;
		}
		System.out.println("Homeostat: " + (enabled ? "ON" : "OFF (defaults restored)"));
	}
	public int getHomeostasisTargetPop() { return homeostasisTargetPop; }
	public void setHomeostasisTargetPop(int target) {
		homeostasisTargetPop = Math.max(10, target);
		// Reset integral so the controller doesn't carry over windup from the
		// old setpoint into the new one — common cause of big initial swings
		// after a target change.
		pidIntegral = 0f;
		pidLastError = Float.NaN;
		System.out.println("Homeostat target population: " + homeostasisTargetPop);
	}
	public int getHomeostasisTargetPlants() { return homeostasisTargetPlants; }
	public void setHomeostasisTargetPlants(int target) {
		homeostasisTargetPlants = Math.max(10, target);
		plantPidIntegral = 0f;
		plantPidLastError = Float.NaN;
		System.out.println("Homeostat target plants: " + homeostasisTargetPlants);
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
			// Sim-time advancement model:
			//   one render call advances sim time by `timeDilation` × baseDt.
			// At low td we run the inner loop with dt=baseDt (small, stable).
			// At high td we'd otherwise call physics+cell update hundreds of
			// times per frame; instead we use BIGGER physics steps capped at a
			// stability ceiling that grows with td, so 128× ends up doing
			// ~8 medium steps per render frame instead of 128 tiny ones. Cell
			// logic is linear in dt so batching is safe; physics has loads of
			// headroom because max cell speed is ~0.05 units/s (terminal
			// velocity under fluid damping 10 with cilia thrust 0.0005); even
			// dt=0.05 won't tunnel a 0.02-radius cell. Chemicals need the
			// per-pixel cap in protozoanIO (already in place) to be safe.
			float baseDt = Environment.settings.simulationUpdateDelta.get();
			float td = Math.max(0f, timeDilation);
			float totalDt = td * baseDt;

			// Stability ceiling scales with time dilation so the per-render
			// step count stays small even at very high td. Max cell speed is
			// ~0.05 units/s, min radius ~0.02, so even dt=0.1s only moves a
			// cell 1/4 of its diameter per step — Box2D handles this fine.
			// At td=1×: stepDt = 8×baseDt = 0.008 (1 step per render)
			// At td=32: stepDt = 8×baseDt → 4 steps per render
			// At td=128: stepDt = 32×baseDt = 0.032 → 4 steps per render
			// At td=256: stepDt = 64×baseDt = 0.064 → 4 steps per render
			float maxStableStep = baseDt * Math.min(64f, Math.max(8f, td / 4f));
			// Per-render-frame cap on the number of big steps. Above this the
			// sim just won't keep up — better to run slow than hang.
			int maxStepsPerFrame = 16;

			try {
				// Chemicals AND plant/meat updates are batched once per render
				// frame. Both are expensive (chem deposit is a parallel stream
				// over all cells painting their footprint; plant cell updates
				// are 1500× per pass and mostly do linear-in-delta work) and
				// neither benefits from sub-frame granularity. Per-pixel
				// extraction cap in protozoanIO makes the chem batching safe;
				// plant/meat batching is safe because their update is linear
				// in delta and Environment accumulates skipped dt internally.
				// Protozoa still update every step — they're the active
				// agents and need sub-frame collision/eating fidelity.
				float remaining = totalDt;
				float pendingChem = 0f;
				int stepsThisFrame = 0;
				while (remaining > 1e-9f && stepsThisFrame < maxStepsPerFrame) {
					float stepDt = Math.min(remaining, maxStableStep);
					pendingChem += stepDt;
					boolean isLastStep =
							(remaining - stepDt) <= 1e-9f
							|| (stepsThisFrame + 1) >= maxStepsPerFrame;
					float chemDt = isLastStep ? pendingChem : 0f;
					environment.update(stepDt, chemDt, isLastStep);
					timedEventsManager.update(stepDt);
					if (isLastStep)
						pendingChem = 0f;
					remaining -= stepDt;
					stepsThisFrame++;
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
