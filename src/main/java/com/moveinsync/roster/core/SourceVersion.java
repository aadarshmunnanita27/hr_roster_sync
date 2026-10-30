package com.moveinsync.roster.core;

public record SourceVersion(long value) implements Comparable<SourceVersion> {
    public SourceVersion {
        if (value < 0) {
            throw new IllegalArgumentException("Source version cannot be negative");
        }
    }

    @Override
    public int compareTo(SourceVersion other) {
        return Long.compare(value, other.value);
    }
}
