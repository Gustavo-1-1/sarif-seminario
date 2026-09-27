package sarif.controlador;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import sarif.modelo.NivelAlerta;
import sarif.modelo.Permiso;
import sarif.modelo.TipoCombustible;
import sarif.modelo.Vertice;
import sarif.modelo.Zona;
import sarif.servicio.ServicioSincronizacion;
import sarif.servicio.ServicioZonas;
import sarif.servicio.Sesion;
import sarif.servicio.ValidacionException;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Controlador de la pestaña "Zonas": CU01 Registrar zona, CU04/CU05 Activar y desactivar vigilancia,
 * CU12 Actualizar nivel de alerta y los relevamientos de combustible posteriores al alta (RFS21).
 * <p>
 * A la izquierda está la lista de zonas y a la derecha dos formularios: el alta de una zona nueva y el
 * cambio de nivel de alerta de la zona seleccionada. Acá solo convierto los textos de los campos a
 * números; las reglas de negocio (nombre repetido, superposición de áreas, fundamento obligatorio)
 * las valida ServicioZonas, porque el controlador nunca habla con un DAO (MVC, RNF04).
 */
public class ZonasControlador {

    // Lista de zonas (lado izquierdo de Zonas.fxml).
    @FXML
    private TableView<Zona> tabla;
    @FXML
    private TableColumn<Zona, String> colNombre;
    @FXML
    private TableColumn<Zona, String> colVigilancia;
    @FXML
    private TableColumn<Zona, String> colForma;
    @FXML
    private TableColumn<Zona, String> colLatitudes;
    @FXML
    private TableColumn<Zona, String> colLongitudes;

    // Formulario "Nueva zona" (CU01).
    @FXML
    private TextField txtNombre;
    @FXML
    private TextField txtDescripcion;
    @FXML
    private TextField txtLatMin;
    @FXML
    private TextField txtLatMax;
    @FXML
    private TextField txtLonMin;
    @FXML
    private TextField txtLonMax;
    @FXML
    private ComboBox<TipoCombustible> cmbCombustible;
    @FXML
    private TextField txtCarga;
    @FXML
    private DatePicker dpRelevamiento;
    @FXML
    private HBox boxPoligono;
    @FXML
    private Label lblPoligono;
    // Vértices del polígono dibujado en el mapa; null si la zona nueva es un rectángulo cargado a mano.
    private List<Vertice> poligono;

    // Formulario "Nivel de alerta de la zona" (CU12).
    @FXML
    private Label lblZonaSeleccionada;
    @FXML
    private Label lblNivelVigente;
    @FXML
    private ComboBox<NivelAlerta> cmbNivel;
    @FXML
    private TextArea txtFundamento;
    @FXML
    private Button btnCambioNivel;
    @FXML
    private Label lblPermisoNivel;

    // Formulario "Relevamiento de combustible" (RFS21).
    @FXML
    private Label lblCombustibleVigente;
    @FXML
    private Label lblNdvi;
    @FXML
    private ComboBox<TipoCombustible> cmbRelTipo;
    @FXML
    private TextField txtRelCarga;
    @FXML
    private DatePicker dpRelFecha;
    @FXML
    private TextField txtRelObservaciones;

    private final ServicioZonas servicio = new ServicioZonas();
    private final ServicioSincronizacion servicioSincronizacion = new ServicioSincronizacion();

    /**
     * Armo las columnas y enlazo la selección de la tabla con el panel de nivel de alerta. A diferencia
     * de otras pantallas, Zona es una clase con getters (getNombre()), pero igual uso lambdas para
     * poder formatear las coordenadas y traducir el booleano de vigilancia a texto.
     */
    @FXML
    private void initialize() {
        Restricciones.aplicar(Permiso.CAMBIAR_NIVEL_ALERTA, lblPermisoNivel, cmbNivel, txtFundamento, btnCambioNivel);
        colNombre.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getNombre()));
        colVigilancia.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().isVigilanciaActiva() ? "Activa" : "Inactiva"));
        colVigilancia.setCellFactory(Celdas.etiqueta("vigilancia-"));
        colForma.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().esPoligono()
                ? "Polígono (" + c.getValue().getVertices().size() + ")" : "Rectángulo"));
        // Locale.ROOT para que el decimal salga siempre con punto, como se cargan las coordenadas.
        colLatitudes.setCellValueFactory(c -> new SimpleStringProperty(
                String.format(Locale.ROOT, "%.3f a %.3f", c.getValue().getLatitudMin(), c.getValue().getLatitudMax())));
        colLongitudes.setCellValueFactory(c -> new SimpleStringProperty(
                String.format(Locale.ROOT, "%.3f a %.3f", c.getValue().getLongitudMin(), c.getValue().getLongitudMax())));
        // Cada vez que el operador elige otra zona, actualizo el panel con su nivel de alerta vigente.
        tabla.getSelectionModel().selectedItemProperty().addListener((obs, anterior, zona) -> mostrarNivel(zona));
        tabla.setPlaceholder(new Label("No hay zonas registradas."));
        dpRelevamiento.setValue(LocalDate.now());
        dpRelFecha.setValue(LocalDate.now());
    }

    /**
     * Recarga la lista de zonas. La llama PrincipalControlador al entrar a la pestaña y yo la uso después
     * de cada cambio. Los combos los lleno una sola vez porque sus valores (combustibles y niveles) casi
     * no cambian, y después de recargar vuelvo a seleccionar la zona que estaba elegida.
     */
    public void actualizar() {
        Zona seleccionada = tabla.getSelectionModel().getSelectedItem();
        try {
            tabla.setItems(FXCollections.observableArrayList(servicio.listarZonas()));
            if (cmbCombustible.getItems().isEmpty()) {
                cmbCombustible.setItems(FXCollections.observableArrayList(servicio.tiposCombustible()));
                cmbRelTipo.setItems(cmbCombustible.getItems());
                cmbNivel.setItems(FXCollections.observableArrayList(servicio.nivelesAlerta()));
            }
            // Busco por id porque la lista nueva trae objetos distintos a los de antes.
            if (seleccionada != null) {
                tabla.getItems().stream().filter(z -> z.getId() == seleccionada.getId()).findFirst()
                        .ifPresent(z -> tabla.getSelectionModel().select(z));
            }
        } catch (SQLException e) {
            Dialogos.errorBase(e);
        }
    }

    /**
     * Botón "Registrar zona" (CU01). Primero convierto los campos numéricos (si alguno está mal, aviso y
     * corto) y después le paso todo al servicio, que valida y guarda la zona junto con su primer
     * relevamiento de combustible. La zona siempre nace con la vigilancia desactivada.
     */
    @FXML
    private void registrarZona() {
        Zona zona;
        double carga;
        try {
            // El id va en 0 porque lo asigna la base al insertar.
            zona = new Zona(0, txtNombre.getText(), txtDescripcion.getText().isBlank() ? null : txtDescripcion.getText().trim(),
                    numero(txtLatMin, "latitud mínima"), numero(txtLatMax, "latitud máxima"),
                    numero(txtLonMin, "longitud mínima"), numero(txtLonMax, "longitud máxima"), false);
            // Si vino un polígono del mapa, manda él: setVertices recalcula el rectángulo que lo encierra.
            zona.setVertices(poligono);
            carga = numero(txtCarga, "carga de combustible");
        } catch (ValidacionException e) {
            Dialogos.aviso(e.getMessage());
            return;
        }
        try {
            servicio.registrarZona(zona, cmbCombustible.getValue(), carga, dpRelevamiento.getValue(), Sesion.getUsuario().id());
            Dialogos.info("Zona \"" + zona.getNombre() + "\" registrada con la vigilancia desactivada.");
            limpiarFormulario();
            actualizar();
        } catch (ValidacionException e) {
            Dialogos.aviso(e.getMessage());
        } catch (SQLException e) {
            Dialogos.errorBase(e);
        }
    }

    /** Botón "Activar vigilancia" (CU04). */
    @FXML
    private void activarVigilancia() {
        cambiarVigilancia(true);
    }

    /** Botón "Desactivar vigilancia" (CU05). */
    @FXML
    private void desactivarVigilancia() {
        cambiarVigilancia(false);
    }

    /**
     * Botón "Registrar cambio de nivel" (CU12): cambia el nivel de alerta de la zona seleccionada.
     * El fundamento es obligatorio porque queda como registro de por qué se tomó la decisión.
     */
    @FXML
    private void registrarCambioNivel() {
        Zona zona = tabla.getSelectionModel().getSelectedItem();
        if (zona == null) {
            Dialogos.aviso("Seleccione una zona de la lista.");
            return;
        }
        try {
            servicio.cambiarNivelAlerta(zona.getId(), cmbNivel.getValue(), txtFundamento.getText(), Sesion.getUsuario().id());
            txtFundamento.clear();
            mostrarNivel(zona);
            Dialogos.info("Nivel de alerta de " + zona.getNombre() + " actualizado a " + cmbNivel.getValue() + ".");
        } catch (ValidacionException e) {
            Dialogos.aviso(e.getMessage());
        } catch (SQLException e) {
            Dialogos.errorBase(e);
        }
    }

    /**
     * Botón "Registrar relevamiento" (RFS21): agrega un relevamiento nuevo de combustible a la zona
     * seleccionada. El índice de los días siguientes va a usar este relevamiento en la componente C.
     */
    @FXML
    private void registrarRelevamiento() {
        Zona zona = tabla.getSelectionModel().getSelectedItem();
        if (zona == null) {
            Dialogos.aviso("Seleccione una zona de la lista.");
            return;
        }
        try {
            servicio.registrarRelevamiento(zona.getId(), cmbRelTipo.getValue(), numero(txtRelCarga, "carga de combustible"),
                    dpRelFecha.getValue(), txtRelObservaciones.getText(), Sesion.getUsuario().id());
            txtRelCarga.clear();
            txtRelObservaciones.clear();
            mostrarNivel(zona);
            Dialogos.info("Relevamiento de combustible de " + zona.getNombre() + " registrado.");
        } catch (ValidacionException e) {
            Dialogos.aviso(e.getMessage());
        } catch (SQLException e) {
            Dialogos.errorBase(e);
        }
    }

    /**
     * Código común de activar y desactivar. Antes de ir a la base controlo que la zona no esté ya en el
     * estado pedido, así el operador recibe un mensaje claro en vez de una operación que no cambia nada.
     */
    private void cambiarVigilancia(boolean activa) {
        Zona zona = tabla.getSelectionModel().getSelectedItem();
        if (zona == null) {
            Dialogos.aviso("Seleccione una zona de la lista.");
            return;
        }
        if (zona.isVigilanciaActiva() == activa) {
            Dialogos.aviso("La vigilancia de la zona ya está " + (activa ? "activa." : "desactivada."));
            return;
        }
        try {
            servicio.cambiarVigilancia(zona.getId(), activa);
            actualizar();
        } catch (SQLException e) {
            Dialogos.errorBase(e);
        }
    }

    /** Muestra en el panel derecho el nombre de la zona elegida, su nivel de alerta y su combustible vigentes. */
    private void mostrarNivel(Zona zona) {
        if (zona == null) {
            lblZonaSeleccionada.setText("Seleccione una zona de la lista");
            lblNivelVigente.setText("—");
            lblCombustibleVigente.setText("—");
            lblNdvi.setText("—");
            return;
        }
        lblZonaSeleccionada.setText(zona.getNombre());
        try {
            // El servicio devuelve un Optional: si la zona nunca tuvo nivel asignado, muestro "Sin nivel".
            lblNivelVigente.setText(servicio.nivelAlertaVigente(zona.getId()).map(NivelAlerta::nombre).orElse("Sin nivel"));
            lblCombustibleVigente.setText(servicio.combustibleVigente(zona.getId(), LocalDate.now())
                    .map(r -> String.format(Locale.ROOT, "%s, %.1f t/ha (%s)", r.tipo().nombre(), r.cargaTHa(),
                            r.fecha().format(Dialogos.FECHA)))
                    .orElse("Sin relevamiento"));
            // El NDVI lo busco hasta la fecha de trabajo, así al revisar una jornada pasada veo el de ese momento.
            lblNdvi.setText(servicioSincronizacion.ndviVigente(zona.getId(), Sesion.getFechaTrabajo())
                    .map(r -> String.format(Locale.ROOT, "%.2f (%s), imagen del %s con %.0f %% despejado",
                            r.ndviMedio(), ServicioSincronizacion.describirNdvi(r.ndviMedio()),
                            r.fechaImagen().format(Dialogos.FECHA), r.porcentajeValido()))
                    .orElse("Sin datos (sincronizar NDVI)"));
        } catch (SQLException e) {
            Dialogos.errorBase(e);
        }
    }

    /**
     * Completo las coordenadas del alta con el rectángulo que se marcó en el mapa. El resto de los datos
     * (nombre, combustible, carga) los carga el operador, y la validación es la misma de siempre.
     */
    public void precargarArea(double latMin, double latMax, double lonMin, double lonMax) {
        quitarPoligono();
        mostrarRectangulo(latMin, latMax, lonMin, lonMax);
        txtNombre.requestFocus();
    }

    /**
     * Lo mismo, pero con el polígono dibujado en el mapa. Muestro en los campos el rectángulo que lo encierra
     * (solo como referencia, no se pueden editar) y un aviso con la cantidad de vértices. Si el operador se
     * arrepiente, "Quitar" vuelve al rectángulo cargado a mano.
     */
    public void precargarPoligono(List<Vertice> vertices) {
        Zona referencia = new Zona();
        referencia.setVertices(vertices);
        poligono = List.copyOf(vertices);
        mostrarRectangulo(referencia.getLatitudMin(), referencia.getLatitudMax(), referencia.getLongitudMin(), referencia.getLongitudMax());
        for (TextField campo : new TextField[]{txtLatMin, txtLatMax, txtLonMin, txtLonMax}) {
            campo.setEditable(false);
        }
        lblPoligono.setText("Polígono de " + vertices.size() + " vértices dibujado en el mapa");
        boxPoligono.setVisible(true);
        boxPoligono.setManaged(true);
        txtNombre.requestFocus();
    }

    /** Botón "Quitar": descarto el polígono y los campos de coordenadas vuelven a ser editables. */
    @FXML
    private void quitarPoligono() {
        poligono = null;
        for (TextField campo : new TextField[]{txtLatMin, txtLatMax, txtLonMin, txtLonMax}) {
            campo.setEditable(true);
        }
        boxPoligono.setVisible(false);
        boxPoligono.setManaged(false);
    }

    private void mostrarRectangulo(double latMin, double latMax, double lonMin, double lonMax) {
        txtLatMin.setText(String.format(Locale.ROOT, "%.4f", latMin));
        txtLatMax.setText(String.format(Locale.ROOT, "%.4f", latMax));
        txtLonMin.setText(String.format(Locale.ROOT, "%.4f", lonMin));
        txtLonMax.setText(String.format(Locale.ROOT, "%.4f", lonMax));
    }

    /** Deja el formulario de alta vacío para cargar otra zona. La fecha de relevamiento la conservo. */
    private void limpiarFormulario() {
        for (TextField campo : new TextField[]{txtNombre, txtDescripcion, txtLatMin, txtLatMax, txtLonMin, txtLonMax, txtCarga}) {
            campo.clear();
        }
        cmbCombustible.getSelectionModel().clearSelection();
        quitarPoligono();
    }

    /**
     * Convierte el texto de un campo a número. Acepta coma o punto decimal, porque en Argentina es
     * habitual escribir "15,5" y Double.parseDouble solo entiende el punto. Si el texto no es un número,
     * lanzo ValidacionException con el nombre del campo para que el operador sepa cuál corregir.
     */
    private static double numero(TextField campo, String nombre) throws ValidacionException {
        try {
            return Double.parseDouble(campo.getText().trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new ValidacionException("El valor de " + nombre + " no es un número válido.");
        }
    }
}
