package sarif.controlador;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import sarif.modelo.Permiso;
import sarif.modelo.Rol;
import sarif.modelo.Usuario;
import sarif.servicio.ServicioUsuarios;
import sarif.servicio.Sesion;
import sarif.servicio.ValidacionException;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Controlador de la pestaña "Usuarios" (solo administrador): alta de usuarios, cambio de rol, activar o
 * desactivar y restablecer la clave. Las reglas (nombre de usuario, largo de la clave, no quedarse sin
 * administradores) las controla ServicioUsuarios; acá solo leo la pantalla y muestro el resultado.
 */
public class UsuariosControlador {

    @FXML
    private TableView<Usuario> tabla;
    @FXML
    private TableColumn<Usuario, String> colUsuario;
    @FXML
    private TableColumn<Usuario, String> colNombre;
    @FXML
    private TableColumn<Usuario, String> colRol;
    @FXML
    private TableColumn<Usuario, String> colEstado;
    @FXML
    private ComboBox<Rol> cmbRolSeleccionado;
    @FXML
    private Button btnActivo;
    @FXML
    private TextField txtUsuario;
    @FXML
    private TextField txtNombre;
    @FXML
    private TextField txtApellido;
    @FXML
    private ComboBox<Rol> cmbRol;
    @FXML
    private PasswordField txtClave;
    @FXML
    private PasswordField txtClaveRepetida;
    @FXML
    private Label lblPermisos;

    private final ServicioUsuarios servicio = new ServicioUsuarios();

    @FXML
    private void initialize() {
        colUsuario.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().nombreUsuario()));
        colNombre.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().nombreCompleto()));
        colRol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().rolTexto()));
        colEstado.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().activo() ? "ACTIVO" : "DESACTIVADO"));
        colEstado.setCellFactory(Celdas.etiqueta("usuario-"));
        tabla.setPlaceholder(new Label("No hay usuarios."));
        cmbRol.setItems(FXCollections.observableArrayList(Rol.values()));
        cmbRol.setValue(Rol.OPERADOR);
        cmbRolSeleccionado.setItems(FXCollections.observableArrayList(Rol.values()));
        // Al elegir un usuario, preparo el combo con su rol y el botón con la acción que corresponde.
        tabla.getSelectionModel().selectedItemProperty().addListener((obs, anterior, u) -> {
            cmbRolSeleccionado.setValue(u == null ? null : Rol.desde(u.rol()));
            btnActivo.setText(u != null && !u.activo() ? "Activar" : "Desactivar");
        });
        lblPermisos.setText(resumenPermisos());
    }

    /** La llama PrincipalControlador al entrar a la pestaña. Mantengo seleccionado el mismo usuario. */
    public void actualizar() {
        Usuario seleccionado = tabla.getSelectionModel().getSelectedItem();
        try {
            tabla.setItems(FXCollections.observableArrayList(servicio.listar(idAdministrador())));
            if (seleccionado != null) {
                tabla.getItems().stream().filter(u -> u.id() == seleccionado.id()).findFirst()
                        .ifPresent(u -> tabla.getSelectionModel().select(u));
            }
        } catch (ValidacionException e) {
            Dialogos.aviso(e.getMessage());
        } catch (SQLException e) {
            Dialogos.errorBase(e);
        }
    }

    @FXML
    private void registrar() {
        if (!txtClave.getText().equals(txtClaveRepetida.getText())) {
            Dialogos.aviso("La clave y su repetición no coinciden.");
            return;
        }
        Rol rol = cmbRol.getValue();
        Usuario nuevo = new Usuario(0, txtUsuario.getText(), txtNombre.getText(), txtApellido.getText(),
                rol == null ? null : rol.name(), true);
        try {
            servicio.crear(nuevo, txtClave.getText(), idAdministrador());
            Dialogos.info("Usuario " + nuevo.nombreUsuario().trim() + " registrado con el rol " + rol.texto()
                    + ". Conviene que cambie la clave en su primer ingreso (botón \"Cambiar clave\" de la barra superior).");
            for (TextField campo : new TextField[]{txtUsuario, txtNombre, txtApellido, txtClave, txtClaveRepetida}) {
                campo.clear();
            }
            cmbRol.setValue(Rol.OPERADOR);
            actualizar();
        } catch (ValidacionException e) {
            Dialogos.aviso(e.getMessage());
        } catch (SQLException e) {
            Dialogos.errorBase(e);
        }
    }

    @FXML
    private void cambiarRol() {
        Usuario u = seleccionado();
        if (u == null) {
            return;
        }
        Rol rol = cmbRolSeleccionado.getValue();
        if (rol != null && !Dialogos.confirmar("¿Cambiar el rol de " + u.nombreUsuario() + " a " + rol.texto() + "?")) {
            return;
        }
        try {
            servicio.cambiarRol(u.id(), rol, idAdministrador());
            actualizar();
        } catch (ValidacionException e) {
            Dialogos.aviso(e.getMessage());
        } catch (SQLException e) {
            Dialogos.errorBase(e);
        }
    }

    /** Un solo botón para activar o desactivar: hace lo contrario del estado actual del usuario elegido. */
    @FXML
    private void cambiarActivo() {
        Usuario u = seleccionado();
        if (u == null) {
            return;
        }
        boolean activar = !u.activo();
        if (!activar && !Dialogos.confirmar("¿Desactivar a " + u.nombreUsuario() + "? No va a poder ingresar a SARIF, "
                + "pero todo lo que registró queda guardado a su nombre.")) {
            return;
        }
        try {
            servicio.cambiarActivo(u.id(), activar, idAdministrador());
            actualizar();
        } catch (ValidacionException e) {
            Dialogos.aviso(e.getMessage());
        } catch (SQLException e) {
            Dialogos.errorBase(e);
        }
    }

    @FXML
    private void restablecerClave() {
        Usuario u = seleccionado();
        if (u == null) {
            return;
        }
        Dialogos.claves("Restablecer clave", "Clave nueva para " + u.nombreCompleto() + " (" + u.nombreUsuario() + ")",
                "Clave nueva", "Repetir clave").ifPresent(c -> {
            if (!c[0].equals(c[1])) {
                Dialogos.aviso("La clave y su repetición no coinciden.");
                return;
            }
            try {
                servicio.restablecerClave(u.id(), c[0], idAdministrador());
                Dialogos.info("Clave restablecida. Avisale a " + u.nombre() + " que la cambie al ingresar.");
            } catch (ValidacionException e) {
                Dialogos.aviso(e.getMessage());
            } catch (SQLException e) {
                Dialogos.errorBase(e);
            }
        });
    }

    private Usuario seleccionado() {
        Usuario u = tabla.getSelectionModel().getSelectedItem();
        if (u == null) {
            Dialogos.aviso("Seleccione un usuario de la lista.");
        }
        return u;
    }

    private static int idAdministrador() {
        return Sesion.getUsuario().id();
    }

    /** Texto de la tarjeta "Qué puede hacer cada rol", armado desde Rol y Permiso. */
    private static String resumenPermisos() {
        List<String> lineas = new ArrayList<>();
        for (Rol rol : Rol.values()) {
            lineas.add(rol.texto() + ": " + Arrays.stream(Permiso.values())
                    .filter(rol::puede)
                    .map(Permiso::descripcion)
                    .collect(Collectors.joining(", ")) + ".");
        }
        return String.join("\n\n", lineas);
    }
}
