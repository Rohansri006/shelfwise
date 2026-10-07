package com.shelfwise.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Smoke test: confirms JUnit runs. Real test cases are added per feature. */
class AppInfoTest {

    @Test
    void displayNameCombinesNameAndVersion() {
        assertEquals("ShelfWise 1.0-SNAPSHOT", AppInfo.getDisplayName());
    }
}
