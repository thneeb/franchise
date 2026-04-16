package de.neebs.franchise.entity;

public final class DurationFormatter {

    private DurationFormatter() {
    }

    public static String formatNanos(long nanos) {
        long totalTenthsOfMillis = Math.max(0L, nanos / 100_000L);
        long totalSeconds = totalTenthsOfMillis / 10_000L;
        long fractional = totalTenthsOfMillis % 10_000L;
        long seconds = totalSeconds % 60L;
        long totalMinutes = totalSeconds / 60L;
        long minutes = totalMinutes % 60L;
        long hours = totalMinutes / 60L;
        return "%02d:%02d:%02d,%04d".formatted(hours, minutes, seconds, fractional);
    }
}
