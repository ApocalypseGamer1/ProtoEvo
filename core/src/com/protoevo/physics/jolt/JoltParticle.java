package com.protoevo.physics.jolt;

import com.badlogic.gdx.math.Vector2;
import com.protoevo.biology.CauseOfDeath;
import com.protoevo.core.Statistics;
import com.protoevo.env.Environment;
import com.protoevo.physics.Collision;
import com.protoevo.physics.Joining;
import com.protoevo.physics.Particle;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Custom-engine particle. Note: despite the "Jolt" name (kept from the
 * scaffolding session), this is NOT a binding to Jolt Physics. It's a
 * pure-Java parallel circle-physics implementation tuned for this sim:
 * - All particles are 2D circles. No polygons, no compound shapes.
 * - Forward-Euler integration with damping.
 * - Collision response is impulse-based (no constraint solver).
 * - Joints are distance constraints applied each step.
 * - Sensors are queried via spatial hash, not as separate Box2D fixtures.
 *
 * Why this works for the sim: Box2D's expensive part is its multi-iteration
 * constraint solver for stacked/contacting bodies. Cells in this sim rarely
 * stack and never form rigid contact chains — they bump, separate, and
 * move on. Simple impulse-based separation gives indistinguishable behavior
 * at a fraction of the per-step cost, AND parallelizes cleanly because
 * impulses are accumulated per-particle in a thread-safe atomic buffer
 * before being applied sequentially at the end of each step.
 */
public class JoltParticle extends Particle {

    private static final long serialVersionUID = 1L;
    private final long id = UUID.randomUUID().getMostSignificantBits();

    private Object userData;
    private final Map<Long, Long> joiningIds = new ConcurrentHashMap<>();
    private volatile boolean dead = false;
    private double radius = Environment.settings.minParticleRadius.get()
            * (1 + 2 * Math.random());
    private float interactionRadius = 0f;

    // World-space state. Vector2 isn't intrinsically thread-safe but each
    // field is only written either (a) inside the owning particle's update,
    // or (b) inside the synchronized impulse accumulation block, so we're
    // safe under the engine's phased step protocol.
    private final Vector2 pos = new Vector2();
    private final Vector2 vel = new Vector2();
    private final Vector2 pendingForce = new Vector2();
    private final Vector2 pendingImpulse = new Vector2();

    /** Impulse accumulator that COLLISION RESOLUTION writes into. The
     *  owning particle reads + clears this in the apply pass. Synchronized
     *  because pair (A,B) collisions are detected in parallel — both
     *  threads may try to write impulses to the same particle. */
    private final Vector2 pendingCollisionImpulse = new Vector2();
    private final Object collisionImpulseLock = new Object();

    private float angle = 0f;
    private float angularVel = 0f;
    private float pendingTorque = 0f;
    private float angularDamping = 5f;
    private float linearDamping;
    private float massDensity = 1f;
    private boolean isStatic = false;

    private CauseOfDeath causeOfDeath = null;

    private final Statistics stats = new Statistics();
    private final Statistics debugStats = new Statistics();
    /** Per-tick contact list. Cleared at the start of each physics step,
     *  populated by the collision detector. */
    private final Collection<Collision> contacts = new ConcurrentLinkedQueue<>();
    /** Particles in this particle's sensor (interaction) radius. */
    private final Collection<Object> interactionObjects = new ConcurrentLinkedQueue<>();

    private final Vector2[] boundingBox = new Vector2[]{new Vector2(), new Vector2()};
    private final Vector2 tmp = new Vector2();

    public JoltParticle(JoltPhysics physics) {
        super(physics);
        this.linearDamping = Environment.settings.env.fluidDragDampening.get();
    }

    public JoltParticle() {
        super(null);
    }

    // ----- Identity / user data -----

    @Override public long getId() { return id; }
    @Override public Object getUserData() { return userData; }
    @Override public void setUserData(Object u) { this.userData = u; }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getUserData(Class<T> type) {
        if (userData == null) throw new NullPointerException("User data is null");
        if (!type.isInstance(userData))
            throw new ClassCastException("User data is not " + type.getName());
        return (T) userData;
    }

    // ----- Geometry -----

    @Override public float getRadius() { return (float) radius; }
    @Override public void setRadius(double r) { this.radius = r; }
    @Override public Vector2 getPos() { return pos; }
    @Override public void setPos(Vector2 newPos) { this.pos.set(newPos); }
    @Override public float getAngle() { return angle; }
    @Override public void setAngle(float a) { this.angle = a; }

    // ----- Mass -----

    @Override
    public double getMassIfRadius(double r) {
        return Math.PI * r * r * massDensity;
    }
    @Override public float getMass() { return (float) getMassIfRadius(radius); }
    @Override public float getMassDensity() { return massDensity; }
    public void setMassDensity(float d) { this.massDensity = d; }

    // ----- Velocity / forces / impulses -----

    @Override public Vector2 getVel() { return vel; }
    @Override public void setVel(float vx, float vy) { this.vel.set(vx, vy); }
    @Override public void setVel(Vector2 v) { this.vel.set(v); }
    @Override public void setAngularVel(float angularVel) { this.angularVel = angularVel; }

    @Override public void applyForce(Vector2 f) { this.pendingForce.add(f); }
    @Override public Vector2 getForce() { return pendingForce; }
    @Override public void applyImpulse(Vector2 i) { this.pendingImpulse.add(i); }
    @Override public Vector2 getImpulse() { return pendingImpulse; }
    @Override public void applyTorque(float t) { this.pendingTorque += t; }
    @Override public float getTorque() { return pendingTorque; }

    @Override
    public void setAngularDamping(float damping) {
        this.angularDamping = damping;
    }

    public float getAngularDamping() { return angularDamping; }
    public float getLinearDamping() { return linearDamping; }

    // ----- Engine-internal accumulators -----

    /** Called by the collision resolver to push a separating impulse onto
     *  this particle. Thread-safe — multiple threads may detect collisions
     *  involving this particle in the same step. */
    void addCollisionImpulse(float ix, float iy) {
        synchronized (collisionImpulseLock) {
            pendingCollisionImpulse.add(ix, iy);
        }
    }

    /** Read and zero the collision-impulse accumulator. Called once per
     *  step on the main thread. */
    void drainCollisionImpulseInto(Vector2 out) {
        synchronized (collisionImpulseLock) {
            out.set(pendingCollisionImpulse);
            pendingCollisionImpulse.setZero();
        }
    }

    /** Engine-internal: clears the pending force/impulse vectors after
     *  they've been integrated into velocity. */
    void clearAccumulators() {
        pendingForce.setZero();
        pendingImpulse.setZero();
        pendingTorque = 0f;
    }

    public boolean isStatic() { return isStatic; }
    public void setStatic(boolean s) { this.isStatic = s; }

    // ----- Joinings (delegated to physics' joints manager) -----

    @Override public Map<Long, Long> getJoiningIds() { return joiningIds; }

    @Override
    public Optional<Joining> getJoining(long joiningId) {
        return physics == null ? Optional.empty()
                : physics.getJointsManager().getJoining(joiningId);
    }

    @Override
    public void requestJointRemoval(Joining joining) {
        if (physics != null)
            ((JoltPhysics) physics).requestJointRemoval(joining);
    }

    // ----- Contacts / interactions (populated by the engine) -----

    @Override public Collection<Collision> getContacts() { return contacts; }
    @Override public Collection<Object> getInteractionQueue() { return interactionObjects; }

    Collection<Collision> getContactsInternal() { return contacts; }
    Collection<Object> getInteractionQueueInternal() { return interactionObjects; }

    /** Cleared by the engine at the start of each step. */
    void clearContacts() {
        contacts.clear();
        interactionObjects.clear();
    }

    @Override
    public void setRangedInteractionRadius(float radius) {
        this.interactionRadius = radius;
    }

    public float getInteractionRadius() { return interactionRadius; }

    @Override
    public void setCanInteractAtRange() {
        super.setCanInteractAtRange();
        // The engine reads `interactionRadius` directly when populating
        // the interaction queue — no separate fixture needed.
    }

    // ----- Lifecycle -----

    @Override public boolean isDead() { return dead; }

    @Override
    public void kill(CauseOfDeath causeOfDeath) {
        this.dead = true;
        if (this.causeOfDeath == null)
            this.causeOfDeath = causeOfDeath;
    }

    @Override public CauseOfDeath getCauseOfDeath() { return causeOfDeath; }

    @Override
    public void dispose() {
        // No native handle — nothing to release. Engine drops us from its
        // map on next step.
    }

    // ----- Update hooks (the engine calls these in phases) -----

    @Override
    public void update(float delta) {
        // No-op: integration happens in JoltPhysics.stepPhysics in batched
        // parallel phases. Box2DParticle.update did per-particle work that
        // we now do globally.
    }

    @Override
    public void physicsUpdate() {
        // Same — engine handles batched updates.
    }

    @Override
    public void rebuildTransientFields() {
        // No transient native handles to rebuild — everything is plain Java
        // fields. This was the awkward part of Box2DParticle's serialisation;
        // the custom engine sidesteps it.
    }

    // ----- Stats -----

    @Override
    public Statistics getStats() {
        stats.clear();
        stats.putDistance("Pos X", pos.x);
        stats.putDistance("Pos Y", pos.y);
        stats.putSpeed("Speed", vel.len());
        stats.putDistance("Radius", (float) radius);
        return stats;
    }

    @Override
    public Statistics getDebugStats() {
        debugStats.clear();
        debugStats.putDistance("Pending Force", pendingForce.len());
        debugStats.putDistance("Pending Impulse", pendingImpulse.len());
        debugStats.putCount("Contacts This Step", contacts.size());
        debugStats.putCount("Interactions", interactionObjects.size());
        debugStats.putCount("Joined To", joiningIds.size());
        return debugStats;
    }

    // ----- Shape interface -----

    @Override
    public boolean pointInside(Vector2 p) {
        float r = (float) radius;
        return p.dst2(pos) <= r * r;
    }

    @Override
    public boolean rayCollisions(Vector2[] ray, Intersection[] intersections) {
        // Solve |ray[0] + t*(ray[1]-ray[0]) - pos|^2 = r^2 for t in [0,1].
        tmp.set(ray[1]).sub(ray[0]);              // direction
        Vector2 oc = new Vector2(ray[0]).sub(pos); // origin minus center
        float a = tmp.dot(tmp);
        float b = 2 * tmp.dot(oc);
        float c = oc.dot(oc) - (float) (radius * radius);
        float disc = b * b - 4 * a * c;
        if (disc < 0 || a < 1e-12f) return false;
        float sqrt = (float) Math.sqrt(disc);
        float t0 = (-b - sqrt) / (2 * a);
        float t1 = (-b + sqrt) / (2 * a);
        boolean hit = false;
        if (t0 >= 0 && t0 <= 1 && intersections.length >= 1) {
            intersections[0].point.set(ray[0]).lerp(ray[1], t0);
            intersections[0].didCollide = true;
            hit = true;
        }
        if (t1 >= 0 && t1 <= 1 && intersections.length >= 2) {
            intersections[1].point.set(ray[0]).lerp(ray[1], t1);
            intersections[1].didCollide = true;
            hit = true;
        }
        return hit;
    }

    @Override
    public Vector2[] getBoundingBox() {
        float r = (float) radius;
        boundingBox[0].set(pos.x - r, pos.y - r);
        boundingBox[1].set(pos.x + r, pos.y + r);
        return boundingBox;
    }
}
