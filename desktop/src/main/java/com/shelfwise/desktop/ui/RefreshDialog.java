package com.shelfwise.desktop.ui;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogEvent;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * The single-book review of Refresh details (E145).
 * One row per field the lookup would change: a tick, the field, the
 * user's value and the found one. Ticked = take the found value; unticked
 * = keep yours.
 * <p>
 * Opened with {@code show()}, not {@code showAndWait()}: the caller is told
 * the answer through {@link Done} when the dialog closes, so the caller
 * never blocks and can close the dialog itself (the Edit form does, when it
 * moves on to another book).
 */
final class RefreshDialog {

    /** Told the ticked changes when Apply is pressed; not called on Cancel. */
    interface Done {
        void apply(List<RefreshDetails.Change> chosen);
    }

    private RefreshDialog() {
    }

    /**
     * @param owner      the main window: the dialog's owner, and its theme.
     *                   A window rather than a button, because the button
     *                   that asked may have left the screen by the time a
     *                   slow lookup answers (the details panel is rebuilt)
     * @param bookTitle  shown in the header
     * @param changes    from {@link RefreshDetails#diff}, never empty
     * @param yourCover  the user's cover at thumbnail size, or null if none
     * @param done       told the outcome
     * @return the open dialog, so the caller can close it
     */
    static Dialog<ButtonType> open(Window owner, String bookTitle, final List<RefreshDetails.Change> changes,
                                   Node yourCover, final Done done) {
        final Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Refresh details");
        dialog.initOwner(owner);
        // The window's theme, so the dialog is dark in dark mode too.
        dialog.getDialogPane().getStylesheets().setAll(owner.getScene().getStylesheets());
        dialog.getDialogPane().getStyleClass().addAll("add-page", "refresh-dialog");

        Label heading = new Label("Refresh details — ‘" + bookTitle + "’");
        heading.getStyleClass().add("section-title");
        Label from = new Label(changes.get(0).found.source
                + "  ·  ticked = use the found value, unticked = keep yours");
        from.getStyleClass().add("muted");

        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(8);
        grid.getStyleClass().add("refresh-grid");
        grid.add(header("Field"), 1, 0);
        grid.add(header("Yours"), 2, 0);
        grid.add(header("Found"), 3, 0);

        final ButtonType apply = new ButtonType("Apply", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, apply);
        final Button applyButton = (Button) dialog.getDialogPane().lookupButton(apply);
        applyButton.getStyleClass().add("accent-button");

        for (int i = 0; i < changes.size(); i++) {
            final RefreshDetails.Change change = changes.get(i);
            final CheckBox use = new CheckBox();
            use.setSelected(change.use);
            use.selectedProperty().addListener(new ChangeListener<Boolean>() {
                @Override
                public void changed(ObservableValue<? extends Boolean> obs, Boolean was, Boolean now) {
                    change.use = now;
                    updateApply(applyButton, changes);
                }
            });
            int row = i + 1;
            grid.add(use, 0, row);
            grid.add(new Label(change.field.getLabel()), 1, row);
            if (change.field == RefreshDetails.Field.COVER) {
                grid.add(yourCover == null ? value("") : yourCover, 2, row);
                grid.add(CoverArt.imageView(change.found.cover, 60, 90), 3, row);
            } else {
                grid.add(value(change.yours), 2, row);
                grid.add(value(change.theirs), 3, row);
            }
        }
        updateApply(applyButton, changes);

        VBox content = new VBox(10, heading, from, grid);
        content.setPadding(new Insets(4, 4, 4, 4));
        content.setAlignment(Pos.TOP_LEFT);
        dialog.getDialogPane().setContent(content);

        dialog.setOnHidden(new EventHandler<DialogEvent>() {
            @Override
            public void handle(DialogEvent event) {
                if (dialog.getResult() == apply) {
                    List<RefreshDetails.Change> chosen = new ArrayList<>();
                    for (int i = 0; i < changes.size(); i++) {
                        if (changes.get(i).use) {
                            chosen.add(changes.get(i));
                        }
                    }
                    done.apply(chosen);
                }
            }
        });
        dialog.show();
        return dialog;
    }

    /** "Apply 3 changes"; disabled when nothing is ticked, since that would change nothing. */
    private static void updateApply(Button applyButton, List<RefreshDetails.Change> changes) {
        int n = RefreshDetails.countUsed(changes);
        applyButton.setText("Apply " + WishlistPicker.count(n, "change", "changes"));
        applyButton.setDisable(n == 0);
    }

    private static Label header(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("section-label");
        return label;
    }

    /** A value cell: "—" when empty, wrapped so a long title does not widen the dialog. */
    private static Label value(String text) {
        Label label = new Label(RefreshDetails.shown(text));
        label.setWrapText(true);
        label.setMaxWidth(240);
        if (text == null || text.isEmpty()) {
            label.getStyleClass().add("muted");
        }
        return label;
    }
}
