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
 * Stub implementation of {@link Particle} backed by a Jolt Body.
 * Session 1 scaffold; Session 2 implements body creation and the
 * pos/vel/force methods against Jolt's API. See {@link JoltPhysics}
 * for the broader plan.
 *
 * The fields here mirror Box2DParticle's serialisable state so saves
 * created under the Box2D engine can be re-loaded (we'll provide a
 * conversion path in Session 3). The transient `body` will hold a
 * jolt-jni `Body` once Session 2 wires it up.
 */
public class JoltParticle extends Particle {

    private static final long serialVersionUID = 1L;
    private final long id = UUID.randomUUID().getMostSignificantBits();

    private Object userData;
    private final Map<Long, Long> joiningIds = new ConcurrentHashMap<>();
    private boolean dead = false;
    private double radius = Environment.settings.minParticleRadius.get();
    private final Vector2 pos = new Vector2();
    private final Vector2 vel = new Vector2();
    private final Vector2 impulse = new Vector2();
    private final Vector2 force = new Vector2();
    private float angle = 0f;
    private float torque = 0f;
    private final Statistics stats = new Statistics();
    private final Statistics debugStats = new Statistics();
    private final Collection<Collision> contacts = new ConcurrentLinkedQueue<>();
    private final Collection<Object> interactionObjects = new ConcurrentLinkedQueue<>();
    private CauseOfDeath causeOfDeath = null;

    public JoltParticle(JoltPhysics physics) {
        super(physics);
    }

    public JoltParticle() {
        super(null);
    }

    private UnsupportedOperationException stub(String method) {
        return new UnsupportedOperationException(
                "JoltParticle." + method + " is not implemented yet "
                + "(Session 2 of the Jolt port). "
                + "Set Environment.settings.physicsEngine back to \"box2d\".");
    }

    @Override public boolean isDead() { return dead; }
    @Override public void update(float delta) { throw stub("update"); }
    @Override public void physicsUpdate() { throw stub("physicsUpdate"); }
    @Override public float getRadius() { return (float) radius; }
    @Override public void setRadius(double r) { this.radius = r; }
    @Override public double getMassIfRadius(double r) { return Math.PI * r * r * getMassDensity(); }
    @Override public float getMass() { return (float) getMassIfRadius(radius); }
    @Override public float getMassDensity() { return Environment.settings.cell.basicParticleMassDensity.get(); }
    @Override public Vector2 getPos() { return pos; }
    @Override public Vector2 getVel() { return vel; }
    @Override public float getAngle() { return angle; }
    @Override public long getId() { return id; }
    @Override public void setPos(Vector2 newPos) { this.pos.set(newPos); }
    @Override public void setAngle(float a) { this.angle = a; }
    @Override public void applyImpulse(Vector2 i) { this.impulse.add(i); }
    @Override public Vector2 getImpulse() { return impulse; }
    @Override public void applyForce(Vector2 f) { this.force.add(f); }
    @Override public Vector2 getForce() { return force; }
    @Override public void applyTorque(float t) { this.torque += t; }
    @Override public float getTorque() { return torque; }
    @Override public Map<Long, Long> getJoiningIds() { return joiningIds; }
    @Override public Optional<Joining> getJoining(long joiningId) { return Optional.empty(); }
    @Override public void requestJointRemoval(Joining joining) { throw stub("requestJointRemoval"); }
    @Override public Collection<Collision> getContacts() { return contacts; }
    @Override public Collection<Object> getInteractionQueue() { return interactionObjects; }
    @Override public Object getUserData() { return userData; }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getUserData(Class<T> type) {
        if (userData == null) throw new NullPointerException("User data is null");
        if (!type.isInstance(userData))
            throw new ClassCastException("User data is not " + type.getName());
        return (T) userData;
    }

    @Override public void setUserData(Object u) { this.userData = u; }
    @Override public Statistics getStats() { return stats; }
    @Override public Statistics getDebugStats() { return debugStats; }

    @Override
    public void kill(CauseOfDeath causeOfDeath) {
        this.dead = true;
        this.causeOfDeath = causeOfDeath;
    }

    @Override public CauseOfDeath getCauseOfDeath() { return causeOfDeath; }
    @Override public void dispose() { /* no native resources yet */ }
    @Override public void setVel(float vx, float vy) { this.vel.set(vx, vy); }
    @Override public void setVel(Vector2 v) { this.vel.set(v); }
    @Override public void setAngularVel(float angularVel) { throw stub("setAngularVel"); }
    @Override public void setRangedInteractionRadius(float r) { /* sensor stub */ }
    @Override public void setAngularDamping(float damping) { /* damping stub */ }
    @Override public void rebuildTransientFields() { /* nothing transient yet */ }

    // --- Shape interface ---
    private final Vector2[] boundingBox = new Vector2[]{new Vector2(), new Vector2()};

    @Override
    public boolean pointInside(Vector2 p) {
        float r = (float) radius;
        return p.dst2(pos) <= r * r;
    }

    @Override
    public boolean rayCollisions(Vector2[] ray, Intersection[] intersections) {
        return false; // not used until Session 2
    }

    @Override
    public Vector2[] getBoundingBox() {
        float r = (float) radius;
        boundingBox[0].set(pos.x - r, pos.y - r);
        boundingBox[1].set(pos.x + r, pos.y + r);
        return boundingBox;
    }
}
