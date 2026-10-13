package com.shelfwise.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the {@link User} hierarchy. */
class UserTest {

    @Test
    @DisplayName("a guest is called Guest by default, needs no password and always gets in")
    void guestDefaults() {
        GuestUser guest = new GuestUser();
        assertEquals("Guest", guest.getDisplayName());
        assertFalse(guest.requiresPassword());
        assertEquals("Guest", guest.getProfileType());
        assertTrue(guest.authenticate(null));
        assertTrue(guest.authenticate("anything"));
    }

    @Test
    @DisplayName("display names are trimmed and blank ones are rejected for both kinds")
    void displayNameValidation() {
        assertEquals("Rohan", new GuestUser("  Rohan ").getDisplayName());
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                new GuestUser(" ");
            }
        });
        // The rule lives once, in User, and both subclasses inherit it.
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                new LocalUser(null, "secret");
            }
        });
    }

    @Test
    @DisplayName("a local profile accepts its password and rejects anything else")
    void localAuthenticates() {
        LocalUser user = new LocalUser("Rohan", "correct horse");
        assertTrue(user.requiresPassword());
        assertEquals("Local", user.getProfileType());
        assertTrue(user.authenticate("correct horse"));
        assertFalse(user.authenticate("Correct horse"));
        assertFalse(user.authenticate(""));
        assertFalse(user.authenticate(null));
    }

    @Test
    @DisplayName("a blank password is rejected when creating a local profile")
    void blankPasswordRejected() {
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                new LocalUser("Rohan", "   ");
            }
        });
    }

    @Test
    @DisplayName("the password is stored only as a 64-character hash with a 32-character salt")
    void passwordNotStoredInPlain() {
        LocalUser user = new LocalUser("Rohan", "correct horse");
        assertNotEquals("correct horse", user.getPasswordHash());
        assertTrue(user.getPasswordHash().matches("[0-9a-f]{64}"), user.getPasswordHash());
        assertTrue(user.getSalt().matches("[0-9a-f]{32}"), user.getSalt());
    }

    @Test
    @DisplayName("the same password gives different hashes for different users")
    void saltMakesHashesUnique() {
        // The whole point of a salt: identical passwords must not produce
        // identical hashes, or a precomputed table would crack both.
        LocalUser a = new LocalUser("A", "password1");
        LocalUser b = new LocalUser("B", "password1");
        assertNotEquals(a.getSalt(), b.getSalt());
        assertNotEquals(a.getPasswordHash(), b.getPasswordHash());
    }

    @Test
    @DisplayName("hashing matches the published SHA-256 test vector, with the salt first")
    void knownAnswerSha256() {
        // SHA-256("abc") is the standard test vector from FIPS 180-2. Salt
        // "a" plus password "bc" hashes the bytes "abc", so this proves both
        // that the algorithm is real SHA-256 and that the salt is prepended.
        LocalUser user = new LocalUser("Rohan",
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "a");
        assertTrue(user.authenticate("bc"));
        assertFalse(user.authenticate("abc"));
    }

    @Test
    @DisplayName("a profile reloaded from its stored hash and salt accepts the same password")
    void storageRoundTrip() {
        LocalUser original = new LocalUser("Rohan", "correct horse");
        LocalUser reloaded = new LocalUser(original.getDisplayName(),
                original.getPasswordHash(), original.getSalt());
        assertTrue(reloaded.authenticate("correct horse"));
        assertFalse(reloaded.authenticate("wrong"));
    }

    @Test
    @DisplayName("the storage constructor rejects a blank hash or salt")
    void storageConstructorValidation() {
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                new LocalUser("Rohan", "", "a");
            }
        });
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                new LocalUser("Rohan", "abcd", null);
            }
        });
    }

    @Test
    @DisplayName("changing the password replaces it and generates a fresh salt")
    void changePassword() {
        LocalUser user = new LocalUser("Rohan", "old password");
        String oldSalt = user.getSalt();

        user.changePassword("old password", "new password");

        assertTrue(user.authenticate("new password"));
        assertFalse(user.authenticate("old password"));
        assertNotEquals(oldSalt, user.getSalt());
    }

    @Test
    @DisplayName("changing the password needs the current one, and a refusal changes nothing")
    void changePasswordNeedsCurrent() {
        final LocalUser user = new LocalUser("Rohan", "old password");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                new Executable() {
                    @Override
                    public void execute() {
                        user.changePassword("guess", "new password");
                    }
                });
        assertTrue(e.getMessage().contains("incorrect"), e.getMessage());
        assertTrue(user.authenticate("old password"));
    }

    @Test
    @DisplayName("calls through a User reference run the subclass's own method")
    void runtimePolymorphism() {
        // The launch screen's view of the world: it holds a User and never
        // asks which kind. Each call is dispatched to the real class at
        // runtime.
        List<User> users = new ArrayList<>();
        users.add(new GuestUser());
        users.add(new LocalUser("Rohan", "secret"));

        int needPassword = 0;
        int letIn = 0;
        StringBuilder types = new StringBuilder();
        for (int i = 0; i < users.size(); i++) {
            User user = users.get(i);
            if (user.requiresPassword()) {
                needPassword++;
            }
            if (user.authenticate("x")) {
                letIn++;
            }
            types.append(user.getProfileType()).append(' ');
        }

        assertEquals(1, needPassword);
        assertEquals(1, letIn);
        assertEquals("Guest Local ", types.toString());
    }

    @Test
    @DisplayName("toString shows the name and the kind of profile")
    void toStringShowsType() {
        assertEquals("Guest (Guest profile)", new GuestUser().toString());
        assertEquals("Rohan (Local profile)", new LocalUser("Rohan", "secret").toString());
    }
}
