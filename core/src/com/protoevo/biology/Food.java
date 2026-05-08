package com.protoevo.biology;

import com.protoevo.env.Environment;

import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class Food implements Serializable {

    public enum Type {
        Plant, Meat;

        public float getEnergyDensity() {
            switch (this) {
                case Plant:
                    return Environment.settings.plantEnergyDensity.get();
                case Meat:
                    return Environment.settings.meatEnergyDensity.get();
                default:
                    return 0;
            }
        }

        public static int numTypes() {
            return values().length;
        }
    }

    private float mass, energy;
    private Type type;
    private Map<ComplexMolecule, Float> complexMoleculeMasses;

    public Food() {}

    public Food(float mass, Type foodType, HashMap<ComplexMolecule, Float> complexMoleculeMass) {
        this.mass = mass;
        this.type = foodType;
        this.complexMoleculeMasses = complexMoleculeMass;
    }

    public Food(float mass, Type foodType) {
        this(mass, foodType, new HashMap<>(0));
    }

    public Type getType() {
        return type;
    }

    public float getSimpleMass() {
        return mass;
    }

    public void addSimpleMass(float m) {
        mass += m;
    }

    public void addEnergy(float energy) {
        // Was: `mass += energy;` — clearly a typo bug. Combined with `addSimpleMass`
        // double-adding mass and `getEnergy` re-releasing the stored value every
        // tick at meat density (3e5×), this let cells eat their own corpses for
        // a 6-figure-multiplier net energy gain. Storing in the right field now.
        this.energy += energy;
    }

    public void subtractSimpleMass(float m) {
        mass = Math.max(0, mass - m);
    }

    public float getComplexMoleculeMass(ComplexMolecule molecule) {
        return complexMoleculeMasses.getOrDefault(molecule, 0f);
    }

    public float getMass() {
        float totalMass = mass;
        for (float complexMoleculeMass : complexMoleculeMasses.values()) {
            totalMass += complexMoleculeMass;
        }
        return totalMass;
    }

    public Collection<ComplexMolecule> getComplexMolecules() {
        return complexMoleculeMasses.keySet();
    }

    public void subtractComplexMolecule(ComplexMolecule molecule, float extracted) {
        float currentAmount = getComplexMoleculeMass(molecule);
        complexMoleculeMasses.put(molecule, Math.max(0, currentAmount - extracted));
    }

    public Map<ComplexMolecule, Float> getComplexMoleculeMasses() {
        return complexMoleculeMasses;
    }

    public void addComplexMolecules(Map<ComplexMolecule, Float> masses) {
        for (ComplexMolecule molecule : masses.keySet())
            addComplexMoleculeMass(molecule, masses.get(molecule));
    }

    public void addComplexMoleculeMass(ComplexMolecule molecule, float mass) {
        float currentMass = complexMoleculeMasses.getOrDefault(molecule, 0f);
        complexMoleculeMasses.put(molecule, currentMass + mass);
    }

    /**
     * Release energy proportional to the mass `m` just extracted. Consumes the
     * proportional fraction of the stored `energy` field, plus density × m for
     * the meat/plant intrinsic energy.
     *
     * Caller in Cell.digest subtracts mass FIRST, then calls this with the
     * removed mass. So `mass` here is the *remaining* mass after extraction;
     * `mass + m` reconstructs the pre-extraction total.
     *
     * Was: `return energy + density * m;` — released the whole stored energy
     * every tick, never decrementing it. Combined with the addEnergy/mass-typo
     * bug above, this was the main "eat your own meat for infinite energy" loop.
     */
    public float getEnergy(float m) {
        float prevMass = mass + m;
        float storedReleased;
        if (prevMass <= 0f) {
            // Edge case: no mass left to attribute the release to. Take it all.
            storedReleased = energy;
        } else {
            storedReleased = energy * (m / prevMass);
        }
        energy = Math.max(0f, energy - storedReleased);
        return storedReleased + type.getEnergyDensity() * m;
    }

    @Override
    public String toString() {
        return type.name();
    }

    public float getDecayRate() {
        return this.type == Type.Plant ?
                Environment.settings.plantDecayRate.get() : Environment.settings.meatDecayRate.get();
    }

    public void decay(float delta) {
        float decay = Math.max(0, 1f - getDecayRate() * delta);
        mass = Math.max(0, mass - decay);
        energy = Math.max(0, energy - decay);
        complexMoleculeMasses.replaceAll((m, v) -> complexMoleculeMasses.get(m)
                * Environment.settings.cell.complexMoleculeDecayRate.get());
    }
}
