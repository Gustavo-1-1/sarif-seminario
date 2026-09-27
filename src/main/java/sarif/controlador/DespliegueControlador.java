package sarif.controlador;

import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.util.StringConverter;
import sarif.modelo.Asignacion;
import sarif.modelo.Permiso;
import sarif.modelo.Recurso;
import sarif.modelo.ZonaParaAsignar;
import sarif.servicio.ServicioDespliegue;
import sarif.servicio.Sesion;
import sarif.servicio.ValidacionException;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Controlador de la pestaña "Despliegue": CU10 Sugerir despliegue preventivo y CU11 Registrar asignación.
 * <p>
 * El flujo tiene dos pasos a propósito. Primero el servicio arma una sugerencia en memoria (qué recurso
 * mandar a qué zona según el índice de riesgo del día) y yo la muestro en la tabla; hasta ahí no se
 * toca la base. El operador la ajusta (cambia la zona de una fila, agrega una asignación a mano o quita
 * filas) y recién cuando confirma se registran las asignaciones. Lo hice así porque la decisión final
 * es del jefe de guardia, no del sistema.
 */
public class DespliegueControlador {

    // Componentes inyectados desde Despliegue.fxml (el nombre coincide con el fx:id).
    @FXML
    private Label lblMensaje;
    @FXML
    private TableView<Asignacion> tabla;
    @FXML
    private TableColumn<Asignacion, String> colZona;
    @FXML
    private TableColumn<Asignacion, Double> colIndice;
    @FXML
    private TableColumn<Asignacion, String> colNivel;
    @FXML
    private TableColumn<Asignacion, String> colRecurso;
    @FXML
    private TableColumn<Asignacion, String> colTipo;
    @FXML
    private TableColumn<Asignacion, Integer> colDotacion;
    @FXML
    private TableColumn<Asignacion, String> colMotivo;
    @FXML
    private ComboBox<ZonaParaAsignar> cmbZona;
    @FXML
    private ComboBox<Recurso> cmbRecurso;
    @FXML
    private Button btnConfirmar;
    @FXML
    private Label lblPermisoAsignar;

    // Solo uso el servicio; el controlador nunca llama a un DAO directamente (MVC, RNF04).
    private final ServicioDespliegue servicio = new ServicioDespliegue();

    /**
     * Armo las columnas con lambdas porque Asignacion es un record (accesores nombreZona(), indice()...,
     * sin prefijo get) y PropertyValueFactory no los encuentra. Algunas columnas navegan al recurso
     * asociado para mostrar su denominación, tipo y dotación. A los combos les doy un StringConverter
     * para que muestren un texto legible en lugar del toString() del record.
     */
    @FXML
    private void initialize() {
        Restricciones.aplicar(Permiso.ASIGNAR_RECURSOS, lblPermisoAsignar, btnConfirmar);
        colZona.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().nombreZona()));
        colIndice.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().indice()));
        colNivel.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().nivel()));
        // El nivel de riesgo va con color y texto (ver Celdas), igual que en el tablero.
        colNivel.setCellFactory(Celdas.etiqueta("nivel-"));
        colRecurso.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().recurso().denominacion()));
        colTipo.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().recurso().tipo().name()));
        colDotacion.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().recurso().dotacion()));
        colMotivo.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().motivo()));
        tabla.setPlaceholder(new Label("Presione \"Sugerir despliegue\" para armar la propuesta del día."));

        cmbZona.setConverter(texto(DespliegueControlador::textoZona));
        cmbZona.setPlaceholder(new Label("No hay zonas registradas."));
        cmbRecurso.setConverter(texto(r -> r.denominacion() + " (" + r.tipo().name() + ")"));
    }

    /**
     * Lo llama PrincipalControlador al entrar a la pestaña o al cambiar la fecha de trabajo. Le recuerdo
     * al operador que la sugerencia no se guarda hasta confirmarla y recargo los combos del ajuste.
     */
    public void actualizar() {
        lblMensaje.setText("Jornada del " + Sesion.getFechaTrabajo().format(Dialogos.FECHA)
                + ". La sugerencia no modifica la base hasta que se confirma.");
        cargarCombos();
    }

    /**
     * Botón "Sugerir despliegue" (CU10): pido al servicio la propuesta para la fecha de trabajo y la
     * cargo en la tabla. Es solo una lectura: no se escribe nada en la base.
     */
    @FXML
    private void sugerir() {
        try {
            List<Asignacion> sugerencia = servicio.sugerir(Sesion.getFechaTrabajo());
            tabla.setItems(FXCollections.observableArrayList(sugerencia));
            cargarCombos();
            List<ZonaParaAsignar> conIndice = cmbZona.getItems().stream().filter(z -> z.indice() != null).toList();
            if (!sugerencia.isEmpty()) {
                lblMensaje.setText(sugerencia.size() + " recurso(s) sugerido(s). Puede ajustar o quitar filas antes de confirmar.");
            } else if (conIndice.isEmpty()) {
                lblMensaje.setText("No hay sugerencias: todavía no se calculó el índice del día. Puede asignar a mano "
                        + "eligiendo la zona y el recurso abajo.");
            } else if (cmbRecurso.getItems().isEmpty()) {
                // Flujo S3: sin recursos disponibles, igual muestro el orden de las zonas por riesgo.
                lblMensaje.setText("No hay recursos disponibles. Orden de las zonas por riesgo: "
                        + conIndice.stream().map(DespliegueControlador::textoZona).collect(Collectors.joining(" · ")));
            } else {
                lblMensaje.setText("No hay sugerencias: ninguna zona supera el nivel BAJO. Puede asignar a mano abajo.");
            }
        } catch (SQLException e) {
            Dialogos.errorBase(e);
        }
    }

    /** Botón "Cambiar zona de la fila": mando el recurso de la fila seleccionada a la zona elegida en el combo. */
    @FXML
    private void cambiarZona() {
        Asignacion fila = tabla.getSelectionModel().getSelectedItem();
        ZonaParaAsignar zona = cmbZona.getValue();
        if (fila == null || zona == null) {
            Dialogos.aviso("Seleccione una fila de la tabla y la zona a la que quiere enviar el recurso.");
            return;
        }
        int posicion = tabla.getItems().indexOf(fila);
        tabla.getItems().set(posicion, ServicioDespliegue.ajustar(fila.recurso(), zona, "Ajuste del operador"));
    }

    /** Botón "Agregar asignación": sumo a mano un recurso disponible que no esté ya en la tabla (flujo S3). */
    @FXML
    private void agregarAsignacion() {
        Recurso recurso = cmbRecurso.getValue();
        ZonaParaAsignar zona = cmbZona.getValue();
        if (recurso == null || zona == null) {
            Dialogos.aviso("Elija el recurso y la zona de la asignación.");
            return;
        }
        if (tabla.getItems().stream().anyMatch(a -> a.recurso().id() == recurso.id())) {
            Dialogos.aviso("El recurso " + recurso.denominacion() + " ya está en la propuesta.");
            return;
        }
        tabla.getItems().add(ServicioDespliegue.ajustar(recurso, zona, "Asignación manual"));
        cmbRecurso.getSelectionModel().clearSelection();
    }

    /**
     * Botón "Quitar fila": el operador descarta una asignación sugerida. Como la sugerencia vive solo
     * en la lista de la tabla, alcanza con sacarla de ahí.
     */
    @FXML
    private void quitarFila() {
        Asignacion seleccionada = tabla.getSelectionModel().getSelectedItem();
        if (seleccionada == null) {
            Dialogos.aviso("Seleccione la fila que desea quitar.");
            return;
        }
        tabla.getItems().remove(seleccionada);
    }

    /**
     * Botón "Confirmar asignaciones" (CU11): después de pedir confirmación, le paso al servicio las filas
     * que quedaron para que las registre y marque los recursos como asignados.
     */
    @FXML
    private void confirmar() {
        // Copio la lista para no depender de la lista observable de la tabla, que después limpio.
        List<Asignacion> asignaciones = new ArrayList<>(tabla.getItems());
        if (asignaciones.isEmpty()) {
            Dialogos.aviso("No hay asignaciones para confirmar.");
            return;
        }
        if (!Dialogos.confirmar("¿Confirmar " + asignaciones.size() + " asignación(es)? Los recursos quedarán asignados.")) {
            return;
        }
        try {
            servicio.confirmar(asignaciones, Sesion.getUsuario().id(), Sesion.getFechaTrabajo());
            tabla.getItems().clear();
            lblMensaje.setText(asignaciones.size() + " asignación(es) registradas.");
            cargarCombos();
        } catch (ValidacionException e) {
            // El servicio vuelve a validar la lista por su cuenta; si algo no cierra, lo muestro como aviso.
            Dialogos.aviso(e.getMessage());
        } catch (SQLException e) {
            Dialogos.errorBase(e);
        }
    }

    /** Cargo todas las zonas (con su índice del día, si hay) y los recursos disponibles en los combos del ajuste manual. */
    private void cargarCombos() {
        try {
            cmbZona.setItems(FXCollections.observableArrayList(servicio.zonasParaAjuste(Sesion.getFechaTrabajo())));
            cmbRecurso.setItems(FXCollections.observableArrayList(servicio.recursosDisponibles()));
        } catch (SQLException e) {
            Dialogos.errorBase(e);
        }
    }

    // "Caviahue - Copahue (0.82, EXTREMO)", o "prueba (sin índice del día · alerta EMERGENCIA)".
    private static String textoZona(ZonaParaAsignar z) {
        String alerta = z.nivelAlerta() == null ? "" : " · alerta " + z.nivelAlerta();
        return z.indice() == null
                ? z.nombreZona() + " (sin índice del día" + alerta + ")"
                : String.format(Locale.ROOT, "%s (%.2f, %s%s)", z.nombreZona(), z.indice().valorFinal(), z.indice().nivel(), alerta);
    }

    /** Armo un StringConverter de solo ida (los combos no son editables, así que fromString no se usa). */
    private static <T> StringConverter<T> texto(java.util.function.Function<T, String> formato) {
        return new StringConverter<>() {
            @Override
            public String toString(T valor) {
                return valor == null ? "" : formato.apply(valor);
            }

            @Override
            public T fromString(String texto) {
                return null;
            }
        };
    }
}
