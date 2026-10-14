package com.shelfwise.core.model;

/**
 * The person using this install of ShelfWise.
 * <p>
 * Abstract because there is no such thing as a plain "user": every profile
 * is either a {@link GuestUser}, who opens the app straight away, or a
 * {@link LocalUser}, who is asked for a password first. The launch screen
 * holds a {@code User} reference and calls {@link #requiresPassword()} and
 * {@link #authenticate(String)} without knowing which kind it has - the
 * method that runs is chosen at runtime by the object's real class. That is
 * runtime polymorphism.
 * <p>
 * There is only one profile per install, so a user has no {@code id} and
 * does not override {@code equals}/{@code hashCode} - unlike every other
 * model class, there is never a second one to tell it apart from.
 */
public abstract class User {

    /** The name shown in the window title and greeting. Required. */
    private String displayName;

    /**
     * Sets the shared part of every profile.
     * <p>
     * {@code protected} because only subclasses construct a user; code
     * elsewhere cannot write {@code new User(...)} on an abstract class
     * anyway.
     *
     * @param displayName the name to show; must not be blank
     * @throws IllegalArgumentException if the name is blank
     */
    protected User(String displayName) {
        setDisplayName(displayName);
    }

    /** @return the name to show */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * @param displayName the name to show; must not be null or blank
     * @throws IllegalArgumentException if null or blank
     */
    public void setDisplayName(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) {
            throw new IllegalArgumentException("Display name must not be blank.");
        }
        this.displayName = displayName.trim();
    }

    /**
     * Whether the launch screen must ask for a password.
     *
     * @return true if a password protects this profile
     */
    public abstract boolean requiresPassword();

    /**
     * The kind of profile, for display and for the settings file.
     *
     * @return a short label such as "Guest" or "Local"
     */
    public abstract String getProfileType();

    /**
     * Checks a password entered at launch.
     * <p>
     * Each subclass answers differently - a guest always gets in, a local
     * profile checks the hash - which is what makes this overriding of
     * behaviour, not just of a returned constant.
     *
     * @param password what the user typed; may be null
     * @return true if the user may open the collection
     */
    public abstract boolean authenticate(String password);

    /**
     * Built from the abstract methods: this concrete method in the parent
     * calls whichever subclass implementation the object really has.
     *
     * @return e.g. "Rohan (Local profile)"
     */
    @Override
    public String toString() {
        return displayName + " (" + getProfileType() + " profile)";
    }
}
