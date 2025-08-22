module com.example.javafxnotepad {
    requires javafx.controls;
    requires javafx.fxml;
    requires org.fxmisc.richtext;
    requires com.google.gson;
    requires okhttp3;
    requires java.logging;
    requires org.slf4j;

    opens com.example.javafxnotepad to javafx.fxml;

    exports com.example.javafxnotepad;
}
