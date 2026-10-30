package com.moveinsync.roster.core;

public enum SyncOutcome {
    APPLIED,
    DUPLICATE,
    STALE_IGNORED,
    VERSION_CONFLICT,
    REJECTED
}
