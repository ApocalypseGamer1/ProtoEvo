package com.protoevo.physics.jolt;

import com.protoevo.physics.JointsManager;
import com.protoevo.physics.Physics;

/**
 * Stub Jolt-backed joints manager. Session 1 scaffold. Session 3
 * implements actual constraint creation against Jolt's
 * {@code TwoBodyConstraintSettings} API.
 */
public class JoltJointsManager extends JointsManager {

    public JoltJointsManager() { super(); }

    public JoltJointsManager(JoltPhysics physics) {
        super(physics);
    }

    @Override
    public void rebuild(Physics physics) {
        // No-op until Session 2 has a Jolt PhysicsSystem worth rebuilding into.
    }

    @Override
    public void flushJoints() {
        // No-op during scaffold. Real implementation in Session 3.
    }
}
