package com.shelfwise.core.storage;

import com.shelfwise.core.model.GuestUser;
import com.shelfwise.core.model.LocalUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link Settings} and {@link SettingsFile}. */
class SettingsFileTest {

    @TempDir
    Path folder;

    @Test
    @DisplayName("with no file yet, the settings are those of a fresh install")
    void missingFileGivesDefaults() throws IOException {
        Settings settings = new SettingsFile(folder.resolve("settings.json")).load();

        assertTrue(settings.isFirstRun());
        assertNull(settings.getDataFolder());
        assertNull(settings.getUser());
        assertEquals(Settings.Theme.DARK, settings.getTheme());
        assertNull(settings.getGoogleApiKey());
    }

    @Test
    @DisplayName("a Local user survives saving and loading, password check included")
    void localUserRoundTrip() throws IOException {
        // A sub-folder that does not exist yet, as ~/.shelfwise on first run.
        SettingsFile file = new SettingsFile(folder.resolve("dot-shelfwise/settings.json"));
        Settings settings = new Settings();
        settings.setDataFolder(folder.resolve("ShelfWise"));
        settings.setUser(new LocalUser("Rohan", "open sesame"));
        settings.setTheme(Settings.Theme.LIGHT);
        settings.setGoogleApiKey("  key-123  ");

        file.save(settings);
        Settings back = file.load();

        assertFalse(back.isFirstRun());
        assertEquals(folder.resolve("ShelfWise"), back.getDataFolder());
        assertTrue(back.getUser() instanceof LocalUser);
        assertEquals("Rohan", back.getUser().getDisplayName());
        // Only the hash and salt were saved, yet the password still works.
        assertTrue(back.getUser().authenticate("open sesame"));
        assertFalse(back.getUser().authenticate("wrong"));
        assertEquals(Settings.Theme.LIGHT, back.getTheme());
        assertEquals("key-123", back.getGoogleApiKey());
        // The all-or-nothing save leaves no temporary file behind.
        assertFalse(Files.exists(folder.resolve("dot-shelfwise/settings.json.tmp")));
    }

    @Test
    @DisplayName("a Guest user comes back as a guest, with no password stored")
    void guestRoundTrip() throws IOException {
        Path path = folder.resolve("settings.json");
        Settings settings = new Settings();
        settings.setUser(new GuestUser("Reader"));
        new SettingsFile(path).save(settings);

        String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        assertFalse(text.contains("passwordHash"));

        Settings back = new SettingsFile(path).load();
        assertTrue(back.getUser() instanceof GuestUser);
        assertEquals("Reader", back.getUser().getDisplayName());
        // No folder chosen yet, so still first run.
        assertTrue(back.isFirstRun());
    }

    @Test
    @DisplayName("a damaged file or an unknown kind of user is reported, not ignored")
    void damagedFile() throws IOException {
        final Path broken = folder.resolve("broken.json");
        Files.write(broken, "{ this is not json".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                new SettingsFile(broken).load();
            }
        });

        final Path unknown = folder.resolve("unknown.json");
        Files.write(unknown, "{\"userType\":\"ADMIN\",\"displayName\":\"X\"}"
                .getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                new SettingsFile(unknown).load();
            }
        });
    }

    @Test
    @DisplayName("printing the settings never shows the Google key")
    void keyNeverPrinted() {
        // L7: the key must not end up in a log.
        Settings settings = new Settings();
        settings.setGoogleApiKey("secret-key");
        assertFalse(settings.toString().contains("secret-key"));
        assertTrue(settings.toString().contains("Google key set"));

        settings.setGoogleApiKey(" ");
        assertNull(settings.getGoogleApiKey());
        assertTrue(settings.toString().contains("Google key not set"));
    }
}
