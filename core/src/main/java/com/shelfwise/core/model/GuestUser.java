package com.shelfwise.core.model;

/**
 * A profile with no password: the app opens straight into the collection.
 * <p>
 * The right choice on a personal laptop, and the default offered on first
 * run. It adds no fields to {@link User}; what makes it a different class is
 * how it answers the abstract methods.
 */
public class GuestUser extends User {

    /** Creates a guest called "Guest". */
    public GuestUser() {
        this("Guest");
    }

    /**
     * Creates a guest with a chosen name.
     *
     * @param displayName the name to show; must not be blank
     * @throws IllegalArgumentException if the name is blank
     */
    public GuestUser(String displayName) {
        super(displayName);
    }

    /** @return false: a guest is never asked for a password */
    @Override
    public boolean requiresPassword() {
        return false;
    }

    /** @return "Guest" */
    @Override
    public String getProfileType() {
        return "Guest";
    }

    /**
     * A guest always gets in. The password is ignored, so a launch screen
     * that calls this for every profile still behaves correctly.
     *
     * @param password ignored
     * @return true
     */
    @Override
    public boolean authenticate(String password) {
        return true;
    }
}
