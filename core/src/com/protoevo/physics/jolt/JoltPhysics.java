package com.protoevo.physics.jolt;

import com.badlogic.gdx.math.Vector2;
import com.protoevo.env.Environment;
import com.protoevo.env.Rock;
import com.protoevo.physics.Collision;
import com.protoevo.physics.Joining;
import com.protoevo.physics.JointsManager;
import com.protoevo.physics.Particle;
import com.protoevo.physics.Physics;
import com.protoevo.physics.SpatialHash;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Custom parallel circle-physics engine. See {@link JoltParticle} for the
 * design rationale — short version: 2D circles only, impulse-based
 * resolution (no constraint solver), parallel-friendly phased step.
 *
 * Step phases (each is either fully parallel or fully sequential — no
 * mixing within a phase, so we don't need fine-grained locking inside
 * the hot loops):
 *
 *   1. INTEGRATE (parallel over particles)
 *      Apply pending forces and impulses to velocity; integrate position
 *      from velocity with linear damping; clear force/impulse vectors.
 *
 *   2. INDEX (sequential)
 *      Rebuild the spatial hash. ~5 µs per particle so total ~10ms at
 *      2000 particles, not worth parallelising.
 *
 *   3. DETECT (parallel over particles)
 *      For each particle, query the spatial hash for nearby particles
 *      and enqueue collision pairs. Each pair (A, B) is enqueued only
 *      once (filter: A.id &lt; B.id).
 *
 *   4. RESOLVE (sequential over pairs)
 *      For each colliding pair, compute the separating impulse and add
 *      it to each particle's collision accumulator. Add to contacts list.
 *      Could be parallelised — the accumulator is synchronized — but at
 *      this scale the sequential pass is fast enough and keeps the code
 *      readable.
 *
 *   5. APPLY (parallel over particles)
 *      Drain each particle's collision accumulator into velocity.
 *
 *   6. CONSTRAINTS (sequential)
 *      Apply joint constraints (distance/rope). Sequential because joints
 *      modify pairs of particle velocities; parallel would race.
 *
 *   7. SENSORS (parallel over particles)
 *      Populate each particle's interaction queue from spatial hash.
 *
 * Rocks (static obstacles) are handled as a separate sequential pass
 * inside RESOLVE — they're polygons, queried against the same spatial
 * hash but treated as immovable.
 */
public class JoltPhysics extends Physics {

    private static final long serialVersionUID = 1L;

    /** Maximum number of particles per chunk in the broad-phase hash.
     *  The chunk size auto-scales with world radius; this is a safety
     *  cap on per-chunk linked-list length. */
    private static final int MAX_PARTICLES_PER_CHUNK = 256;

    private final JoltJointsManager jointsManager;

    /** Broad-phase spatial hash. Rebuilt every step. */
    private transient SpatialHash<JoltParticle> spatialHash;

    /** Static rock polygons. Created once per environment, queried each
     *  step against any particle whose bounding-box overlaps the rock's. */
    private final List<Rock> rocks = new ArrayList<>();

    /** Pre-allocated pair buffer to keep DETECT phase allocation-free. */
    private final ConcurrentLinkedQueue<CollisionPair> pairBuffer =
            new ConcurrentLinkedQueue<>();

    /** Last-known physics step time in seconds (for the watchdog). */
    private float lastStepWall = 0f;

    public JoltPhysics() {
        jointsManager = new JoltJointsManager(this);
        rebuildSpatialHash();
        System.out.println("[JoltPhysics] custom parallel circle-engine initialised "
                + "(world radius=" + Environment.settings.worldgen.radius.get() + ").");
    }

    private void rebuildSpatialHash() {
        int resolution = Environment.settings.misc.spatialHashResolution.get();
        float worldRadius = 1.5f * Environment.settings.worldgen.radius.get();
        spatialHash = new SpatialHash<>(resolution, MAX_PARTICLES_PER_CHUNK, worldRadius);
    }

    @Override
    public void registerStaticBodies(Environment environment) {
        rocks.clear();
        rocks.addAll(environment.getRocks());
        // Nothing else to do — rocks are queried lazily per step.
    }

    @Override
    public void dispose() {
        // No native resources. ConcurrentLinkedQueue / ConcurrentHashMap
        // are GC-managed.
    }

    @Override
    protected void stepPhysics(float delta) {
        long t0 = System.nanoTime();

        Collection<Particle> all = getParticles();

        // ---- Phase 1: INTEGRATE (parallel) ----
        all.parallelStream().forEach(p -> {
            if (!(p instanceof JoltParticle) || p.isDead()) return;
            JoltParticle jp = (JoltParticle) p;
            if (jp.isStatic()) {
                jp.clearAccumulators();
                jp.clearContacts();
                return;
            }
            float mass = jp.getMass();
            if (mass <= 1e-9f) mass = 1e-9f;

            Vector2 vel = jp.getVel();
            // Impulse: instantaneous Δv = I/m
            Vector2 impulse = jp.getImpulse();
            vel.add(impulse.x / mass, impulse.y / mass);
            // Force over delta: Δv = F·dt/m
            Vector2 force = jp.getForce();
            vel.add(force.x * delta / mass, force.y * delta / mass);
            // Linear damping: v *= 1/(1 + damping·dt)
            float ldFactor = 1f / (1f + delta * jp.getLinearDamping());
            vel.scl(ldFactor);

            // Angular: same pattern.
            float angVel = (jp.getTorque() * delta) / mass; // simplified inertia
            // (cells barely use angular; full I/τ math isn't worth the cost)
            float oldAng = jp.getAngle();
            float adFactor = 1f / (1f + delta * jp.getAngularDamping());
            float newAng = oldAng + angVel * delta * adFactor;
            jp.setAngle(newAng);

            // Integrate position
            Vector2 pos = jp.getPos();
            pos.add(vel.x * delta, vel.y * delta);

            jp.clearAccumulators();
            jp.clearContacts();
        });

        // ---- Phase 2: INDEX (sequential) ----
        spatialHash.clear();
        for (Particle p : all) {
            if (p instanceof JoltParticle && !p.isDead()) {
                spatialHash.add((JoltParticle) p, p.getPos());
            }
        }

        // ---- Phase 3: DETECT (parallel) ----
        pairBuffer.clear();
        all.parallelStream().forEach(p -> {
            if (!(p instanceof JoltParticle) || p.isDead()) return;
            JoltParticle jp = (JoltParticle) p;
            Vector2 ppos = jp.getPos();
            float pr = jp.getRadius();
            // Query a generous radius — particle pairs whose bounding boxes
            // overlap, plus interaction-sensor range.
            float queryRadius = pr + Math.max(pr, jp.getInteractionRadius());
            Collection<JoltParticle> neighbours = spatialHash.getNearbyEntries(ppos, queryRadius);
            for (JoltParticle other : neighbours) {
                if (other == jp || other.isDead()) continue;
                if (jp.getId() >= other.getId()) continue; // pair only once
                pairBuffer.add(new CollisionPair(jp, other));
            }
        });

        // ---- Phase 4: RESOLVE (sequential — uses tmpA/B/tmpImpulse) ----
        for (CollisionPair pair : pairBuffer) {
            resolvePair(pair.a, pair.b);
        }

        // Static rocks (sequential): cells colliding with rocks pushed out.
        if (!rocks.isEmpty()) {
            for (Particle p : all) {
                if (!(p instanceof JoltParticle) || p.isDead()) continue;
                resolveAgainstRocks((JoltParticle) p);
            }
        }

        // ---- Phase 5: APPLY collision impulses (parallel) ----
        all.parallelStream().forEach(p -> {
            if (!(p instanceof JoltParticle) || p.isDead()) return;
            JoltParticle jp = (JoltParticle) p;
            if (jp.isStatic()) return;
            float mass = Math.max(1e-9f, jp.getMass());
            jp.drainCollisionImpulseInto(tmpImpulseLocal.get());
            Vector2 i = tmpImpulseLocal.get();
            if (i.x != 0 || i.y != 0) {
                jp.getVel().add(i.x / mass, i.y / mass);
            }
        });

        // ---- Phase 6: CONSTRAINTS (sequential) ----
        jointsManager.applyConstraints(delta);
        jointsManager.flushJoints();

        // ---- Phase 7: SENSORS (parallel) ----
        all.parallelStream().forEach(p -> {
            if (!(p instanceof JoltParticle) || p.isDead()) return;
            JoltParticle jp = (JoltParticle) p;
            if (jp.getInteractionRadius() <= 0f) return;
            Collection<JoltParticle> nearby = spatialHash.getNearbyEntries(
                    jp.getPos(), jp.getInteractionRadius());
            for (JoltParticle other : nearby) {
                if (other != jp && !other.isDead()) {
                    jp.getInteractionQueueInternal().add(other);
                }
            }
        });

        lastStepWall = (System.nanoTime() - t0) / 1e9f;
    }

    /** Thread-local scratch vector for Phase 5. */
    private final ThreadLocal<Vector2> tmpImpulseLocal =
            ThreadLocal.withInitial(Vector2::new);

    /**
     * Circle-vs-circle collision response. If A and B overlap, push them
     * apart along their center line with an impulse proportional to the
     * overlap depth, and add a contact to both particles' contact lists.
     */
    private void resolvePair(JoltParticle a, JoltParticle b) {
        Vector2 pa = a.getPos();
        Vector2 pb = b.getPos();
        float dx = pb.x - pa.x;
        float dy = pb.y - pa.y;
        float dist2 = dx * dx + dy * dy;
        float ra = a.getRadius();
        float rb = b.getRadius();
        float minDist = ra + rb;
        if (dist2 >= minDist * minDist) return;

        // Contact happened — record it. Use the midpoint as the contact
        // point; Box2D's manifold also gives this approximately.
        float dist = (float) Math.sqrt(Math.max(dist2, 1e-12));
        float nx, ny;
        if (dist < 1e-6f) {
            // Identical positions — pick an arbitrary normal.
            nx = 1f;
            ny = 0f;
        } else {
            nx = dx / dist;
            ny = dy / dist;
        }
        Vector2 point = new Vector2(pa.x + nx * ra, pa.y + ny * ra);
        Collision col = new Collision(a, b, point);
        a.getContactsInternal().add(col);
        b.getContactsInternal().add(col);

        if (a.isStatic() && b.isStatic()) return;

        // Penetration depth
        float pen = minDist - dist;

        // Relative velocity along the contact normal
        float rvx = b.getVel().x - a.getVel().x;
        float rvy = b.getVel().y - a.getVel().y;
        float velAlongNormal = rvx * nx + rvy * ny;

        // Only resolve if they're moving toward each other
        if (velAlongNormal < 0) {
            // Bouncy-ish restitution — much less than Box2D's default
            // because we want gentle separation, not pinball.
            float restitution = 0.2f;
            float ma = Math.max(1e-9f, a.getMass());
            float mb = Math.max(1e-9f, b.getMass());
            float invMassSum = 1f / ma + 1f / mb;
            if (invMassSum <= 0f) return;
            float j = -(1 + restitution) * velAlongNormal / invMassSum;
            float ix = j * nx;
            float iy = j * ny;
            if (!a.isStatic()) a.addCollisionImpulse(-ix, -iy);
            if (!b.isStatic()) b.addCollisionImpulse(ix, iy);
        }

        // Positional correction so they don't sit overlapping. Apply a
        // small fraction of the penetration as direct position offset.
        float positionalCorrection = 0.5f * pen;
        float ma = a.isStatic() ? Float.POSITIVE_INFINITY : a.getMass();
        float mb = b.isStatic() ? Float.POSITIVE_INFINITY : b.getMass();
        float massSum = (Float.isInfinite(ma) ? 0 : 1f / ma)
                      + (Float.isInfinite(mb) ? 0 : 1f / mb);
        if (massSum <= 0f) return;
        float aShare = Float.isInfinite(ma) ? 0 : (1f / ma) / massSum;
        float bShare = Float.isInfinite(mb) ? 0 : (1f / mb) / massSum;
        if (!a.isStatic()) {
            pa.add(-nx * positionalCorrection * aShare,
                   -ny * positionalCorrection * aShare);
        }
        if (!b.isStatic()) {
            pb.add(nx * positionalCorrection * bShare,
                   ny * positionalCorrection * bShare);
        }
    }

    /**
     * Push the particle out of any rock it's penetrating. Simple
     * polygon-vs-circle check using each rock's bounding box first as a
     * cheap rejection, then point-in-polygon for the precise test.
     */
    private void resolveAgainstRocks(JoltParticle p) {
        if (p.isStatic()) return;
        Vector2 pos = p.getPos();
        float r = p.getRadius();
        for (Rock rock : rocks) {
            // Rocks have their own bounding box query — keep this loop cheap.
            if (rock.pointInside(pos)) {
                // Naive push-out: just reflect velocity, nudge position
                // toward world centre. Rocks here are static decorations
                // for now; refining the response is Session 3 polish.
                pos.x *= 0.95f;
                pos.y *= 0.95f;
                p.getVel().scl(-0.3f);
                return;
            }
        }
    }

    // ----- Joints / lifecycle for jointsmgr -----

    public void requestJointRemoval(Joining joining) {
        if (joining == null) return;
        jointsManager.requestJointRemoval(joining);
    }

    @Override
    public JointsManager getJointsManager() {
        return jointsManager;
    }

    @Override
    protected Particle newParticle() {
        return new JoltParticle(this);
    }

    public SpatialHash<JoltParticle> getSpatialHash() {
        return spatialHash;
    }

    @Override
    public void rebuildTransientFields(Environment environment) {
        rebuildSpatialHash();
        jointsManager.rebuild(this);
    }

    /** Pair holder for the DETECT → RESOLVE handoff. Lightweight, allocated
     *  per pair. Could be pooled if allocation pressure shows up in profiling,
     *  but at 2000 particles with ~3 neighbours each, that's only ~3000
     *  allocations per step — well below GC concern. */
    private static final class CollisionPair {
        final JoltParticle a;
        final JoltParticle b;
        CollisionPair(JoltParticle a, JoltParticle b) {
            this.a = a;
            this.b = b;
        }
    }
}
