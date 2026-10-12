package com.shelfwise.core.model;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One physical copy of a book, on one shelf, in one condition.
 * <p>
 * <strong>Why this class exists.</strong> A count would have been simpler,
 * but the requirement is that copies of the same title can sit in different
 * places - one by the bed, one in the study - and a single number cannot
 * record two locations. So the physical facts live here, per copy, while the
 * bibliographic facts live once on the {@link Book}. Nothing about the text
 * of a book differs between copies; where it is, what it cost and what state
 * it is in all do.
 * <p>
 * A Copy belongs to exactly one Book and cannot outlive it - composition, and
 * the reason the database deletes copies along with their book.
 */
public class Copy {

    /** Permanent internal identity. */
    private final String id;

    /** Id of the location holding it, or null when unshelved. */
    private String locationId;

    /** Physical form. Defaults to {@link Format#PAPERBACK}. */
    private Format format;

    /** State of this copy. Defaults to {@link Condition#GOOD}. */
    private Condition condition;

    /** When it was bought, or null if not recorded. */
    private LocalDate purchaseDate;

    /** What it cost. 0 means not recorded, matching the model-wide rule. */
    private double pricePaid;

    /**
     * Creates a copy with default format and condition and no location.
     * <p>
     * Used when adding a book from a barcode scan, where nothing is known
     * about the physical object yet beyond the fact that it exists.
     */
    public Copy() {
        this(UUID.randomUUID().toString(), Format.PAPERBACK, Condition.GOOD);
    }

    /**
     * Creates a copy with a known format and condition.
     *
     * @param format    the physical form; must not be null
     * @param condition its state; must not be null
     */
    public Copy(Format format, Condition condition) {
        this(UUID.randomUUID().toString(), format, condition);
    }

    /**
     * Recreates a copy that already exists, keeping its identity. Used by the
     * storage layer when loading.
     *
     * @param id        the existing identity
     * @param format    the physical form; must not be null
     * @param condition its state; must not be null
     */
    public Copy(String id, Format format, Condition condition) {
        this.id = id;
        this.pricePaid = 0;
        setFormat(format);
        setCondition(condition);
    }

    /** @return the permanent internal identity */
    public String getId() {
        return id;
    }

    /** @return id of the location holding this copy, or null if unshelved */
    public String getLocationId() {
        return locationId;
    }

    /** @param locationId the location's id, or null to unshelve */
    public void setLocationId(String locationId) {
        this.locationId = locationId;
    }

    /** @return true when this copy has not been assigned a location */
    public boolean isUnshelved() {
        return locationId == null;
    }

    /** @return the physical form */
    public Format getFormat() {
        return format;
    }

    /**
     * @param format the physical form; must not be null
     * @throws IllegalArgumentException if null
     */
    public void setFormat(Format format) {
        if (format == null) {
            throw new IllegalArgumentException("Format must not be null.");
        }
        this.format = format;
    }

    /** @return the condition */
    public Condition getCondition() {
        return condition;
    }

    /**
     * @param condition the condition; must not be null
     * @throws IllegalArgumentException if null
     */
    public void setCondition(Condition condition) {
        if (condition == null) {
            throw new IllegalArgumentException("Condition must not be null.");
        }
        this.condition = condition;
    }

    /** @return when it was bought, or null if not recorded */
    public LocalDate getPurchaseDate() {
        return purchaseDate;
    }

    /**
     * @param purchaseDate when it was bought; null clears it
     * @throws IllegalArgumentException if the date is in the future, which
     *                                  is always a typo
     */
    public void setPurchaseDate(LocalDate purchaseDate) {
        if (purchaseDate != null && purchaseDate.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException(
                    "Purchase date cannot be in the future, but was " + purchaseDate + ".");
        }
        this.purchaseDate = purchaseDate;
    }

    /** @return what it cost, or 0 if not recorded */
    public double getPricePaid() {
        return pricePaid;
    }

    /**
     * @param pricePaid what it cost; 0 means not recorded
     * @throws IllegalArgumentException if negative
     */
    public void setPricePaid(double pricePaid) {
        if (pricePaid < 0) {
            throw new IllegalArgumentException(
                    "Price paid cannot be negative, but was " + pricePaid + ".");
        }
        this.pricePaid = pricePaid;
    }

    /** @return true when a price was actually recorded */
    public boolean hasRecordedPrice() {
        return pricePaid > 0;
    }

    /**
     * Identity is the id alone: two copies with identical format and
     * condition are still two different physical objects.
     *
     * @param other the object to compare with
     * @return true if the ids match
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Copy)) {
            return false;
        }
        return id.equals(((Copy) other).id);
    }

    /** @return hash of the id, matching {@link #equals} */
    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /** @return a short description, e.g. "Paperback, Good" */
    @Override
    public String toString() {
        return format.getLabel() + ", " + condition.getLabel();
    }
}
