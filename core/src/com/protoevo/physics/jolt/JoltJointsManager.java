package com.protoevo.physics.jolt;

import com.badlogic.gdx.math.Vector2;
import com.protoevo.physics.Joining;
import com.protoevo.physics.JointsManager;
import com.protoevo.physics.Particle;
import com.protoevo.physics.Physics;

import java.util.Optional;

/**
 * Distance/rope constraint manager for the custom parallel circle-physics
 * engine.
 *
 * Implementation: each step, walk the joinings and apply a "soft" distance
 * constraint — for each pair (A, B), compute the current distance, the
 * target ideal length, and push the particles back toward target with an
 * impulse proportional to the error. Damped to prevent oscillation.
 *
 * Sequential (not parallelised) because each joint modifies two particles'
 * velocities/positions; parallel execution would race on shared particles
 * in chains (e.g. A-B-C where B is in two joints).
 */
public class JoltJointsManager extends JointsManager {

    public static long serialVersionUID = 1L;

    /** Spring stiffness for the distance constraint. Larger = stiffer joint.
     *  Empirically tuned to match Box2D's distance-joint default behaviour
     *  at the scales we use in this sim. */
    private static final float SPRING_K = 80f;

    /** Damping coefficient applied to the relative velocity along the
     *  joint axis. Without this, particles oscillate around the rest
     *  length indefinitely. */
    private static final float DAMP_K = 4f;

    /** Reusable scratch vectors for constraint math. JointsManager runs
     *  sequentially so single instances are safe. */
    private final Vector2 tmpAB = new Vector2();
    private final Vector2 tmpRelVel = new Vector2();

    public JoltJointsManager() { super(); }

    public JoltJointsManager(JoltPhysics physics) {
        super(physics);
    }

    @Override
    public void rebuild(Physics physics) {
        // No transient native handles to recreate — joinings are plain
        // serialisable objects in `this.joinings`. The JointsManager base
        // class restores those at deserialisation time; we don't need
        // engine-specific bookkeeping.
        this.physics = physics;
    }

    /** Apply all distance constraints. Called once per physics step,
     *  AFTER collision response, so the constraint impulse competes with
     *  collision impulses on the same particles. */
    void applyConstraints(float delta) {
        if (joinings.isEmpty()) return;
        for (Joining joining : joinings.values()) {
            applyConstraint(joining, delta);
        }
    }

    private void applyConstraint(Joining joining, float delta) {
        Optional<Particle> maybeA = joining.getParticleA();
        Optional<Particle> maybeB = joining.getParticleB();
        if (!maybeA.isPresent() || !maybeB.isPresent()) return;
        Particle a = maybeA.get();
        Particle b = maybeB.get();
        if (a.isDead() || b.isDead()) {
            jointRemovalRequests.add(joining.id);
            return;
        }
        if (!(a instanceof JoltParticle) || !(b instanceof JoltParticle))
            return; // engine mismatch; shouldn't happen

        JoltParticle ja = (JoltParticle) a;
        JoltParticle jb = (JoltParticle) b;
        if (ja.isStatic() && jb.isStatic()) return;

        Vector2 pa = a.getPos();
        Vector2 pb = b.getPos();
        tmpAB.set(pb).sub(pa);
        float dist = tmpAB.len();
        if (dist < 1e-6f) {
            // Degenerate — bodies overlap exactly. Pick an arbitrary axis.
            tmpAB.set(1f, 0f);
            dist = 1f;
        }
        float idealLen = joining.getIdealLength();

        // Different constraint behaviour by joint type.
        Joining.MetaData meta = joining.getMetaData();
        float error;
        if (meta.getType() == Joining.Type.ROPE) {
            // Rope: only resists STRETCH (dist > idealLen). Slack = no force.
            error = Math.max(0f, dist - idealLen);
            if (error <= 0f) return;
        } else {
            // Distance: pulls back toward idealLen in either direction.
            error = dist - idealLen;
        }

        // Unit vector from A to B
        float nx = tmpAB.x / dist;
        float ny = tmpAB.y / dist;

        // Relative velocity along the joint axis
        tmpRelVel.set(b.getVel()).sub(a.getVel());
        float relVelAlongAxis = tmpRelVel.x * nx + tmpRelVel.y * ny;

        // Spring + damper force (per Hooke + viscous damping)
        float force = SPRING_K * error + DAMP_K * relVelAlongAxis;

        // Distribute by inverse-mass so heavier bodies move less.
        float ma = ja.isStatic() ? Float.POSITIVE_INFINITY : Math.max(1e-9f, a.getMass());
        float mb = jb.isStatic() ? Float.POSITIVE_INFINITY : Math.max(1e-9f, b.getMass());
        float invMassSum = (Float.isInfinite(ma) ? 0 : 1f / ma)
                         + (Float.isInfinite(mb) ? 0 : 1f / mb);
        if (invMassSum <= 0f) return;

        float dv = force * delta / invMassSum;
        // Apply opposite-direction velocity changes.
        if (!ja.isStatic()) {
            a.getVel().add(nx * dv / ma, ny * dv / ma);
        }
        if (!jb.isStatic()) {
            b.getVel().add(-nx * dv / mb, -ny * dv / mb);
        }
    }

    @Override
    public void flushJoints() {
        // Activate any joints that have been queued during the previous tick.
        if (!jointsToAdd.isEmpty()) {
            for (Joining j : jointsToAdd) {
                joinings.put(j.id, j);
                Optional<Particle> a = j.getParticleA();
                Optional<Particle> b = j.getParticleB();
                a.ifPresent(p -> p.getJoiningIds().put(j.particleBId, j.id));
                b.ifPresent(p -> p.getJoiningIds().put(j.particleAId, j.id));
            }
            jointsToAdd.clear();
        }

        // Process pending removals.
        if (!jointRemovalRequests.isEmpty()) {
            for (Long id : jointRemovalRequests) {
                Joining removed = joinings.remove(id);
                if (removed == null) continue;
                Optional<Particle> a = removed.getParticleA();
                Optional<Particle> b = removed.getParticleB();
                a.ifPresent(p -> p.getJoiningIds().remove(removed.particleBId));
                b.ifPresent(p -> p.getJoiningIds().remove(removed.particleAId));
            }
            jointRemovalRequests.clear();
        }
    }
}
