/**
 * User-defined exceptions, all descending from
 * {@link com.shelfwise.core.exception.ShelfWiseException}.
 * <p>
 * The hierarchy is:
 * <pre>
 * Exception
 * └── ShelfWiseException (abstract, checked)
 *     ├── InvalidISBNException
 *     ├── DuplicateItemException
 *     ├── CategoryNotEmptyException
 *     ├── LocationNotEmptyException
 *     ├── MetadataFetchException
 *     └── SyncConflictException
 * </pre>
 * Every one is checked, so the compiler forces the user interface to handle
 * it. That is intentional: each corresponds to a decision only the user can
 * make, not a bug to be logged and ignored.
 * <p>
 * Standard exceptions are still used where they fit better -
 * {@code IllegalArgumentException} from validating setters,
 * {@code IOException} and {@code NumberFormatException} in file and CSV
 * handling - because inventing a custom type for "this number is negative"
 * would add no information.
 */
package com.shelfwise.core.exception;
