package com.example.javafxnotepad;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.FileChooser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.File;

import java.util.*;
import java.util.concurrent.*;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpansBuilder;


import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



public class MainController {

    @FXML
    private StackPane editorPane;
    @FXML
    private TextArea textArea;

    private CodeArea codeArea;

    public ComboBox<String> languageSelector;


    @FXML
    private VBox outputWrapper;
    @FXML
    private CheckBox codeModeToggle;
    @FXML
    private Button executeModeToggle;
    @FXML
    private TextArea outputArea;
    @FXML
    private BorderPane rootPane;

    private Stage primaryStage;
    private final String DARK_THEME = Objects.requireNonNull(getClass().getResource("style.css")).toExternalForm();

    @FXML
    private CheckMenuItem darkModeToggle;

    private boolean isDirty = false; // true = unsaved changes exist
    private File currentFile = null;


    private String currentSuggestion = "";
    private boolean isGhostVisible = false;

    private Path sessionTempDir;

    public void setStage(Stage stage) {
        this.primaryStage = stage;
    }


    private boolean isUpdating = false;


    private GroqSuggestionService suggestionService = new GroqSuggestionService();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ScheduledExecutorService debounceScheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> debounceFuture;

    private boolean userTyped=false;
    private static final Logger logger = LoggerFactory.getLogger(MainController.class);
    private Process runningProcess;

    private final List<Process> runningProcesses = new ArrayList<>();

    @FXML
    public void initialize() {
        textArea.textProperty().addListener((observable, oldValue, newValue) -> {
            isDirty = true;// mark that there are unsaved changes
            updateTitle();
        });

        languageSelector.getItems().addAll("Python", "Java", "C", "C++");
        languageSelector.getSelectionModel().selectFirst();
        languageSelector.setDisable(true);

        //Hide O/P area & execute toggle by default
        outputWrapper.setVisible(false);
        outputWrapper.setManaged(false);
        executeModeToggle.setDisable(true);

        //code area setup

        if (codeArea == null) {
            codeArea = new CodeArea();
            codeArea.setWrapText(true);
        }

        codeArea.textProperty().addListener((obs, oldText, newText) -> {
            isDirty = true;
            updateTitle();
        });

        //Ensure only plain text area at startup
        editorPane.getChildren().clear();
        editorPane.getChildren().add(textArea);

        //Temporary directory for each session
        try {
            sessionTempDir = Files.createTempDirectory("javafx-notepad-");
        } catch (IOException e) {
            showAlert("Error", "Could not create session temp dir:\n" + e.getMessage());
        }

        //ShutDown clean-up Using method reference
        Runtime.getRuntime().addShutdownHook(new Thread(this::cleanupTempDir));
        //can also be done as
        /*
        Runtime.getRuntime().addShutdownHook(new Thread(()->{
            cleanupTempDir();
        }));
        */

        //set-up ghost logic
        setupGhostTextLogic();

        codeArea.getStylesheets().add(Objects.requireNonNull(getClass().getResource("ghost-suggestion.css")).toExternalForm());

        suggestionService= new GroqSuggestionService();
    }


    //Recursively delete everything under sessionTempDir
    private void cleanupTempDir() {
        if (sessionTempDir == null) return;
        try (var stream = Files.walk(sessionTempDir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }


    @FXML
    public void handleNew() {
        if (isDirty) {
            Alert alert = new Alert(Alert.AlertType.NONE);
            alert.setTitle("Unsaved Changes");
            alert.setHeaderText("Do you want to save the changes before creating a new file?");
            alert.setContentText("Choose your option:");

            ButtonType saveButton = new ButtonType("Save");
            ButtonType dontSaveButton = new ButtonType("Don't Save");
            ButtonType cancel = new ButtonType("Cancel");

            alert.getButtonTypes().setAll(saveButton, dontSaveButton, cancel);

            Optional<ButtonType> result = alert.showAndWait();//Shows the dialogBox and wait for response;
            if (result.isPresent()) {
                if (result.get() == saveButton) {
                    handleSave();// save the current work
                    if (isDirty)
                        return;//if user cancelled save dialog box
                } else if (result.get() == cancel) {
                    return;// cancels new
                }
                //if both of them are not clicked then that means don't save is clicked, for that
                //it will skip both processes and
                // after this text area is cleared for a new file.
            }

        }

        textArea.clear();       //Clear for new file
        currentFile = null;       //forget the previous file
        isDirty = false;     //reset unsaved flag , for new file consider no changes done yet.
        updateTitle();
        System.out.println("New File Created");

    }

    @FXML
    public void handleOpen() {
        //check unsaved changes
        if (isDirty) {
            Alert alert = new Alert(Alert.AlertType.NONE);
            alert.setTitle("Unsaved Changes");
            alert.setHeaderText("You have unsaved changes.");
            alert.setContentText("Do you want to save the changes before opening a new file?");

            ButtonType saveButton = new ButtonType("Save");
            ButtonType dontSaveButton = new ButtonType("Don't Save");
            ButtonType cancel = new ButtonType("Cancel");
            alert.getButtonTypes().setAll(saveButton, dontSaveButton, cancel);

            Optional<ButtonType> result = alert.showAndWait();
            if (result.isPresent()) {
                if (result.get() == saveButton) {
                    handleSave();
                    if (isDirty) return;
                } else if (result.get() == cancel) {
                    return;
                }
                //else means don't save then we continue to open the file the user asked for...
            }
        }
        //Now show OPEN dialog box
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Open File");
        fileChooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Text Files", "*.txt"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
        );

        File file = fileChooser.showOpenDialog(primaryStage);
        if (file != null) {
            //currentFile = file;
//            isDirty = false;
//            primaryStage.setTitle(file.getName() + " - Notepad");

            //Read the content of the current file that is chosen

             /* String content = Files.readString(file.toPath());
                blocks the JavaFX thread
                fast for small files but will freeze the UI for Large files
                textArea.setText(content);
*/
            //hence we use the BACKGROUND THREAD SO THAT UI ONLY TOUCHES IT WHEN IT'S READY
            //THIS OVERCOMES THE FREEZING OF THE UI WITH LARGE FILES
            //FOR THIS WE USE Platform.runLater

            //BACKGROUND THREAD
            new Thread(() -> {
                //DEFAULT BUFFER SIZE IS 8192 characters (8KB)
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                    StringBuilder content = new StringBuilder();
                    String line;

                    while ((line = reader.readLine()) != null) {
                        content.append(line).append("\n");
                    }
                    //Return to the main thread for UI changes
                    javafx.application.Platform.runLater(() -> {
                        textArea.setText(content.toString());
                        currentFile = file;
                        isDirty = false;
                        updateTitle();

                        System.out.println("Opened: " + file.getAbsolutePath());
                    });
                } catch (IOException e) {
                    logger.error("Exception occurred", e);
                    javafx.application.Platform.runLater(() -> {
                        showAlert("Error", "Could not read the file:\n" + e.getMessage());
                    });
                }
            }).start();

        }
    }

    @FXML
    public void handleSave() {
        if (currentFile == null) {
            handleSaveAs();//no currentFile , that means the file is saving for the first time & not have been saved earlier
            return;
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(currentFile))) {
            writer.write(textArea.getText());
            isDirty = false;
            updateTitle();
            System.out.println("File saved: " + currentFile.getAbsolutePath());
        } catch (IOException e) {
            logger.error("Exception occurred", e);//on console
            showAlert("Error", "Could not save the file:\n" + e.getMessage());//on dialog_box
        }
        isDirty = false;
        updateTitle();


    }
    //System.out.println("Save file clicked.");


    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    @FXML
    public void handleSaveAs() {
        FileChooser fileChooser = new FileChooser();  //  Create a file chooser dialog
        fileChooser.setTitle("Save As");              // Set the title for the dialog

        fileChooser.getExtensionFilters().addAll(     //  Add filters for common file types
                new FileChooser.ExtensionFilter("Text Files", "*.txt"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
        );

        File selectedFile = fileChooser.showSaveDialog(primaryStage);  //Show dialog

        if (selectedFile != null) {  // 5. If user didn’t cancel
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(selectedFile))) {
                writer.write(textArea.getText());      // Write text from editor to file

                currentFile = selectedFile;            // Track this file for next saves
                isDirty = false;                       // Reset unsaved changes flag

                updateTitle();
                // Update title bar

                System.out.println("File saved as: " + selectedFile.getAbsolutePath());

            } catch (IOException e) {
                logger.error("Exception occurred", e);
                // show user-friendly error dialog
            }

        }
        isDirty = false;
        updateTitle();

    }


    @FXML
    public void handleExit() {
        // Check which editor has content and unsaved changes
        boolean shouldCheckForChanges;
        String currentContent;

        if (codeModeToggle.isSelected()) {
            shouldCheckForChanges = codeArea != null && !codeArea.getText().isEmpty();
            currentContent = codeArea.getText();
        } else {
            shouldCheckForChanges = !textArea.getText().isEmpty();
            currentContent = textArea.getText();
        }

        boolean dirty = shouldCheckForChanges && isDirty;
        if (dirty) {
            Alert alert = new Alert(Alert.AlertType.NONE);
            alert.setTitle("Unsaved Changes");
            alert.setHeaderText("You have unsaved changes.");
            alert.setContentText("Do you want to save before exiting?");

            //defining buttons
            ButtonType saveButton = new ButtonType("Save");
            ButtonType dontSaveButton = new ButtonType("Don't Save");
            ButtonType cancelButton = new ButtonType("Cancel");
            //adding buttons to dialog box
            alert.getButtonTypes().addAll(saveButton, dontSaveButton, cancelButton);

            //Response from user via buttons
            Optional<ButtonType> result = alert.showAndWait();
            if (result.isPresent()) {
                if (result.get() == saveButton) {
                    if(codeModeToggle.isSelected()) {
                        handleSaveCodeMode();
                    }
                    else {
                        handleSave();
                    }
                    if (isDirty) return;//if save was cancelled or returned
                } else if (result.get() == cancelButton) {
                    return;
                }
                //Else means user doesn't want to save the current work on file
                //so go on and exit
            }
        }
        if (runningProcess != null && runningProcess.isAlive()) {
            // Kill all children first
            runningProcess.descendants().forEach(ph -> ph.destroyForcibly());

            // Kill the main process
            runningProcess.destroyForcibly();

            try {
                runningProcess.waitFor(); // only on Process, not ProcessHandle
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        //no unsaved changes
        primaryStage.close();
        cleanupTempDir();


        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t != Thread.currentThread() && !t.isDaemon()) {
                t.interrupt();
            }
        }
        Platform.exit();
        System.exit(0);
        Runtime.getRuntime().halt(0);
    }

    private void handleSaveCodeMode() {
        if (currentFile == null) {
            handleSaveAs(); // reuse existing Save As logic
        } else {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(currentFile))) {
                writer.write(codeArea.getText());
                isDirty = false;
                updateTitle();
            } catch (IOException e) {
                showAlert("Error", "Could not save file:\n" + e.getMessage());
            }
        }
    }


    public boolean handleExitRequest() {
        if (isDirty) {
            Alert alert = new Alert(Alert.AlertType.NONE);
            alert.setTitle("Unsaved Changes");
            alert.setHeaderText("You have unsaved changes.");
            alert.setContentText("Do you want to save before exiting?");

            ButtonType saveButton = new ButtonType("Save", ButtonBar.ButtonData.YES);
            ButtonType dontSaveButton = new ButtonType("Don't Save", ButtonBar.ButtonData.NO);
            ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);

            alert.getButtonTypes().addAll(saveButton, dontSaveButton, cancelButton);

            Optional<ButtonType> result = alert.showAndWait();

            if (result.isPresent()) {
                if (result.get() == saveButton) {
                    handleSave();
                    return !isDirty;//only exit if save was successful
                } else return result.get() == dontSaveButton;
            }
            return false;//dialog closed without selection
        }

        return true;//No unsaved changes, safe to exit
    }

    //STANDARD CONVENTION FOR TEXT EDITORS TO DISTINGUISH BETWEEN SAVED AND UNSAVED FILES
    private void updateTitle() {
        String fileName = (currentFile != null) ? currentFile.getName() : "Untitled";
        String dirtyMark = isDirty ? "*" : "";
        primaryStage.setTitle(fileName + dirtyMark + " - Notepad");
    }

    @FXML
    public void handleUndo() {
        textArea.undo();
    }

    @FXML
    public void handleRedo() {
        textArea.redo();
    }

    @FXML
    public void handleCut() {
        textArea.cut();
    }

    @FXML
    public void handleCopy() {
        textArea.copy();
    }

    @FXML
    public void handlePaste() {
        textArea.paste();
    }

    @FXML
    public void handleSelectAll() {
        textArea.selectAll();
    }

    @FXML
    private void handleDarkModeToggle() {
        Scene scene = primaryStage.getScene();
        if (darkModeToggle.isSelected()) {
            if (!scene.getStylesheets().contains(DARK_THEME)) {
                scene.getStylesheets().add(DARK_THEME);
                codeArea.getStyleClass().add("code-area");

            }
        } else {
            scene.getStylesheets().remove(DARK_THEME);
            codeArea.getStyleClass().remove("code-area");

        }
    }

    @FXML
    private void handleCodeModeToggle() {
        boolean isCodeMode = codeModeToggle.isSelected();

        outputWrapper.setVisible(isCodeMode);
        outputWrapper.setManaged(isCodeMode);

        languageSelector.setDisable(!isCodeMode);
        executeModeToggle.setDisable(!isCodeMode);

        //codeArea.textProperty().addListener((obs, old, new) -> isDirty = true);

        if (!isCodeMode) {
            outputArea.clear();
        }
//Initialize the editor pane first time
        if (isCodeMode && codeArea == null) {
            codeArea = new CodeArea();
            //here obs=codeArea.textProperty()(object that is being watched)
            codeArea.textProperty().addListener((obs, oldText, newText) -> {
                isDirty = true;
                updateTitle();
            });
        }

        //Clear the existing editor pane
        editorPane.getChildren().clear();
        if (isCodeMode) {
            codeArea.replaceText(textArea.getText());
            editorPane.getChildren().add(codeArea);
        } else {
            textArea.setText(codeArea != null ? codeArea.getText() : "");
            editorPane.getChildren().add(textArea);
        }


        System.out.println("Code Mode is " + (isCodeMode ? "ON" : "OFF"));
    }

    @FXML
    private void handleExecuteMode() {
        if (!codeModeToggle.isSelected()) {
            outputArea.setText("Code Mode must be enabled first to execute.");
            return;
        }


//        String code = textArea.getText();
        String code = codeModeToggle.isSelected() ? codeArea.getText() : textArea.getText();

        String language = languageSelector.getValue();

        try {
            Path temDir = sessionTempDir; //folder for storing code files
            File codeFile;
            ProcessBuilder processBuilder;


            switch (language) {
                case "Python" -> {
                    String fileName = "script.py";
                    codeFile = new File(temDir.toFile(), fileName);

                    Files.writeString(codeFile.toPath(), code);

                    String pythonCmd = System.getProperty("os.name").toLowerCase().contains("win") ? "python" : "python3";
                    processBuilder = new ProcessBuilder(pythonCmd, codeFile.getAbsolutePath());
                    processBuilder.directory(temDir.toFile());
                }

//                case "Python" -> {
//                    codeFile = new File(temDir.toFile(), "script.py");
//                    Files.writeString(codeFile.toPath(), code);
//
//                    processBuilder = new ProcessBuilder("python", codeFile.getAbsolutePath());
//                }



                case "Java" -> {
                    String className = detectJavaClassName(code);
                    System.out.println(">>> Detected className: " + className);

                    codeFile = new File(temDir.toFile(), className + ".java");
                    Files.writeString(codeFile.toPath(), code);

                    // Compile
                    Process compile = new ProcessBuilder("javac", codeFile.getAbsolutePath())
                            .directory(temDir.toFile())
                            .start();

                    // Capture compile errors
                    String compileErrors = new String(compile.getErrorStream().readAllBytes());
                    compile.waitFor();
                    runningProcesses.add(compile);

                    if (compile.exitValue() != 0) {
                        outputArea.setText("Compilation failed:\n" + compileErrors);
                        return;
                    }

                    // Run (set classpath for safety)
                    processBuilder = new ProcessBuilder("java", className);
                    processBuilder.directory(temDir.toFile());
                    processBuilder.environment().put("CLASSPATH", temDir.toAbsolutePath().toString());
                }

                case "C" -> {
                    String fileName = detectCOrCppFileName(code, "C") + ".c";
                    codeFile = new File(temDir.toFile(), fileName);
                    Files.writeString(codeFile.toPath(), code);

                    // Cross-platform output filename
                    String outputFileName = System.getProperty("os.name").toLowerCase().contains("win") ? "main.exe" : "main";
                    File outputFile = new File(temDir.toFile(), outputFileName);

                    // Compile
                    Process compile = new ProcessBuilder("gcc", codeFile.getAbsolutePath(), "-o", outputFile.getAbsolutePath())
                            .directory(temDir.toFile())
                            .start();
                    compile.waitFor();
                    runningProcesses.add(compile);
                    if (!outputFile.exists()) {
                        outputArea.setText("Compilation failed: Executable not created.");
                        return;
                    }

                    // Run
                    processBuilder = new ProcessBuilder(outputFile.getAbsolutePath());
                }

//                case "C" -> {
//                    //COPY TO FILE
//                    String fileName = detectCOrCppFileName(code, "C") + ".c";
//                    codeFile = new File(temDir.toFile(), fileName);
//
//                    Files.writeString(codeFile.toPath(), code);
//                    //COMPILE
//                    Process compile = new ProcessBuilder("gcc", codeFile.getAbsolutePath(), "-o", "main").directory(temDir.toFile()).start();
//                    compile.waitFor();
//                    //RUN
//                    String execName = System.getProperty("os.name").toLowerCase().contains("win") ? "main.exe" : "./main";
//                    processBuilder = new ProcessBuilder(execName);
//                    processBuilder.directory(temDir.toFile());
//
////                    processBuilder = new ProcessBuilder("./main");
////                    processBuilder.directory(temDir.toFile());
//
//                }

                case "C++" -> {
                    String fileName = detectCOrCppFileName(code, "C++") + ".cpp";
                    codeFile = new File(temDir.toFile(), fileName);
                    Files.writeString(codeFile.toPath(), code);

                    // Cross-platform output filename
                    String outputFileName = System.getProperty("os.name").toLowerCase().contains("win") ? "main.exe" : "main";
                    File outputFile = new File(temDir.toFile(), outputFileName);

                    // Compile
                    Process compile = new ProcessBuilder("g++", codeFile.getAbsolutePath(), "-o", outputFile.getAbsolutePath())
                            .directory(temDir.toFile())
                            .start();
                    runningProcesses.add(compile);
                    compile.waitFor();

                    if (!outputFile.exists()) {
                        outputArea.setText("Compilation failed: Executable not created.");
                        return;
                    }

                    // Run
                    processBuilder = new ProcessBuilder(outputFile.getAbsolutePath());
                }

//                case "C++" -> {
//                    String fileName = detectCOrCppFileName(code, "C++") + ".cpp";
//                    codeFile = new File(temDir.toFile(), fileName);
//
//                    Files.writeString(codeFile.toPath(), code);
//
//                    // Determine platform-specific executable name
//                    String isWindows = System.getProperty("os.name").toLowerCase();
//                    String outputName = isWindows.contains("win") ? "main.exe" : "main";
//
//                    // Compile: output should be main.exe on Windows, main otherwise
//                    Process compile = new ProcessBuilder("g++", codeFile.getAbsolutePath(), "-o", outputName)
//                            .directory(temDir.toFile())
//                            .start();
//
//                    // Capture and check compilation error (optional but recommended)
//                    BufferedReader compileErrorReader = new BufferedReader(
//                            new InputStreamReader(compile.getErrorStream()));
//                    StringBuilder compileErrors = new StringBuilder();
//                    String line;
//                    while ((line = compileErrorReader.readLine()) != null) {
//                        compileErrors.append(line).append("\n");
//                    }
//
//                    int compileExit = compile.waitFor();
//                    if (compileExit != 0) {
//                        outputArea.setText(" Compilation failed:\n" + compileErrors);
//                        return;
//                    }
//
//                    // Create process builder to run the compiled executable
//                    String execPath = isWindows.contains("win") ? outputName : "./" + outputName;
//                    processBuilder = new ProcessBuilder(execPath);
//                    processBuilder.directory(temDir.toFile());
//                }


                default -> {
                    outputArea.setText("Unsupported Language Selected");
                    return;
                }
            }
            //RUN
            processBuilder.redirectErrorStream(true);//stderr to stdout  catches (error + normal) output
            runningProcess = processBuilder.start();
            runningProcesses.add(runningProcess);
            String output = new String(runningProcess.getInputStream().readAllBytes());
            outputArea.setText(output);
        } catch (IOException | InterruptedException e) {

            System.out.println("Error while executing the code: \n" + e.getMessage());
            //Print the error msg on the output text area instead of console
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            outputArea.setText(sw.toString());
        }
    }




//    private String detectJavaClassName(String code) {
//        System.out.println("---- Detecting Java Class Name ----");
//        System.out.println("Code Received:\n" + code);
//
//        // Matches any class declaration (public or not)
//        Pattern pattern = Pattern.compile("\\bclass\\s+(\\w+)");
//        Matcher matcher = pattern.matcher(code);
//
//        if (matcher.find()) {
//            System.out.println("Detected class: " + matcher.group(1));
//            return matcher.group(1); // Return class name
//        }
//
//        System.out.println("No class detected. Returning fallback 'Main'");
//        return "Main"; // fallback
//    }


    private String detectJavaClassName(String code) {
        System.out.println("---- Detecting Java Class Name ----");
        code = code.replace("\r", "");  // Normalize line endings

        // Match only top-level class that has a main method inside
        Pattern pattern = Pattern.compile(
                "\\bclass\\s+(\\w+)\\s*\\{[^}]*?public\\s+static\\s+void\\s+main\\s*\\(",
                Pattern.DOTALL
        );

        Matcher matcher = pattern.matcher(code);
        if (matcher.find()) {
            String name = matcher.group(1);
            System.out.println("Detected Java class with main(): " + name);
            return name;
        }

        // Fallback to any class (not necessarily with main)
        matcher = Pattern.compile("\\bclass\\s+(\\w+)").matcher(code);
        if (matcher.find()) {
            String name = matcher.group(1);
            System.out.println("Detected Java class (no main() check): " + name);
            return name;
        }

        System.out.println("No class found. Fallback to 'Main'");
        return "Main";
    }

    private String detectCOrCppFileName(String code, String language) {
        if (code.contains("main(")) {
            return "main";  // default name
        } else {
            return "program"; // fallback
        }
    }

    private void setupGhostTextLogic() {
        codeArea.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.TAB) {
                event.consume();
                if (isGhostVisible) {
                    acceptGhostSuggestion();
                } else {
                    int pos = codeArea.getCaretPosition();
                    codeArea.insertText(pos, "\t");
                    codeArea.moveTo(pos + 1);
                }
            }

        });


        // Detect actual typing
        codeArea.addEventFilter(KeyEvent.KEY_TYPED, event -> {
            // KEY_TYPED is the correct event for characters
            //Ensures a valid key is pressed
            if (!event.isControlDown() && !event.getCharacter().isEmpty()) {
                userTyped = true;
            }
        });


        // Remove ghost when caret moves left (back)
        codeArea.caretPositionProperty().addListener((obs, oldPos, newPos) -> {
            if (newPos < oldPos) {
                removeGhostText();
            }
        });


        codeArea.textProperty().addListener((obs, oldText, newText) -> {
            if (isUpdating) return;

            removeGhostText();
            if (newText.trim().isEmpty()) return;

            // Cancel any pending suggestion task
            if (debounceFuture != null && !debounceFuture.isDone()) {
                debounceFuture.cancel(false);
            }
            System.out.println("Scheduling suggestion for: " + newText);

            // Schedule a new task with delay
            debounceFuture = debounceScheduler.schedule(() -> {
                String latestText = codeArea.getText();
                int caretPosition = codeArea.getCaretPosition();

                try {
                    System.out.println("API Call for: " + latestText);
                    String context = latestText.substring(0,Math.min(caretPosition,latestText.length()));
                    String suggestion = suggestionService.getSuggestion(context).get();
                    System.out.println("Suggestion: " + suggestion);

                    if (suggestion != null && !suggestion.isBlank()) {
                        String ghost = "";

                        System.out.println("latestText: >>>" + latestText + "<<<");
                        System.out.println("suggestion: >>>" + suggestion + "<<<");
                        System.out.println("caretPosition: " + caretPosition);
                        System.out.println("latestText.substring(0, caretPosition): >>>" +
                                context + "<<<");


                        String normalizedSuggestion = normalize(suggestion);
                        String normalizedContext = normalize(context);

                        if (normalizedSuggestion.startsWith(normalizedContext)) {
                            // Calculate ghost using original suggestion's index (not normalized)
                            //System.out    (4 Spaces) =>Suggestion-> System.out.println(); -> ghost should be .println();
                            //Here suggestion.length is the trimmed length, to avoid extra spaces if in context
                            //ghost = suggestion.substring(Math.min(context.length(), suggestion.length()));
                            // Fix ghost start index calculation (handles tabs/spaces properly)
                            // Find ghost start index correctly
                            int ghostStartIndex = findGhostStartIndex(suggestion, normalizedContext);

                            // Clamp ghostStartIndex
                            ghostStartIndex = Math.min(Math.max(ghostStartIndex, context.length()), suggestion.length());

                            // Extract ghost text
                            ghost = suggestion.substring(ghostStartIndex);



                            // Clear ghost if invalid
                            if (ghost.isBlank()) {
                                ghost = "";
                            }
                        } else {
                            System.out.println("Suggestion structure doesn't match. Fallback to empty ghost.");
                            ghost = "";
                        }


//
//                        // Use caret position to extract the ghost part after the caret
//                        if (suggestion.startsWith(context)) {
//                            ghost = suggestion.substring(context.length());
//                        }
//                        //else if (caretPosition < suggestion.length() &&
//                            //    suggestion.startsWith(latestText.substring(0, caretPosition))) {
//
//                          //  ghost = suggestion.substring(caretPosition);
//                        //}
//                        else {
//                            System.out.println("Suggestion structure doesn't match. Fallback to empty ghost.");
//                            ghost = "";
//                        }

                        String finalGhost = ghost;
                        Platform.runLater(() -> {
                            if (codeArea.getText().equals(latestText)) {
                                // Only show ghost if caret is at end or user actually typed
                                if (caretPosition == latestText.length() || userTyped) {
                                    showGhostText(latestText, finalGhost);
                                } else {
                                    removeGhostText();
                                }
                            }
                            // reset flag after suggestion is handled
                            userTyped = false;
                        });
                    }
                } catch (Exception e) {
                    logger.error("Exception occurred", e);
                }
                finally {
                    userTyped=false;
                }
            }, 600, TimeUnit.MILLISECONDS);


        });
    }


    /** Align ghost indentation with the current line's indentation */
    private String alignIndentation(String ghost, int caretPosition, String latestText) {
        // Find the current line's indentation
        int lineStart = latestText.lastIndexOf('\n', caretPosition - 1) + 1;
        String lineIndentation = latestText.substring(lineStart, caretPosition)
                .replaceAll("\\S", ""); // Keep only spaces/tabs

        return lineIndentation + ghost.stripLeading();
    }


    /** Finds correct ghost start index in original suggestion handling the spaces/tabs */
    private int findGhostStartIndex(String suggestion, String normalizedContext) {
        String normalizedSuggestion = normalize(suggestion);

        if (normalizedSuggestion.startsWith(normalizedContext)) {
            int targetLength = normalizedContext.length();
            int count = 0;

            for (int i = 0; i < suggestion.length(); i++) {
                char c = suggestion.charAt(i);

                if (c == '\t') {
                    count += 1;
                } else if (c == ' ') {
                    if (i == 0 || suggestion.charAt(i - 1) != ' ') {
                        count++;
                    }
                } else {
                    count++;
                }

                if (count >= targetLength) return i + 1;
            }
        }
        return normalizedContext.length(); // fallback
    }


    // TO normalise extra whitespace
    private String normalize(String text) {
        return text.replace("\t", " ") // Replace tabs with spaces
                .replaceAll(" +", " ") // Collapse multiple spaces
                .trim();
    }




    public void showGhostText(String userText, String suggestion) {
        isUpdating = true;
        try {
            // Get the caret position where user is typing
            int caretPosition = codeArea.getCaretPosition();

            // Split the full text into two parts: before and after caret
            String beforeCaret = codeArea.getText(0, caretPosition);
            String afterCaret = codeArea.getText(caretPosition, codeArea.getLength());

            // Combine: user typed + suggestion + rest of old content
            String combined = beforeCaret + suggestion + afterCaret;

            // Styling: mark user typed part as normal, suggestion as ghost, rest as normal
            StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
            spansBuilder.add(Collections.singleton("normal"), beforeCaret.length());
            spansBuilder.add(Collections.singleton("ghost-text"), suggestion.length());
            spansBuilder.add(Collections.singleton("normal"), afterCaret.length());

            // Replace full text
            codeArea.replaceText(combined);
            codeArea.setStyleSpans(0, spansBuilder.create());

            // Move caret to just after user's typed input (not at end of suggestion)
            codeArea.moveTo(beforeCaret.length());

            // Set state
            currentSuggestion = suggestion;
            isGhostVisible = true;
        } finally {
            isUpdating = false;
        }
    }


    private void acceptGhostSuggestion() {
        if (!isGhostVisible || currentSuggestion.isEmpty()) return;

        // Block our ghost‐listener while we do the replace
        isUpdating = true;

        try {
            String suggestion = currentSuggestion;
            int totalLen = codeArea.getLength();
            int ghostStart = totalLen - suggestion.length();

            // Replace *just* the ghost span with the real text
            codeArea.replaceText(
                    ghostStart,
                    ghostStart + suggestion.length(),
                    suggestion
            );

            // Re‐style the entire document as “normal”
            StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>();
            spans.add(Collections.singleton("normal"), codeArea.getLength());
            codeArea.setStyleSpans(0, spans.create());

            // Move caret to end of the inserted text
            codeArea.moveTo(codeArea.getLength());
        } finally {
            // Reset all our flags
            isUpdating = false;
            isGhostVisible = false;
            currentSuggestion = "";
        }
    }


    private void removeGhostText() {
        if (!isGhostVisible) return;

        String fullText = codeArea.getText();
        if (fullText.endsWith(currentSuggestion)) {
            isUpdating = true;
            try {

                int ghostStart = fullText.length() - currentSuggestion.length();
                if (ghostStart < 0) return;

                String realText = fullText.substring(0, ghostStart);
                codeArea.replaceText(realText);

                StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
                spansBuilder.add(Collections.singleton("normal"), realText.length());
                codeArea.setStyleSpans(0, spansBuilder.create());

            } finally {
                isUpdating = false;
            }
        }

        isGhostVisible = false;
        currentSuggestion = "";
    }

}


