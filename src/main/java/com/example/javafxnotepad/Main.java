package com.example.javafxnotepad;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class Main extends Application {
    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(Main.class.getResource("notepad-view.fxml"));
       Parent root= loader.load();

        MainController controller = loader.getController();
       controller.setStage(stage);

        Scene scene = new Scene(root, 800, 600);
        stage.setTitle("Notepad");
        stage.setScene(scene);
        stage.show();
//        Scene scene1= new Scene(fxmlLoader.load());
//        scene1.getStylesheets().add(getClass().getResource("/com/example/javafxnotepad/style.css").toExternalForm());


        // ⬇️ Hook exit request
        stage.setOnCloseRequest(event -> {
            boolean shouldExit = controller.handleExitRequest();
            if (!shouldExit) {
                event.consume(); // Prevent window from closing
            }
            else{
                Platform.exit();
                System.exit(0);
            }
        });
    }


    public static void main(String[] args) {
        launch();
    }
}
