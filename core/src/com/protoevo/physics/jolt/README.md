# Custom parallel circle-physics engine

This package is named `jolt` for historical reasons (the original plan was
to bind Jolt Physics via `jolt-jni`), but the implementation is a pure-Java
parallel circle-physics engine purpose-built for this sim. No native
dependencies.

## Why custom, not Jolt

- All particles in this sim are 2D **circles**. No polygons, no compound
  shapes, no boxes. Box2D's expensive constraint solver does work we don't
  need.
- Cells in this sim rarely stack and never form rigid contact chains.
  Box2D's multi-iteration position/velocity solver is overkill — simple
  impulse-based separation gives indistinguishable visible behaviour at a
  fraction of the per-step cost.
- Pure Java means: no native library compatibility issues across
  Windows/Linux/Mac, no jolt-jni version drift, no platform-specific
  builds in `build.gradle`.

## Architecture

Three classes implementing the abstract `Physics` / `Particle` /
`JointsManager` contracts:

- **`JoltPhysics`** — the world. Owns the spatial hash for broad-phase.
  Runs a 7-phase step (see class javadoc).
- **`JoltParticle`** — per-particle state. Plain Java fields, no native
  handles. Has a synchronized impulse accumulator so collision response
  can write to it from any worker thread in the parallel detect phase.
- **`JoltJointsManager`** — distance/rope constraints via spring+damper,
  applied sequentially each step (parallel would race on chain joints).

## Selecting the engine

In `MiscSettings`, the `physicsEngine` parameter controls which backend
`Environment` instantiates:

```java
public final Parameter<String> physicsEngine = ...  // "box2d" or "jolt"
```

`"box2d"` is still the default for now. Flip to `"jolt"` to use this
implementation. Both engines are alive in the codebase so you can A/B
test or fall back if Jolt has regressions.

## Known parity gaps vs Box2D

- **Angular dynamics**: cells DO rotate but the angular update is a
  simplified `τ·dt/m` rather than a proper moment-of-inertia integration.
  Cells barely use rotation in this sim, but if you add features that
  depend on accurate angular momentum, revisit `JoltPhysics.stepPhysics`
  Phase 1.
- **Restitution**: hardcoded to 0.2 in `resolvePair`. Box2D's default
  was the per-fixture restitution from `Box2DParticle.createBody`.
  Wire to a setting if you want fine-grained tuning.
- **Static rocks**: the response is naive (push position toward origin).
  Box2D had proper polygon collision via its native code. If rocks
  become more than decoration, write a proper polygon-vs-circle SAT
  response in `resolveAgainstRocks`.

## Performance characteristics

Designed for the c7a-class CPU instances. Phases that scale linearly
with particle count (INTEGRATE, DETECT, APPLY, SENSORS) use
`parallelStream` to fan out across cores. The sequential phases (INDEX,
RESOLVE, CONSTRAINTS) are O(N) but lightweight — typical cost is dominated
by the parallel phases at 2000-particle scale.

Expected vs Box2D at 2000 particles on 32-core Zen 4:
- Box2D `stepPhysics` (measured): ~1.2 sec
- JoltPhysics `stepPhysics` (predicted): 50-200 ms
- Speedup: ~6-20×
