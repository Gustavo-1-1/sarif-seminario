package sarif.controlador;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.util.StringConverter;
import sarif.modelo.DireccionViento;
import sarif.modelo.Permiso;
import sarif.modelo.RegistroFwi;
import sarif.modelo.RegistroMeteo;
import sarif.modelo.Zona;
import sarif.servicio.ServicioPronostico;
import sarif.servicio.ServicioSincronizacion;
import sarif.servicio.ServicioZonas;
import sarif.servicio.Sesion;
import sarif.servicio.ValidacionException;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Controlador de la pestaña "Pronóstico": muestra día por día el pronóstico extendido de la zona elegida,
 * desde el día anterior a la fecha de trabajo, y un resumen con lo que importa para la propagación de un
 * incendio. Los datos los arma ServicioPronostico; la actualización desde Open-Meteo corre en un hilo
 * aparte para que la ventana no se congele mientras responde el servicio.
 */
public class PronosticoControlador {

    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("EEE dd/MM", new Locale("es", "AR"));

    @FXML
    private Label lblTitulo;
    @FXML
    private Label lblCarga;
    @FXML
    private Label lblPermiso;
    @FXML
    private Label lblResumen;
    @FXML
    private Label lblMensaje;
    @FXML
    private ComboBox<Zona> cmbZona;
    @FXML
    private Button btnActualizar;
    @FXML
    private TableView<RegistroMeteo> tabla;
    @FXML
    private TableColumn<RegistroMeteo, String> colFecha, colTipo, colTemperatura, colHumedad, colViento, colRafaga,
            colDireccion, colLluvia, colProbLluvia, colSuelo, colEvapotranspiracion, colFwi;

    private final ServicioPronostico servicio = new ServicioPronostico();
    private final ServicioZonas servicioZonas = new ServicioZonas();

    /** FWI de GWIS por fecha de la zona mostrada; lo carga el servicio junto con el pronóstico. */
    private Map<LocalDate, RegistroFwi> fwi = Map.of();

    @FXML
    private void initialize() {
        cmbZona.setConverter(new StringConverter<>() {
            @Override
            public String toString(Zona z) {
                return z == null ? "" : z.getNombre();
            }

            @Override
            public Zona fromString(String texto) {
                return null;
            }
        });
        cmbZona.valueProperty().addListener((obs, a, z) -> mostrar());
        columna(colFecha, r -> r.fecha().format(DIA));
        columna(colTipo, r -> r.esPronostico() ? "Pronóstico" : "Observado");
        columna(colTemperatura, r -> uno(r.temperaturaMaxC()) + " / " + (r.temperaturaMinC() == null ? "—" : uno(r.temperaturaMinC())));
        columna(colHumedad, r -> cero(r.humedadMinPct()));
        columna(colViento, r -> cero(r.vientoMaxKmh()));
        columna(colRafaga, r -> r.rafagaMaxKmh() == null ? "—" : cero(r.rafagaMaxKmh()));
        columna(colDireccion, r -> r.direccionVientoGrados() == null ? "—" : DireccionViento.describir(r.direccionVientoGrados()));
        columna(colLluvia, r -> uno(r.precipitacionMm()));
        columna(colProbLluvia, r -> r.probPrecipitacionPct() == null ? "—" : cero(r.probPrecipitacionPct()));
        columna(colSuelo, r -> r.humedadSueloPct() == null ? "—" : uno(r.humedadSueloPct()));
        columna(colEvapotranspiracion, r -> r.evapotranspiracionMm() == null ? "—" : uno(r.evapotranspiracionMm()));
        // El FWI no viene en el registro meteorológico: es de otro servicio y lo busco por fecha.
        columna(colFwi, r -> {
            RegistroFwi f = fwi.get(r.fecha());
            return f == null ? "—" : uno(f.valor()) + " · " + f.clase();
        });
        // Resalto los días de la regla 30-30-30. Las filas se reciclan al hacer scroll: siempre saco la clase antes.
        tabla.setRowFactory(t -> new TableRow<>() {
            @Override
            protected void updateItem(RegistroMeteo r, boolean vacia) {
                super.updateItem(r, vacia);
                getStyleClass().remove("fila-critica");
                if (!vacia && r != null && r.cumpleRegla30()) {
                    getStyleClass().add("fila-critica");
                }
            }
        });
        tabla.setPlaceholder(new Label("No hay datos meteorológicos de la zona para estas fechas. "
                + "Con la fecha de trabajo de hoy, \"Actualizar pronóstico\" trae los próximos 16 días."));
        Restricciones.aplicar(Permiso.OPERAR, lblPermiso, btnActualizar);
    }

    /** La llama PrincipalControlador al entrar a la pestaña o cambiar la fecha de trabajo. */
    public void actualizar() {
        lblTitulo.setText("Pronóstico y viento — desde el " + Sesion.getFechaTrabajo().minusDays(1).format(Dialogos.FECHA));
        try {
            Zona elegida = cmbZona.getValue();
            cmbZona.getItems().setAll(servicioZonas.listarZonas());
            Zona zona = elegida == null ? null
                    : cmbZona.getItems().stream().filter(z -> z.getId() == elegida.getId()).findFirst().orElse(null);
            if (zona == null && !cmbZona.getItems().isEmpty()) {
                zona = cmbZona.getItems().get(0);
            }
            // Si la zona no cambió, el listener no se dispara: por eso muestro igual.
            cmbZona.setValue(zona);
            mostrar();
        } catch (SQLException e) {
            Dialogos.errorBase(e);
        }
    }

    private void mostrar() {
        Zona zona = cmbZona.getValue();
        if (zona == null) {
            tabla.getItems().clear();
            lblResumen.setText("");
            lblCarga.setText("");
            return;
        }
        LocalDate fecha = Sesion.getFechaTrabajo();
        try {
            ServicioPronostico.Pronostico p = servicio.pronostico(zona.getId(), fecha);
            fwi = p.fwi();
            tabla.setPlaceholder(new Label("No hay datos meteorológicos de " + zona.getNombre() + " para estas fechas. "
                    + "Con la fecha de trabajo de hoy, \"Actualizar pronóstico\" trae los próximos 16 días"
                    + (zona.isVigilanciaActiva() ? "." : " (también de las zonas sin vigilancia activa).")));
            tabla.setItems(FXCollections.observableArrayList(p.dias()));
            lblResumen.setText(ServicioPronostico.resumen(p.dias(), fecha));
            lblCarga.setText(p.cargado() == null ? "La zona todavía no tiene pronósticos cargados."
                    : "Último pronóstico cargado el " + p.cargado().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                    + " (Open-Meteo, en el centro de la zona).");
        } catch (SQLException e) {
            Dialogos.errorBase(e);
        }
    }

    /** Botón "Actualizar pronóstico": consulta Open-Meteo en un hilo aparte y, al terminar, refresca la tabla. */
    @FXML
    private void actualizarPronostico() {
        int idUsuario = Sesion.getUsuario().id();
        Task<ServicioSincronizacion.Resultado> tarea = new Task<>() {
            @Override
            protected ServicioSincronizacion.Resultado call() throws Exception {
                return servicio.actualizar(idUsuario);
            }
        };
        btnActualizar.setDisable(true);
        lblMensaje.setText("Consultando Open-Meteo...");
        tarea.setOnSucceeded(e -> {
            btnActualizar.setDisable(false);
            ServicioSincronizacion.Resultado r = tarea.getValue();
            lblMensaje.setText((r.exitosa() ? "Pronóstico actualizado. " : "No se pudo actualizar. ") + r.detalle());
            mostrar();
        });
        tarea.setOnFailed(e -> {
            btnActualizar.setDisable(false);
            lblMensaje.setText("");
            if (tarea.getException() instanceof ValidacionException v) {
                Dialogos.aviso(v.getMessage());
            } else if (tarea.getException() instanceof SQLException s) {
                Dialogos.errorBase(s);
            } else {
                Dialogos.aviso("No se pudo actualizar el pronóstico: " + tarea.getException().getMessage());
            }
        });
        Thread hilo = new Thread(tarea, "sarif-pronostico");
        hilo.setDaemon(true);
        hilo.start();
    }

    private static void columna(TableColumn<RegistroMeteo, String> columna, Function<RegistroMeteo, String> valor) {
        columna.setCellValueFactory(c -> new SimpleStringProperty(valor.apply(c.getValue())));
    }

    private static String uno(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String cero(double v) {
        return String.format(Locale.ROOT, "%.0f", v);
    }
}
