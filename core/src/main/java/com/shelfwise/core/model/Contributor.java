package com.shelfwise.core.model;

/**
 * A person who worked on a book, and in what capacity.
 * <p>
 * Deliberately a small value-like object rather than a full entity with its
 * own screen. A contributor is only ever meaningful in relation to a book, so
 * it has no independent lifecycle in the application.
 * <p>
 * Identity is the name and role together, which is why
 * {@code "Gregory Rabassa, translator"} and a hypothetical
 * {@code "Gregory Rabassa, author"} are different contributors: the credit is
 * the thing being recorded, not merely the human.
 */
public class Contributor {

    /** The person's name as printed. Required. */
    private String name;

    /** What they did. Defaults to {@link ContributorRole#AUTHOR}. */
    private ContributorRole role;

    /**
     * Creates an author, which is overwhelmingly the common case.
     *
     * @param name the person's name; must not be blank
     * @throws IllegalArgumentException if the name is blank
     */
    public Contributor(String name) {
        // Chains to the fuller constructor rather than repeating its work.
        // AUTHOR is the default so the ordinary case stays a one-argument call.
        this(name, ContributorRole.AUTHOR);
    }

    /**
     * Creates a contributor in a specific role.
     *
     * @param name the person's name; must not be blank
     * @param role their role; must not be null
     * @throws IllegalArgumentException if the name is blank or the role null
     */
    public Contributor(String name, ContributorRole role) {
        setName(name);
        setRole(role);
    }

    /** @return the person's name */
    public String getName() {
        return name;
    }

    /**
     * @param name the person's name; must not be null or blank
     * @throws IllegalArgumentException if the name is null or blank
     */
    public void setName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Contributor name must not be blank.");
        }
        this.name = name.trim();
    }

    /** @return their role */
    public ContributorRole getRole() {
        return role;
    }

    /**
     * @param role their role; must not be null
     * @throws IllegalArgumentException if the role is null
     */
    public void setRole(ContributorRole role) {
        if (role == null) {
            throw new IllegalArgumentException("Contributor role must not be null.");
        }
        this.role = role;
    }

    /**
     * Two contributors are equal when both name and role match.
     * <p>
     * Overridden so a book cannot end up listing the same credit twice, and
     * so contributors can be de-duplicated when loading.
     *
     * @param other the object to compare with
     * @return true if name and role both match
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Contributor)) {
            return false;
        }
        Contributor that = (Contributor) other;
        return name.equals(that.name) && role == that.role;
    }

    /**
     * Must use the same fields as {@link #equals}, or hash-based collections
     * would misbehave.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return name.hashCode() * 31 + role.hashCode();
    }

    /**
     * @return the name alone for an author, or "name (role)" otherwise, since
     *         labelling every author as an author adds nothing
     */
    @Override
    public String toString() {
        if (role == ContributorRole.AUTHOR) {
            return name;
        }
        return name + " (" + role.getLabel() + ")";
    }
}
