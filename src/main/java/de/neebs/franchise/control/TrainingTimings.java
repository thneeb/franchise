package de.neebs.franchise.control;

record TrainingTimings(long trainingNanos, long modelSaveNanos) {

    static final TrainingTimings ZERO = new TrainingTimings(0L, 0L);
}
