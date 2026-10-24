package com.shelfwise.desktop.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * Holds a page's place in the window until the real page is ported from
 * its approved demo (E152): each later commit replaces one of these.
 * Built in code because it is only a title and one line.
 */
public class ComingSoonPage extends PageController {

    private final VBox view;

    /** @param id the page this stands in for */
    public ComingSoonPage(PageId id) {
        Label title = new Label(id.getLabel());
        title.getStyleClass().add("page-title");
        Label line = new Label("This page is built in a later commit.");
        line.getStyleClass().add("muted");
        view = new VBox(8, title, line);
        view.setAlignment(Pos.CENTER);
    }

    /** @return what the page shows */
    public Node getView() {
        return view;
    }

    /** Nothing to read afresh: the text never changes. */
    @Override
    public void shown() {
    }
}
