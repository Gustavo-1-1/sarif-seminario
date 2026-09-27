package sarif.controlador;

import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import sarif.modelo.ActivoProtegido;
import sarif.modelo.EstadoRecurso;
import sarif.modelo.Recurso;
import sarif.modelo.TipoRecurso;
import sarif.modelo.Zona;
import sarif.servicio.ServicioRecursos;
import sarif.servicio.ServicioZonas;
import sarif.servicio.ValidacionException;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Controlador de la pestaña "Activos y recursos": CU02 Registrar activo protegido y CU03 Registrar recurso.
 * <p>
 * Como en las otras pantallas, acá solo convierto los textos a números y muestro los resultados; las
 * reglas (que el activo caiga dentro de la zona, nombres repetidos, dotación válida) las controlan
 * ServicioZonas y ServicioRecursos (MVC, RNF04).
 */
public class ActivosRecursosControlador {

    // Activos protegidos.
    @FXML
    private TableView<ActivoProtegido> tablaActivos;
    @FXML
    private TableColumn<ActivoProtegido, String> colActZona;
    @FXML
    private TableColumn<ActivoProtegido, String> colActNombre;
    @FXML
    private TableColumn<ActivoProtegido, String> colActTipo;
    @FXML
    private TableColumn<ActivoProtegido, String> colActUbicacion;
    @FXML
    private TableColumn<ActivoProtegido, Double> colActDistancia;
    @FXML
    private ComboBox<Zona> cmbActZona;
    @FXML
    private TextField txtActNombre;
    @FXML
    private ComboBox<String> cmbActTipo;
    @FXML
    private TextField txtActLat;
    @FXML
    private TextField txtActLon;
    @FXML
    private TextField txtActDistancia;

    // Recursos de combate.
    @FXML
    private TableView<Recurso> tablaRecursos;
    @FXML
    private TableColumn<Recurso, String> colRecDenominacion;
    @FXML
    private TableColumn<Recurso, String> colRecTipo;
    @FXML
    private TableColumn<Recurso, Integer> colRecDotacion;
    @FXML
    private TableColumn<Recurso, String> colRecEstado;
    @FXML
    private TableColumn<Recurso, String> colRecBase;
    @FXML
    private TextField txtRecDenominacion;
    @FXML
    private ComboBox<TipoRecurso> cmbRecTipo;
    @FXML
    private TextField txtRecDotacion;
    @FXML
    private ComboBox<Zona> cmbRecBase;

    private final ServicioZonas servicioZonas = new ServicioZonas();
    private final ServicioRecursos servicioRecursos = new ServicioRecursos();
    // Nombre de cada zona por id, para mostrar el nombre en las tablas en lugar del número.
    private final Map<Integer, String> nombresZona = new HashMap<>();

    /** Armo las columnas con lambdas (records sin getX()) y cargo los combos que no dependen de la base. */
    @FXML
    private void initialize() {
        colActZona.setCellValueFactory(c -> new SimpleStringProperty(nombresZona.getOrDefault(c.getValue().idZona(), "—")));
        colActNombre.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().nombre()));
        colActTipo.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().tipo()));
        colActUbicacion.setCellValueFactory(c -> new SimpleStringProperty(
                String.format(Locale.ROOT, "%.4f, %.4f", c.getValue().latitud(), c.getValue().longitud())));
        colActDistancia.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().distanciaAlertaKm()));
        tablaActivos.setPlaceholder(new Label("No hay activos protegidos registrados."));

        colRecDenominacion.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().denominacion()));
        colRecTipo.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().tipo().name()));
        colRecDotacion.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().dotacion()));
        colRecEstado.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().estado().name()));
        colRecEstado.setCellFactory(Celdas.etiqueta("recurso-"));
        colRecBase.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().idZonaBase() == null ? "Sin base" : nombresZona.getOrDefault(c.getValue().idZonaBase(), "—")));
        tablaRecursos.setPlaceholder(new Label("No hay recursos registrados."));

        cmbActTipo.setItems(FXCollections.observableArrayList(ServicioZonas.TIPOS_ACTIVO));
        cmbRecTipo.setItems(FXCollections.observableArrayList(TipoRecurso.values()));
    }

    /** La llama PrincipalControlador al entrar a la pestaña: recargo zonas, activos y recursos. */
    public void actualizar() {
        try {
            List<Zona> zonas = servicioZonas.listarZonas();
            nombresZona.clear();
            zonas.forEach(z -> nombresZona.put(z.getId(), z.getNombre()));
            cmbActZona.setItems(FXCollections.observableArrayList(zonas));
            cmbRecBase.setItems(FXCollections.observableArrayList(zonas));
            tablaActivos.setItems(FXCollections.observableArrayList(servicioZonas.listarActivos()));
            tablaRecursos.setItems(FXCollections.observableArrayList(servicioRecursos.listar()));
        } catch (SQLException e) {
            Dialogos.errorBase(e);
        }
    }

    /** Botón "Registrar activo" (CU02). */
    @FXML
    private void registrarActivo() {
        Zona zona = cmbActZona.getValue();
        try {
            ActivoProtegido activo = new ActivoProtegido(0, zona == null ? 0 : zona.getId(), txtActNombre.getText(),
                    cmbActTipo.getValue(), numero(txtActLat, "latitud"), numero(txtActLon, "longitud"),
                    numero(txtActDistancia, "distancia de alerta"));
            servicioZonas.registrarActivo(activo);
            Dialogos.info("Activo \"" + activo.nombre().trim() + "\" registrado en " + zona + ".");
            for (TextField campo : new TextField[]{txtActNombre, txtActLat, txtActLon}) {
                campo.clear();
            }
            actualizar();
        } catch (ValidacionException e) {
            Dialogos.aviso(e.getMessage());
        } catch (SQLException e) {
            Dialogos.errorBase(e);
        }
    }

    /** Botón "Registrar recurso" (CU03). La dotación tiene que ser un número entero. */
    @FXML
    private void registrarRecurso() {
        int dotacion;
        try {
            dotacion = Integer.parseInt(txtRecDotacion.getText().trim());
        } catch (NumberFormatException e) {
            Dialogos.aviso("La dotación tiene que ser un número entero.");
            return;
        }
        Zona base = cmbRecBase.getValue();
        Recurso recurso = new Recurso(0, txtRecDenominacion.getText(), cmbRecTipo.getValue(), dotacion,
                EstadoRecurso.DISPONIBLE, base == null ? null : base.getId());
        try {
            servicioRecursos.registrarRecurso(recurso);
            Dialogos.info("Recurso \"" + recurso.denominacion().trim() + "\" registrado como disponible.");
            txtRecDenominacion.clear();
            txtRecDotacion.clear();
            cmbRecBase.getSelectionModel().clearSelection();
            actualizar();
        } catch (ValidacionException e) {
            Dialogos.aviso(e.getMessage());
        } catch (SQLException e) {
            Dialogos.errorBase(e);
        }
    }

    /** Botón "Marcar disponible": libero un recurso que volvió de una asignación o que se reparó. */
    @FXML
    private void marcarDisponible() {
        cambiarEstadoRecurso(EstadoRecurso.DISPONIBLE);
    }

    /** Botón "Fuera de servicio": el despliegue deja de sugerir este recurso. */
    @FXML
    private void marcarFueraDeServicio() {
        cambiarEstadoRecurso(EstadoRecurso.FUERA_DE_SERVICIO);
    }

    private void cambiarEstadoRecurso(EstadoRecurso nuevo) {
        Recurso recurso = tablaRecursos.getSelectionModel().getSelectedItem();
        if (recurso == null) {
            Dialogos.aviso("Seleccione un recurso de la lista.");
            return;
        }
        try {
            servicioRecursos.cambiarEstado(recurso, nuevo);
            actualizar();
        } catch (ValidacionException e) {
            Dialogos.aviso(e.getMessage());
        } catch (SQLException e) {
            Dialogos.errorBase(e);
        }
    }

    /** Convierte el texto de un campo a número, aceptando coma o punto decimal. */
    private static double numero(TextField campo, String nombre) throws ValidacionException {
        try {
            return Double.parseDouble(campo.getText().trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new ValidacionException("El valor de " + nombre + " no es un número válido.");
        }
    }
}
