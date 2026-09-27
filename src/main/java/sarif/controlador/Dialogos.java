package sarif.controlador;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.GridPane;

import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Optional;

/**
 * Mensajes al operador comunes a todos los controladores.
 * <p>
 * Junto acá los cuadros de diálogo y los formatos de fecha para que todas las pantallas se vean y
 * se comporten igual: mismo título, mismo tipo de ícono y mismas fechas en formato argentino
 * (día/mes/año). Si querés cambiar un mensaje general, lo cambiás en un solo lugar.
 */
final class Dialogos {

    // Formatos de fecha que uso en las tablas y en los textos de las pantallas.
    static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    // Clase utilitaria: no se instancia.
    private Dialogos() {
    }

    /** Mensaje informativo, por ejemplo cuando una operación terminó bien. */
    static void info(String mensaje) {
        mostrar(Alert.AlertType.INFORMATION, "SARIF", mensaje);
    }

    /** Aviso para errores de validación o de uso (falta seleccionar algo, dato inválido, etc.). */
    static void aviso(String mensaje) {
        mostrar(Alert.AlertType.WARNING, "Revise los datos", mensaje);
    }

    /** Error de base de datos: muestro un texto entendible y agrego el detalle técnico debajo. */
    static void errorBase(SQLException e) {
        mostrar(Alert.AlertType.ERROR, "Error de base de datos",
                "No se pudo completar la operación con la base de datos.\n" + e.getMessage());
    }

    /**
     * Pregunta de confirmación antes de una operación que modifica datos. Devuelve true solo si el
     * operador aprieta Aceptar; cerrar el diálogo con la cruz lo tomo como Cancelar.
     */
    static boolean confirmar(String mensaje) {
        Alert alerta = new Alert(Alert.AlertType.CONFIRMATION, mensaje, ButtonType.OK, ButtonType.CANCEL);
        alerta.setTitle("Confirmar");
        alerta.setHeaderText(null);
        return alerta.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    /**
     * Diálogo con un campo de clave por cada etiqueta (por ejemplo "Clave actual", "Clave nueva",
     * "Repetir clave nueva"). Uso PasswordField para que lo escrito no se vea en pantalla. Devuelve los
     * textos en el mismo orden, o vacío si se canceló.
     */
    static Optional<String[]> claves(String titulo, String explicacion, String... etiquetas) {
        Dialog<String[]> dialogo = new Dialog<>();
        dialogo.setTitle(titulo);
        dialogo.setHeaderText(explicacion);
        dialogo.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        GridPane grilla = new GridPane();
        grilla.setHgap(10);
        grilla.setVgap(8);
        PasswordField[] campos = new PasswordField[etiquetas.length];
        for (int i = 0; i < etiquetas.length; i++) {
            campos[i] = new PasswordField();
            campos[i].setPrefWidth(220);
            grilla.addRow(i, new Label(etiquetas[i]), campos[i]);
        }
        dialogo.getDialogPane().setContent(grilla);
        dialogo.setOnShown(e -> campos[0].requestFocus());
        dialogo.setResultConverter(boton -> boton == ButtonType.OK
                ? Arrays.stream(campos).map(PasswordField::getText).toArray(String[]::new)
                : null);
        return dialogo.showAndWait();
    }

    /** Arma y muestra un diálogo modal simple, sin encabezado, con un único botón Aceptar. */
    private static void mostrar(Alert.AlertType tipo, String titulo, String mensaje) {
        Alert alerta = new Alert(tipo, mensaje, ButtonType.OK);
        alerta.setTitle(titulo);
        alerta.setHeaderText(null);
        alerta.showAndWait();
    }
}
