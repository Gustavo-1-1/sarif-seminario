package sarif.controlador;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import sarif.App;
import sarif.servicio.ServicioAutenticacion;
import sarif.servicio.ValidacionException;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Controlador de la ventana de ingreso (RFS01).
 * <p>
 * Toma usuario y clave, se los pasa a ServicioAutenticacion (que es quien consulta la base y deja
 * al usuario en la Sesion) y, si todo sale bien, le pide a App que muestre la ventana principal.
 * Los errores los muestro en una etiqueta dentro del mismo formulario en lugar de un diálogo, para
 * que el operador pueda corregir y volver a intentar sin cerrar nada.
 */
public class LoginControlador {

    // Componentes inyectados desde Login.fxml.
    @FXML
    private TextField txtUsuario;
    @FXML
    private PasswordField txtClave;
    @FXML
    private Label lblMensaje;

    private final ServicioAutenticacion servicio = new ServicioAutenticacion();

    /**
     * Apenas se abre la pantalla verifico si hay conexión con la base, así el operador se entera antes
     * de escribir sus datos y sabe dónde revisar la configuración.
     */
    @FXML
    private void initialize() {
        if (!servicio.baseDisponible()) {
            lblMensaje.setText("No hay conexión con la base de datos. Revise config/sarif.properties.");
        }
    }

    /**
     * Botón "Ingresar" (y también Enter en cualquiera de los dos campos, por el onAction del FXML).
     * Distingo tres casos de error: credenciales inválidas, falla de base y falla al cargar la vista.
     */
    @FXML
    private void ingresar() {
        try {
            servicio.ingresar(txtUsuario.getText(), txtClave.getText());
            App.mostrarPrincipal();
        } catch (ValidacionException e) {
            // Credenciales incorrectas o campos vacíos: borro la clave y dejo el foco ahí para reintentar.
            lblMensaje.setText(e.getMessage());
            txtClave.clear();
            txtClave.requestFocus();
        } catch (SQLException e) {
            lblMensaje.setText("No se pudo conectar con la base de datos.");
        } catch (IOException e) {
            lblMensaje.setText("No se pudo abrir la ventana principal: " + e.getMessage());
        }
    }
}
