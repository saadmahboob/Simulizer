package simulizer.ui.components;

import com.sun.javafx.scene.control.skin.resources.ControlResources;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * A dialog that shows a slider input control to the user.
 */
public class SliderInputDialog extends Dialog<String> {

    /**************************************************************************
     *
     * Fields
     *
     **************************************************************************/

    private final GridPane grid;
    private final Label label;
    private final Slider sliderField;
    private final Spinner<Double> resultField;



    /**************************************************************************
     *
     * Constructors
     *
     **************************************************************************/

    /**
     * Creates a new SliderInputDialog without default values entered into the
     * dialog.
     */
    public SliderInputDialog() {
        this(0, 1000, 500);
    }

    /**
     * Creates a new SliderInputDialog with the default values entered into the
     * dialog.
     */
    public SliderInputDialog(double min, double max, double value) {
        final DialogPane dialogPane = getDialogPane();

        // -- textfield
        this.sliderField = new Slider();
        this.sliderField.setMin(min);
        this.sliderField.setMax(max);
        this.sliderField.setValue(value);
        this.sliderField.setShowTickLabels(true);
        this.sliderField.setShowTickMarks(true);
        this.sliderField.setMajorTickUnit(max / 2 - 1);
        this.sliderField.setMinorTickCount(5);
        this.sliderField.setBlockIncrement(0.1);
        resultField = new Spinner(min, max, value);

        this.sliderField.valueProperty().addListener(new ChangeListener<Number>() {
            @Override
            public void changed(ObservableValue<? extends Number> observable, Number oldValue, Number newValue) {
                resultField.getValueFactory().setValue(newValue.doubleValue());
            }
        });

        resultField.valueProperty().addListener(new ChangeListener<Double>() {
            @Override
            public void changed(ObservableValue<? extends Double> observable, Double oldValue, Double newValue) {
                sliderField.setValue(newValue);
            }
        });


        GridPane.setHgrow(sliderField, Priority.ALWAYS);
        GridPane.setFillWidth(sliderField, true);

        // -- label
        label = new Label(dialogPane.getContentText());
        label.setMaxWidth(Double.MAX_VALUE);
        label.setMaxHeight(Double.MAX_VALUE);
        label.getStyleClass().add("content");
        label.setWrapText(true);
        label.setPrefWidth(360);
        label.setPrefWidth(Region.USE_COMPUTED_SIZE);
        label.textProperty().bind(dialogPane.contentTextProperty());

        this.grid = new GridPane();
        this.grid.setHgap(10);
        this.grid.setMaxWidth(Double.MAX_VALUE);
        this.grid.setAlignment(Pos.CENTER_LEFT);

        dialogPane.contentTextProperty().addListener(o -> updateGrid());

        setTitle(ControlResources.getString("Dialog.confirm.title"));
        dialogPane.setHeaderText(ControlResources.getString("Dialog.confirm.header"));
        dialogPane.getStyleClass().add("text-input-dialog");
        dialogPane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        updateGrid();

        setResultConverter((dialogButton) -> {
            ButtonBar.ButtonData data = dialogButton == null ? null : dialogButton.getButtonData();
            return data == ButtonBar.ButtonData.OK_DONE ? Double.toString(sliderField.getValue()) : null;
        });
    }



    /**************************************************************************
     *
     * Public API
     *
     **************************************************************************/

    /**
     * Returns the {@link Slider} used within this dialog.
     */
    public final Slider getEditor() {
        return sliderField;
    }

    /**************************************************************************
     *
     * Private Implementation
     *
     **************************************************************************/

    private void updateGrid() {
        grid.getChildren().clear();

        grid.add(label, 0, 0);
        grid.add(sliderField, 1, 0);
        grid.add(resultField, 2, 0);
        getDialogPane().setContent(grid);

        Platform.runLater(() -> sliderField.requestFocus());
    }
}
