package com.shelfwise.desktop.ui;

import com.shelfwise.core.exception.ShelfWiseException;
import com.shelfwise.core.model.Book;

import java.util.List;

/**
 * Everything the Organise page needs to know about one kind of tree. It
 * extends the picker's {@link TreePicker.Source}, so one adapter object
 * ({@link CategoryTree} or {@link LocationTree}) serves both the picker,
 * which only reads and creates, and the Organise page, which also renames,
 * moves and deletes. An interface extending an interface: everything that
 * is an {@code Organisable} is also a {@code Source}.
 * <p>
 * None of these methods save; the page saves once per logical operation
 * (ADR 0001), because a delete is several changes ({@link #release} then
 * {@link #delete}) that belong together.
 *
 * @param <T> the node type, {@code Category} or {@code Location}
 */
interface Organisable<T> extends TreePicker.Source<T> {

    /** @return the node's id, which stays the same after a rename or move */
    String id(T node);

    /** @return the node's parent, or null for a top-level node */
    T parent(T node);

    /** @return the node and every node beneath it */
    List<T> subtree(T node);

    /** @return the node ids a book is in: its categories, or its copies' locations */
    List<String> idsOf(Book book);

    /** @throws IllegalArgumentException if blank or a sibling has the name */
    void rename(T node, String newName);

    /**
     * @param newParent the new parent, or null for top level
     * @throws IllegalArgumentException if the name clashes there
     */
    void move(T node, T newParent);

    /** @throws ShelfWiseException if any book, trashed or not, is inside */
    void delete(T node) throws ShelfWiseException;

    /**
     * Puts a deleted node back, for Undo. Its sub-nodes come back with it,
     * since they stayed linked to it.
     *
     * @param parent its parent before the delete, or null for top level
     * @throws IllegalArgumentException if the parent is gone or the name is taken now
     */
    void restore(T node, T parent);

    /**
     * E101: takes every book out of a node's subtree before it is deleted -
     * by filing it under {@code moveTo} instead, or, if that is null, by
     * dropping the filing (categories) or unshelving the copy (locations).
     * Trashed books are included, since they would still block the delete.
     * <p>
     * Each change returns a Runnable that reverses it: the command pattern.
     * Undo runs them in order once the node is back (E102).
     *
     * @param node   the node about to be deleted; its sub-nodes are included
     * @param moveTo the node to move the books to, or null to remove them
     * @return the undo steps
     */
    List<Runnable> release(T node, T moveTo);
}
