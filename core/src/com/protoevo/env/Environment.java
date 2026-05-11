package com.protoevo.env;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.collect.Streams;
import com.protoevo.biology.BurstRequest;
import com.protoevo.biology.CauseOfDeath;
import com.protoevo.biology.cells.Cell;
import com.protoevo.biology.cells.MeatCell;
import com.protoevo.biology.cells.PlantCell;
import com.protoevo.biology.cells.Protozoan;
import com.protoevo.biology.evolution.Evolvable;
import com.protoevo.biology.nodes.NodeAttachment;
import com.protoevo.core.Statistics;
import com.protoevo.maths.Shape;
import com.protoevo.physics.*;
import com.protoevo.physics.box2d.Box2DPhysics;
import com.protoevo.settings.SimulationSettings;
import com.protoevo.maths.Geometry;
import com.protoevo.utils.SerializableFunction;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;


public class Environment implements Serializable
{
	private static final long serialVersionUID = 2804817237950199223L;
	private static final GetCellFunction getParticleFn = new GetCellFunction();
	public static final CellIsProtozoaPredicate isCellProtozoa = new CellIsProtozoaPredicate();
	public static SimulationSettings settings = SimulationSettings.createDefault();

	private final SimulationSettings mySettings;
	private String simulationName;
	private final Physics physics;
	private String loadingStatus;
	private final Statistics stats = new Statistics();
	private final Statistics debugStats = new Statistics();
	public final ConcurrentHashMap<CauseOfDeath, Integer> causeOfDeathCounts =
			new ConcurrentHashMap<>(CauseOfDeath.values().length, 1);

	private transient Chunks chunks;

	private Map<Class<? extends Cell>, SerializableFunction<Float, Vector2>> spawnPositionFns;

	private final ChemicalSolution chemicalSolution;
	private final LightManager light;
	private final TimeManager timeManager;
	private transient CurrentField currentField;
	private transient Vector2 currentForce;
	// Monotonically increasing lineage id counter. Each new cell that isn't
	// a descendant of an existing one (initial spawn, manual injection)
	// gets the next value; burst children inherit their parent's lineage id.
	private long nextLineageId = 1L;
	// Phylogeny records — keyed by cell id, persists past death so the
	// tree view can walk parents. Bounded by `pruneDeadLineages()` which
	// runs periodically. Public-ish via getLineageRecords() for the UI.
	private final java.util.HashMap<Long, LineageRecord> lineageRecords =
			new java.util.HashMap<>();
	private final List<Rock> rocks = new ArrayList<>();
	private final HashMap<Class<? extends Cell>, Long> bornCounts = new HashMap<>(3);
	private final HashMap<Class<? extends Cell>, Long> generationCounts = new HashMap<>(3);
	private final static HashMap<Class<? extends Cell>, String> cellClassNames = new HashMap<>(3);
	static {
		cellClassNames.put(Protozoan.class, "Protozoa");
		cellClassNames.put(PlantCell.class, "Plants");
		cellClassNames.put(MeatCell.class, "Meat");
	}
	private volatile long crossoverEvents = 0;

	@JsonIgnore
	private transient Set<Cell> cellsToAdd;
	private final ConcurrentHashMap<Long, Cell> cells = new ConcurrentHashMap<>();
	private boolean hasInitialised, hasStarted;
	private Vector2[] populationStartCentres;
	@JsonIgnore
	private final ConcurrentHashMap<Cell, BurstRequest<? extends Cell>> burstRequests = new ConcurrentHashMap<>();
	private final Collection<Cell> handledBurstRequests = new ConcurrentLinkedQueue<>();
	private final CellDeadPredicate isDeadPredicate = new CellDeadPredicate();
	// Per-frame consumer for the parallel cell-update pass. Reused so each
	// update doesn't allocate a fresh lambda holder; only the `delta` field
	// changes per call.
	private transient CellUpdateConsumer cellUpdateConsumer;
	// Plant + meat delta accumulator. Their update() is skipped on
	// intermediate substeps at high time dilation, and the accumulated dt
	// is applied on the next "plants update" pass so the linear-in-delta
	// terms (photosynthesis, growth, decay) integrate correctly. Non-linear
	// edge events (plant-protozoa collision damage pulses) are sampled at
	// the protozoa-pass cadence instead, which is fine because protozoa
	// always update every step.
	private transient float plantPendingDt = 0f;

	public Environment()
	{
		this(settings);
	}

	public Environment(SimulationSettings settings) {
		mySettings = settings;
		Environment.settings = settings;

		hasStarted = false;
		createTransientObjects();
		physics = new Box2DPhysics();

		System.out.println("Creating chemicals solution... ");
		if (Environment.settings.enableChemicalField.get()) {
			chemicalSolution = new ChemicalSolution(
					this,
					Environment.settings.worldgen.chemicalFieldResolution.get(),
					Environment.settings.worldgen.chemicalFieldRadius.get());
		} else {
			chemicalSolution = null;
		}

		timeManager = new TimeManager();

		int lightDim = Environment.settings.worldgen.lightMapResolution.get();
		light = new LightManager(lightDim, lightDim, Environment.settings.worldgen.radius.get());
		light.setTimeManager(timeManager);

		hasInitialised = false;
		NodeAttachment.setupPossibleAttachments();
	}

	public void createTransientObjects() {
		cellsToAdd = new HashSet<>();
		chunks = new Chunks();
		chunks.initialise();
		forceChunkRebuild();
		rebuildCurrentField();
	}

	private void rebuildCurrentField() {
		// Defensive defaults: a save from before these params existed will
		// deserialize EnvironmentSettings without them, and even with
		// CompatibleFieldSerializer the field stays at its constructor
		// default value (which only runs if Kryo invokes the constructor;
		// not all instantiator strategies do).
		float strength = (settings.env != null && settings.env.currentStrength != null)
				? settings.env.currentStrength.get() : 1.5e-5f;
		float scale = (settings.env != null && settings.env.currentSpatialScale != null)
				? settings.env.currentSpatialScale.get() : 4f;
		float rate = (settings.env != null && settings.env.currentTimeRate != null)
				? settings.env.currentTimeRate.get() : 0.05f;
		currentField = new CurrentField(strength, scale, rate);
		currentForce = new Vector2();
	}

	private void applyCurrents() {
		if (currentField == null)
			rebuildCurrentField();
		float intensity = settings.env.currentStrength.get();
		if (intensity <= 0f)
			return;
		// Keep CurrentField in sync with the settings so a live tweak via
		// REPL or UI reflects immediately. Cheap — three field copies.
		currentField.setIntensity(intensity);
		float t = timeManager.getTimeElapsed();
		for (Cell cell : getCells()) {
			Vector2 p = cell.getPos();
			currentField.sample(p.x, p.y, t, currentForce);
			cell.getParticle().applyForce(currentForce);
		}
	}

	public boolean hasStarted() {
		return hasStarted;
	}

	public void rebuildWorld() {
		settings = mySettings;
		NodeAttachment.setupPossibleAttachments();
		physics.rebuildTransientFields(this);
		for (Cell cell : getCells())
			cell.setEnvironment(this);
		forceChunkRebuild();
	}

	public void update(float delta)
	{
		update(delta, delta);
	}

	/**
	 * Split-delta version for fast-forward substepping.
	 *
	 * @param physicsDelta   delta used for the physics step + per-cell update.
	 *                       MUST be the small per-substep dt — running physics
	 *                       with a giant accumulated delta produces unstable
	 *                       behavior (cells tunneling through rocks, etc.).
	 * @param chemicalsDelta total accumulated delta for the chemical pass.
	 *                       Pass 0 to skip chemicals this call entirely; pass
	 *                       the same value as physicsDelta on the last substep
	 *                       to make the deposit reflect the time we *actually*
	 *                       advanced. The previous version skipped deposits on
	 *                       intermediate substeps, which made plants emit ~1/N
	 *                       as much chemical at N× speed — protozoa lost the
	 *                       gradient they navigate by, and populations starved.
	 */
	public void update(float physicsDelta, float chemicalsDelta)
	{
		update(physicsDelta, chemicalsDelta, true);
	}

	/**
	 * @param plantsAndMeatThisStep if true, plant + meat cells run their full
	 *   update this step. Otherwise only protozoa update — used by the fast-
	 *   forward scheduler so we don't pay for 1500 plant updates on every
	 *   substep when the per-render budget only fits one or two passes.
	 *   Plants get a fat-delta update once per render frame instead. Since
	 *   plant logic is linear in delta (photosynthesis, growth, decay) this
	 *   integrates correctly; the only thing that loses fidelity is the
	 *   plant–protozoa contact-death pulse, which evens out over many frames.
	 */
	public void update(float physicsDelta, float chemicalsDelta,
	                   boolean plantsAndMeatThisStep)
	{
		hasStarted = true;
		settings = mySettings;
		for (Cell cell : getCells())
			cell.getParticle().physicsUpdate();

		timeManager.update(physicsDelta);
		light.update(physicsDelta);

		// Apply environmental current as a per-cell force. Done before the
		// Box2D step so the force is integrated into this step's motion.
		// Single-threaded — the math is cheap (a handful of sin/cos per
		// cell) and avoids the thread-local Vector2 dance you'd need for
		// a parallelStream.
		applyCurrents();

		if (physicsDelta > 0f)
			physics.step(physicsDelta);

  		handleCellUpdates(physicsDelta, plantsAndMeatThisStep);
		handleBirthsAndDeaths();
		updateChunkAllocations(physicsDelta);
		pruneDeadLineages(physicsDelta);

		physics.getJointsManager().flushJoints();

		if (chemicalsDelta > 0f && Environment.settings.enableChemicalField.get()) {
			chemicalSolution.update(chemicalsDelta);
		}
	}


	public void ensureAddedToEnvironment(Cell cell) {
		if (!cells.containsKey(cell.getId()))
			registerToAdd(cell);
	}

	private void handleCellUpdates(float delta, boolean updatePlantsAndMeat) {
		if (cellUpdateConsumer == null)
			cellUpdateConsumer = new CellUpdateConsumer(delta);
		cellUpdateConsumer.delta = delta;
		// Plants/meat get caught-up delta on the steps where they actually run.
		// On skipped steps we accumulate; on running steps they receive
		// (current delta) + (sum of skipped deltas).
		if (updatePlantsAndMeat) {
			cellUpdateConsumer.plantDelta = delta + plantPendingDt;
			plantPendingDt = 0f;
		} else {
			plantPendingDt += delta;
		}
		cellUpdateConsumer.updatePlantsAndMeat = updatePlantsAndMeat;
		getCells().parallelStream().forEach(cellUpdateConsumer);
	}

	private void handleBirthsAndDeaths() {
		handledBurstRequests.clear();
		for (Cell parent : burstRequests.keySet()) {
			BurstRequest<? extends Cell> burstRequest = burstRequests.get(parent);
			if (hasBurstCapacity(parent, burstRequest.getCellType()) && burstRequest.canBurst()) {
				burstRequest.burst();
				handledBurstRequests.add(parent);
			}
		}
		for (Cell parent : handledBurstRequests)
			burstRequests.remove(parent);
		handledBurstRequests.clear();

		flushEntitiesToAdd();

		// Single pass: dispose+deposit+remove for any dead cell. The previous
		// version iterated the map twice (once to dispose/deposit, once to
		// removeIf) — at large populations that's a measurable repeat scan.
		// We also pull the cell out of `chunks` here so the spatial index
		// stays consistent without needing a full clear-and-rebuild on every
		// frame (see updateChunkAllocations comment).
		getCells().removeIf(cell -> {
			if (!cell.isDead())
				return false;
			dispose(cell);
			depositOnDeath(cell);
			chunks.remove(cell);
			recordDeath(cell);
			return true;
		});
	}

	public void createRocks() {
		System.out.println("Creating rocks structures...");
		rocks.addAll(WorldGeneration.generate());
		physics.registerStaticBodies(this);
	}

	public void initialise() {
		System.out.println("Commencing world generation... ");
		loadingStatus = "Generating World";
		createRocks();
		loadingStatus = "Creating Light";
		System.out.println("Baking shadows... ");
		if (settings.worldgen.bakeRockLights.get())
			LightManager.bakeRockShadows(light, rocks);
		if (settings.worldgen.generateLightNoiseTexture.get())
			light.generateNoiseLight(0);

		loadingStatus = "Creating Population";
		initialisePopulation();

		flushEntitiesToAdd();

		if (chemicalSolution != null) {
			loadingStatus = "Creating Chemicals";
			chemicalSolution.initialise();
		}

		loadingStatus = "Initialisation Complete";
		hasInitialised = true;
		System.out.println("Environment initialisation complete.");
	}

	public boolean hasBeenInitialised() {
		return hasInitialised;
	}

	private void buildSpawners() {
		spawnPositionFns = new HashMap<>(3, 1);
		if (populationStartCentres != null) {
			final float clusterR = Environment.settings.worldgen.populationClusterRadius.get();
			spawnPositionFns.put(PlantCell.class,
					new SpawnPlantInClustersFn(this, populationStartCentres, clusterR));
			spawnPositionFns.put(Protozoan.class,
					new SpawnProtozoaInClustersFn(this, populationStartCentres, clusterR));
		}
		else {
			spawnPositionFns.put(PlantCell.class, new SpawnInRandomPositionFn(this));
			spawnPositionFns.put(Protozoan.class, new SpawnInRandomPositionFn(this));
		}
	}

	public void initialisePopulation() {
		populationStartCentres = new Vector2[Environment.settings.worldgen.numPopulationStartClusters.get()];
		final float clusterR = Environment.settings.worldgen.populationClusterRadius.get();
		for (int i = 0; i < populationStartCentres.length; i++)
			populationStartCentres[i] = Geometry.randomPointInCircle(
					Environment.settings.worldgen.radius.get() - clusterR, WorldGeneration.RANDOM
			);

		buildSpawners();

		int nPlants = Environment.settings.worldgen.numInitialPlantPellets.get();
		nPlants = Math.min(nPlants, chunks.getGlobalCapacity(PlantCell.class));
		System.out.println("Creating population of " + nPlants + " plants..." );
		loadingStatus = "Seeding Plants";
		for (int i = 0; i < nPlants; i++) {
			PlantCell cell;
			if (Environment.settings.plant.evolutionEnabled.get())
				cell = Evolvable.createNew(PlantCell.class);
			else
				cell = new PlantCell();
			cell.setEnvironmentAndBuildPhysics(this);
			findRandomPositionOrKillCell(cell);
		}

		int nProtozoa = Environment.settings.worldgen.numInitialProtozoa.get();
		nProtozoa = Math.min(nProtozoa, chunks.getGlobalCapacity(Protozoan.class));
		System.out.println("Creating population of " + nProtozoa + " protozoa...");
		loadingStatus = "Spawning Protozoa";
		for (int i = 0; i < nProtozoa; i++) {
			Protozoan p = Evolvable.createNew(Protozoan.class);
			p.setEnvironmentAndBuildPhysics(this);
			findRandomPositionOrKillCell(p);
		}

		for (Cell cell : cellsToAdd)
			cell.getParticle().applyImpulse(Geometry.randomVector(.01f));
	}

	public void findRandomPositionOrKillCell(Cell cell) {
		Vector2 pos = getRandomPosition(cell);
		if (pos == null) {
			cell.kill(CauseOfDeath.FAILED_TO_CONSTRUCT);
			return;
		}
		cell.getParticle().setPos(pos);
	}

	public Vector2 randomPosition(float entityRadius, Vector2[] clusterCentres) {
		int clusterIdx = MathUtils.random(clusterCentres.length - 1);
		Vector2 clusterCentre = clusterCentres[clusterIdx];
		return randomPosition(entityRadius, clusterCentre, Environment.settings.worldgen.populationClusterRadius.get());
	}

	public Vector2 randomPosition(float entityRadius, Vector2[] clusterCentres, float clusterRadius) {
		int clusterIdx = MathUtils.random(clusterCentres.length - 1);
		Vector2 clusterCentre = clusterCentres[clusterIdx];
		return randomPosition(entityRadius, clusterCentre, clusterRadius);
	}

	public Vector2 randomPosition(float entityRadius, Vector2 centre, float clusterRadius) {
		for (int i = 0; i < 20; i++) {
			float r = WorldGeneration.RANDOM.nextFloat() * clusterRadius;
			Vector2 pos = Geometry.randomPointInCircle(r, WorldGeneration.RANDOM);
			pos.add(centre);
			Optional<? extends Shape> collision = getCollidingShape(pos, entityRadius);
			if (collision.isPresent() && collision.get() instanceof Particle
					&& ((Particle) collision.get()).getUserData() instanceof PlantCell) {
				PlantCell plant = ((Particle) collision.get()).getUserData(PlantCell.class);
				plant.kill(CauseOfDeath.ENV_CAPACITY_EXCEEDED);
				return pos;
			} else if (collision.isEmpty())
				return pos;
		}

		return null;
	}

	public Vector2 getRandomPosition(Cell cell) {
//		return spawnPositionFns.getOrDefault(cell.getClass(), new SerializableFunction<Float, Vector2>() {
//                    @Override
//                    public Vector2 apply(Float entityRadius) {
//                        return Environment.this.randomPosition(entityRadius);
//                    }
//                })
//				.apply(cell.getRadius());
		SerializableFunction<Float, Vector2> fn = spawnPositionFns.get(cell.getClass());
		if (fn == null)
			return randomPosition(cell.getRadius());
		return fn.apply(cell.getRadius());
	}

	public Vector2 getRandomPosition(Class<? extends Cell> cellClass) {
//		return spawnPositionFns.getOrDefault(cellClass, new SerializableFunction<Float, Vector2>() {
//            @Override
//            public Vector2 apply(Float entityRadius) {
//                return Environment.this.randomPosition(entityRadius);
//            }
//        }).apply(0f);
		SerializableFunction<Float, Vector2> fn = spawnPositionFns.get(cellClass);
		if (fn == null)
			return randomPosition(0f);
		return fn.apply(0f);
	}

	public Vector2 randomPosition(float entityRadius) {
		return randomPosition(entityRadius, Geometry.ZERO, Environment.settings.worldgen.minRockClusterRadius.get());
	}

	public void tryAdd(Cell cell) {
		add(cell);
		// Assign a founder lineage id if this cell wasn't already tagged
		// (initial-spawn cells, or any future case where we inject cells
		// without a parent). Burst children get their parent's id set by
		// BurstRequest before they hit this path.
		if (cell.getLineageId() == 0L)
			cell.setLineageId(nextLineageId++);
		recordBirth(cell);
		bornCounts.put(cell.getClass(),
				bornCounts.getOrDefault(cell.getClass(), 0L) + 1);
		generationCounts.put(cell.getClass(),
				Math.max(generationCounts.getOrDefault(cell.getClass(), 0L),
						 cell.getGeneration()));
	}

	public long allocateLineageId() {
		return nextLineageId++;
	}

	private void recordBirth(Cell cell) {
		// Only track *protozoa* in the phylogeny. Plants and meat exist in
		// such churn (a meat pellet's "lineage" is meaningless; plants
		// barely evolve and 1500 of them swamp the tree with single-cell
		// chains) that including them turns the tree into a 10k-leaf
		// hairball nobody can read. The tree is meant to show interesting
		// evolutionary divergence — that's a protozoa-only concept here.
		if (!(cell instanceof Protozoan))
			return;
		LineageRecord r = new LineageRecord();
		r.id = cell.getId();
		r.parentId = cell.getParentId();
		r.generation = cell.getGeneration();
		r.birthTime = timeManager.getTimeElapsed();
		r.deathTime = -1f;
		r.aliveDescendants = 1;
		r.cellType = 0; // Protozoan
		lineageRecords.put(r.id, r);
		// Propagate the +1 alive-descendants count up the parent chain so
		// the renderer can prioritise lineages with the most living
		// descendants without scanning the whole tree every frame.
		long pid = r.parentId;
		while (pid != 0L) {
			LineageRecord p = lineageRecords.get(pid);
			if (p == null) break;
			p.aliveDescendants++;
			pid = p.parentId;
		}
	}

	private void recordDeath(Cell cell) {
		LineageRecord r = lineageRecords.get(cell.getId());
		if (r == null) return; // plants/meat were never tracked
		r.deathTime = timeManager.getTimeElapsed();
		r.aliveDescendants--; // self
		long pid = r.parentId;
		while (pid != 0L) {
			LineageRecord p = lineageRecords.get(pid);
			if (p == null) break;
			p.aliveDescendants--;
			pid = p.parentId;
		}
	}

	private float lineagePruneTimer = 0f;
	/** Drop lineage records whose entire subtree died out long enough ago
	 *  that they're no longer interesting for the tree view. Keeps the
	 *  record map bounded — without this it grows with every birth. */
	private void pruneDeadLineages(float delta) {
		lineagePruneTimer += delta;
		if (lineagePruneTimer < 30f) return;
		lineagePruneTimer = 0f;
		float now = timeManager.getTimeElapsed();
		final float keepDeadFor = 120f; // sim-sec of grace
		lineageRecords.values().removeIf(r ->
				r.aliveDescendants <= 0
				&& r.deathTime > 0f
				&& (now - r.deathTime) > keepDeadFor);
	}

	public java.util.Map<Long, LineageRecord> getLineageRecords() {
		return lineageRecords;
	}

	public void add(Cell cell) {
		cells.put(cell.getId(), cell);
		chunks.add(cell);
	}

	public Optional<Cell> getCell(long id) {
		return Optional.ofNullable(cells.get(id));
	}

	private void flushEntitiesToAdd() {
		for (Cell cell : cellsToAdd)
			tryAdd(cell);
		cellsToAdd.clear();
	}

	public int getCount(Class<? extends Cell> cellClass) {
		return chunks.getLocalCount(cellClass);
	}

	// Full chunk rebuild is expensive (clear all 1200 sets + re-add every
	// cell). Births/deaths are now tracked incrementally via chunks.add /
	// chunks.remove, so the only thing a full rebuild catches is cells that
	// have *moved* between chunks. Cells take many frames to traverse a chunk,
	// so stale-by-a-second counts are fine for capacity checks. We still do a
	// periodic full rebuild to clear any drift. Transient so adding this
	// field doesn't break older saves.
	private transient float chunkRebuildTimer = 0f;
	private static final float CHUNK_REBUILD_INTERVAL = 0.5f;

	public void updateChunkAllocations(float delta) {
		chunkRebuildTimer += delta;
		if (chunkRebuildTimer < CHUNK_REBUILD_INTERVAL)
			return;
		chunkRebuildTimer = 0f;
		chunks.clear();
		for (Cell cell : getCells())
			chunks.allocate(cell);
	}

	public void forceChunkRebuild() {
		chunks.clear();
		for (Cell cell : getCells())
			chunks.allocate(cell);
		chunkRebuildTimer = 0f;
	}

	private void dispose(Cell e) {
		CauseOfDeath cod = e.getCauseOfDeath();
		if (cod != null) {
			int count = causeOfDeathCounts.getOrDefault(cod, 0);
			causeOfDeathCounts.put(cod, count + 1);
		}
		e.getParticle().dispose();
	}

	public void depositOnDeath(Cell cell) {
		if (settings.enableChemicalField.get()) {
			if (!cell.isEngulfed() && cell.hasNotBurst()) {
				chemicalSolution.depositCircle(
						cell.getPos(), cell.getRadius() * 1.25f,
						cell.getColour());
			}
		}
	}


	public boolean hasCapacity(Class<? extends Cell> cellType, Vector2 pos) {
		return getLocalCount(cellType, pos) < getLocalCapacity(cellType)
				&& getGlobalCount(cellType) < getGlobalCapacity(cellType);
	}

	public boolean hasCapacity(Cell cell) {
		return hasCapacity(cell.getClass(), cell.getPos());
	}

	public int getGlobalCount(Class<? extends Cell> cellType) {
		int toAdd = 0;
		for (Cell cell : cellsToAdd)
			if (cell.getClass().equals(cellType))
				toAdd++;

		return chunks.getGlobalCount(cellType) + toAdd;
	}

	public int getGlobalCapacity(Cell cell) {
		return chunks.getGlobalCapacity(cell);
	}

	public int getGlobalCapacity(Class<? extends Cell> cellType) {
		return chunks.getGlobalCapacity(cellType);
	}

	public int getLocalCount(Cell cell) {
		return getLocalCount(cell.getClass(), cell.getPos());
	}

	public int getLocalCount(Class<? extends Cell> cellType, Vector2 pos) {
		int existingCount = chunks.getChunkCount(cellType, pos);
		SpatialHash<? extends Cell> cellHash = chunks.getCellHash(cellType, pos);
		int chunkX = cellHash.getChunkX(pos.x);
		int chunkY = cellHash.getChunkY(pos.y);
		for (Cell cell : cellsToAdd) {
			int thisChunkX = cellHash.getChunkX(cell.getPos().x);
			int thisChunkY = cellHash.getChunkY(cell.getPos().y);
			if (thisChunkY == chunkY && thisChunkX == chunkX)
				existingCount++;
		}
		return existingCount;
	}

	public int getLocalCapacity(Cell cell) {
		return getLocalCapacity(cell.getClass());
	}

	public int getLocalCapacity(Class<? extends Cell> cellType) {
		return chunks.getChunkCapacity(cellType);
	}

	public void registerToAdd(Cell e) {
		cellsToAdd.add(e);
	}

	public Statistics getStats() {
		Statistics stats = new Statistics();
		stats.putTime("Time Elapsed", timeManager.getTimeElapsed());
		stats.putPercentage("Time of Day", timeManager.getTimeOfDayPercentage());
		stats.put("Days Elapsed", timeManager.getDay());
		stats.putCount("Protozoa", numberOfProtozoa());
		stats.putCount("Plants", getCount(PlantCell.class));
		stats.putCount("Meat Pellets", getCount(MeatCell.class));

		stats.putPercentage("Sky Light Level", 100 * light.getEnvLight());

		stats.putCount("Max Protozoa Generation",
						generationCounts.getOrDefault(Protozoan.class, 1L).intValue());

		stats.putCount("Max Plant Generation",
				generationCounts.getOrDefault(PlantCell.class, 0L).intValue());

		for (Class<? extends Cell> cellClass : bornCounts.keySet())
			stats.putCount(cellClassNames.get(cellClass) + " Created",
					bornCounts.get(cellClass).intValue());

		if (Environment.settings.protozoa.matingEnabled.get())
			stats.putCount("Crossover Events", (int) crossoverEvents);

		for (CauseOfDeath cod : CauseOfDeath.values()) {
			if (cod.isDebugDeath())
				continue;
			int count = causeOfDeathCounts.getOrDefault(cod, 0);
			if (count > 0)
				stats.putCount("Died from " + cod.getReason(), count);
		}
		return stats;
	}

	public Statistics getDebugStats() {
		debugStats.clear();
		for (CauseOfDeath cod : CauseOfDeath.values()) {
			if (!cod.isDebugDeath())
				continue;
			int count = causeOfDeathCounts.getOrDefault(cod, 0);
			if (count > 0)
				debugStats.put("Died from " + cod.getReason(), (float) count);
		}
		return debugStats;
	}

	public Statistics getPhysicsDebugStats() {
		return physics.getDebugStats();
	}

	public Statistics getProtozoaSummaryStats(
			boolean computeLogStats, boolean removeMoleculeStats, boolean allStats) {
		Iterator<Statistics> protozoaStats = getCells().stream()
				.filter(isCellProtozoa)
				.map(new CellStatisticsFn(allStats))
				.iterator();

		Statistics stats = Statistics.computeSummaryStatistics(protozoaStats, computeLogStats);
		final int protozoaCount = numberOfProtozoa();
		stats.putCount("Protozoa Count", protozoaCount);
		stats.getStatsMap().entrySet().removeIf(
				new RemoveStatFromSummaryPredicate(protozoaCount, removeMoleculeStats, allStats)
		);
		return stats;
	}

	public Statistics getProtozoaSummaryStats() {
		return getProtozoaSummaryStats(false, true, false);
	}

	public int numberOfProtozoa() {
		return getCount(Protozoan.class);
	}

	public long getGeneration() {
		return generationCounts.getOrDefault(Protozoan.class, 0L);
	}

	public Optional<? extends Shape> getCollidingShape(Vector2 pos, float r) {
		Optional<Rock> collidingRock = rocks.stream()
				.filter(new RockCollisionWithParticlePredicate(pos, r))
				.findAny();
		if (collidingRock.isPresent())
			return collidingRock;

		return Streams.concat(getCells().stream(), cellsToAdd.stream())
				.filter(new DoCirclesCollidePredicate(pos, r))
				.map(getParticleFn)
				.findAny();
	}

	public float getElapsedTime() {
		return timeManager.getTimeElapsed();
	}

	public ChemicalSolution getChemicalSolution() {
		return chemicalSolution;
	}

	public List<Rock> getRocks() {
		return rocks;
	}

	public Physics getPhysics() {
		return physics;
	}

	public Collection<Cell> getCells() {
		return cells.values();
	}

	public Stream<Particle> getParticles() {
		return getCells().stream().map(getParticleFn);
	}

	public JointsManager getJointsManager() {
		return physics.getJointsManager();
	}

	public <T extends Cell> boolean hasBurstRequest(Cell parent, Class<T> cellType) {
		return burstRequests.containsKey(parent) &&
				burstRequests.get(parent).getCellType().equals(cellType);
	}

	public boolean hasBurstCapacity(Cell parent, Class<? extends Cell> cellType) {
		return hasCapacity(cellType, parent.getPos());
	}

	public <T extends Cell> void requestBurst(Cell parent,
											  Class<T> cellType,
											  SerializableFunction<Float, T> createChild,
											  boolean overrideMinParticleSize) {

		if (hasBurstRequest(parent, cellType) || !hasBurstCapacity(parent, cellType))
			return;

		burstRequests.put(parent, new BurstRequest<>(parent, cellType, createChild, overrideMinParticleSize));
	}

	public <T extends Cell> void requestBurst(Cell parent,
											  Class<T> cellType,
											  SerializableFunction<Float, T> createChild) {
		requestBurst(parent, cellType, createChild, false);
	}

	public SpatialHash<Cell> getSpatialHash(Class<? extends Cell> clazz) {
		return chunks.getSpatialHash(clazz);
	}

	public Chunks getChunks() {
		return chunks;
	}

	public void incrementCrossOverCount() {
		crossoverEvents = crossoverEvents + 1;
	}

	public float getRadius() {
		return settings.worldgen.radius.get();
	}

	public void dispose() {
		physics.dispose();
	}

	public LightManager getLightMap() {
		return light;
	}

	public float getLight(Vector2 pos) {
		return light.getLightLevel(pos);
	}

	public float getTemperature(Vector2 pos) {
		return light.getLightLevel(pos) * settings.env.maxLightEnvTemp.get();
	}

	public String getLoadingStatus() {
		return loadingStatus;
	}

	public SimulationSettings getSettings() {
		return mySettings;
	}

	public void setSimulationName(String simulationName) {
		this.simulationName = simulationName;
	}

	public String getSimulationName() {
		return simulationName;
	}

	public static class CellUpdateConsumer implements Serializable, Consumer<Cell> {
		float delta;
		float plantDelta;
		boolean updatePlantsAndMeat = true;

		public CellUpdateConsumer() {}

		public CellUpdateConsumer(float delta) {
			this.delta = delta;
			this.plantDelta = delta;
		}

		@Override
		public void accept(Cell cell) {
			if (cell instanceof Protozoan) {
				cell.update(delta);
				return;
			}
			if (!updatePlantsAndMeat)
				return;
			cell.update(plantDelta);
		}
	}

	public static class GetCellFunction implements SerializableFunction<Cell, Particle> {
		@Override
		public Particle apply(Cell cell) {
			return cell.getParticle();
		}
	}

	public static class DoCirclesCollidePredicate implements Serializable, Predicate<Cell> {
		private Vector2 pos;
		private float r;

		public DoCirclesCollidePredicate() {}

		public DoCirclesCollidePredicate(Vector2 pos, float r) {
			this.pos = pos;
			this.r = r;
		}

		@Override
		public boolean test(Cell cell) {
			return Geometry.doCirclesCollide(pos, r, cell.getPos(), cell.getRadius());
		}
	}

	public static class RemoveStatFromSummaryPredicate implements Serializable, Predicate<Map.Entry<String, Statistics.Stat>> {
		private int protozoaCount;
		private boolean removeMoleculeStats;
		private boolean allStats;

		public RemoveStatFromSummaryPredicate() {}

		public RemoveStatFromSummaryPredicate(int protozoaCount, boolean removeMoleculeStats, boolean allStats) {
			this.protozoaCount = protozoaCount;
			this.removeMoleculeStats = removeMoleculeStats;
			this.allStats = allStats;
		}

		@Override
		public boolean test(Map.Entry<String, Statistics.Stat> entry) {
			return entry.getKey().endsWith("Count")
					&& ((int) entry.getValue().getValue() == 0 || (int) entry.getValue().getValue() == protozoaCount)
					|| (removeMoleculeStats && entry.getKey().contains("Molecule"))
					|| (!allStats && (entry.getKey().contains("Min") || entry.getKey().contains("Max")));
		}
	}

	public static class RockCollisionWithParticlePredicate implements Serializable, Predicate<Rock> {
		private Vector2 pos;
		private float r;

		public RockCollisionWithParticlePredicate() {}

		public RockCollisionWithParticlePredicate(Vector2 pos, float r) {
			this.pos = pos;
			this.r = r;
		}

		@Override
		public boolean test(Rock rock) {
			return rock.intersectsWith(pos, r);
		}
	}

	public static class CellIsProtozoaPredicate implements Serializable, Predicate<Cell> {

		public CellIsProtozoaPredicate() {}

		@Override
		public boolean test(Cell cell) {
			return cell instanceof Protozoan;
		}
	}

	public static class CellDeadPredicate implements Predicate<Cell> {
		@Override
		public boolean test(Cell cell) {
			return cell.isDead();
		}
	}

	public static class SpawnPlantInClustersFn implements SerializableFunction<Float, Vector2> {
		private float clusterR;
		private Environment environment;
		private Vector2[] centres;

		public SpawnPlantInClustersFn() {}

		public SpawnPlantInClustersFn(Environment environment, Vector2[] centres, float clusterR) {
			this.environment = environment;
			this.centres = centres;
			this.clusterR = clusterR;
		}

		@Override
		public Vector2 apply(Float r) {
			return environment.randomPosition(r, centres, clusterR);
		}
	}

	public static class SpawnProtozoaInClustersFn implements SerializableFunction<Float, Vector2> {
		private float clusterR;
		private Environment environment;
		private Vector2[] centres;

		public SpawnProtozoaInClustersFn() {}

		public SpawnProtozoaInClustersFn(Environment environment, Vector2[] centres, float clusterR) {
			this.environment = environment;
			this.centres = centres;
			this.clusterR = clusterR;
		}

		@Override
		public Vector2 apply(Float r) {
			return environment.randomPosition(r, centres, 0.8f * clusterR);
		}
	}

	public static class SpawnInRandomPositionFn implements SerializableFunction<Float, Vector2> {

		private Environment env;

		public SpawnInRandomPositionFn() {}

		public SpawnInRandomPositionFn(Environment env) {
			this.env = env;
		}

		@Override
		public Vector2 apply(Float entityRadius) {
			return env.randomPosition(entityRadius);
		}
	}

	public static class CellStatisticsFn implements SerializableFunction<Cell, Statistics> {
		private boolean allStats;

		public CellStatisticsFn() {}

		public CellStatisticsFn(boolean allStats) {
			this.allStats = allStats;
		}

		@Override
		public Statistics apply(Cell p) {
			if (allStats)
				return ((Protozoan) p).getAllStats();
			return p.getStats();
		}
	}
}
