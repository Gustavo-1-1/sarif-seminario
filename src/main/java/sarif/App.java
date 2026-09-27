package sarif;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.Objects;

/**
 * Punto de entrada de la aplicación de escritorio SARIF.
 * <p>
 * Extiendo {@link Application} porque así funciona JavaFX: el framework crea la ventana principal
 * (el {@link Stage}) y me la pasa en {@link #start(Stage)}. Uso una sola ventana durante toda la
 * ejecución y lo único que hago es cambiarle la escena: primero la de ingreso (RFS01) y, cuando el
 * usuario se autentica, la ventana principal con las pestañas.
 */
public class App extends Application {

    // Guardo la ventana en un atributo estático para poder cambiar de vista desde los controladores
    // (por ejemplo, LoginControlador llama a mostrarPrincipal() sin tener una referencia al Stage).
    private static Stage ventana;

    /** JavaFX llama a este método al arrancar; acá muestro la pantalla de ingreso. */
    @Override
    public void start(Stage stage) throws IOException {
        ventana = stage;
        mostrarIngreso();
        stage.show();
    }

    /** Muestra la ventana de ingreso (RFS01). La uso al iniciar y también al cerrar sesión. */
    public static void mostrarIngreso() throws IOException {
        cambiarVista("Login.fxml", "SARIF - Ingreso", false);
    }

    /** Muestra la ventana principal con las cinco pestañas, una vez que el usuario ingresó. */
    public static void mostrarPrincipal() throws IOException {
        cambiarVista("Principal.fxml", "SARIF - Sistema de Anticipación y Respuesta ante Incendios Forestales", true);
    }

    /**
     * Carga un FXML de la carpeta vista, le aplico la hoja de estilos común y lo pongo como escena de la
     * ventana. Centralizo esto acá para no repetir en cada lugar la carga del FXML y del CSS.
     * La ventana de ingreso la dejo de tamaño fijo y la principal redimensionable.
     */
    private static void cambiarVista(String fxml, String titulo, boolean redimensionable) throws IOException {
        // Uso Objects.requireNonNull para que, si me equivoco en el nombre del recurso, falle enseguida
        // con un mensaje claro en lugar de un NullPointerException más adelante.
        Parent raiz = FXMLLoader.load(Objects.requireNonNull(App.class.getResource("vista/" + fxml)));
        Scene escena = new Scene(raiz);
        escena.getStylesheets().add(Objects.requireNonNull(App.class.getResource("vista/sarif.css")).toExternalForm());
        ventana.setScene(escena);
        ventana.setTitle(titulo);
        ventana.setResizable(redimensionable);
        // Ajusto la ventana al tamaño preferido del FXML nuevo y la centro en la pantalla.
        ventana.sizeToScene();
        ventana.centerOnScreen();
    }

    /** launch() inicializa el toolkit de JavaFX y termina llamando a start(). */
    public static void main(String[] args) {
        launch(args);
    }
}
