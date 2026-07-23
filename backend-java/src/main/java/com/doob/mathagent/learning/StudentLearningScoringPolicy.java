package com.doob.mathagent.learning;

/** Named, explainable constants for the first learning mastery model. */
public final class StudentLearningScoringPolicy {
    public static final int PRIOR_CORRECT = 1;
    public static final int PRIOR_TOTAL = 2;
    public static final int HEALTHY_MASTERY_PERCENT = 70;
    public static final int WEAK_MASTERY_PERCENT = 55;
    public static final int CRITICAL_MASTERY_PERCENT = 35;
    public static final int MAX_WEAKNESS_LEVEL = 5;

    private StudentLearningScoringPolicy() { }

    /** Converts smoothed mastery and answer evidence into a teacher-readable risk level. */
    public static int weaknessLevel(int masteryPercent, int incorrectCount, int attemptCount) {
        if (attemptCount == 0 || (masteryPercent >= HEALTHY_MASTERY_PERCENT && incorrectCount == 0)) return 0;
        if (masteryPercent < CRITICAL_MASTERY_PERCENT) return MAX_WEAKNESS_LEVEL;
        if (masteryPercent < WEAK_MASTERY_PERCENT) return 4;
        if (masteryPercent < HEALTHY_MASTERY_PERCENT) return 3;
        return 2;
    }
}
