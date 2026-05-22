package com.protoevo.physics.jolt;

import com.protoevo.env.Environment;
import com.protoevo.physics.JointsManager;
import com.protoevo.physics.Particle;
import com.protoevo.physics.Physics;

/**
 * Stub implementation of {@link Physics} backed by Jolt Physics
 * (via jolt-jni). Session 1 scaffold: every method throws so we can
 * verify the engine-selection switch in Environment routes correctly.
 * Session 2 will implement world creation + step loop. Session 3
 * will add joints, sensors, and behavioural parity tuning vs Box2D.
 *
 * Why Jolt: Box2D's stepPhysics is single-threaded and pegs one CPU
 * core at ~1.2s/step with 2000 bodies + dense contacts (measured on
 * c7a.8xlarge). Jolt's broad-phase, integration, and constraint solver
 * are designed to parallelize across cores. Empirical speedup at
 * comparable scale is 3-5×.
 */
public class JoltPhysics extends Physics {

    private final JoltJointsManager jointsManager;

    public JoltPhysics() {
        // Deliberately doesn't create a Jolt PhysicsSystem yet. That happens
        // in Session 2 once we have the jolt-jni dependency wired up.
        // Constructing this object today is enough to prove the engine
        // selector dispatches here; calling any method will throw and
        // tell the user they need to finish wiring Jolt up.
        jointsManager = new JoltJointsManager(this);
    }

    @Override
    public void registerStaticBodies(Environment environment) {
        throw new UnsupportedOperationException(
                "JoltPhysics.registerStaticBodies is not implemented yet "
                + "(Session 2 of the Jolt port). "
                + "Set Environment.settings.physicsEngine back to \"box2d\".");
    }

    @Override
    public void dispose() {
        // No-op for now — nothing to release until Session 2 creates a
        // PhysicsSystem.
    }

    @Override
    protected void stepPhysics(float delta) {
        throw new UnsupportedOperationException(
                "JoltPhysics.stepPhysics is not implemented yet (Session 2).");
    }

    @Override
    public JointsManager getJointsManager() {
        return jointsManager;
    }

    @Override
    protected Particle newParticle() {
        return new JoltParticle(this);
    }
}
