package sarif.controlador;

import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.concurrent.ScheduledService;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.Cursor;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import sarif.modelo.RegistroMeteo;
import sarif.modelo.Sincronizacion;
import sarif.modelo.Zona;
import sarif.servicio.ServicioSincronizacion;
import sarif.servicio.ServicioZonas;
import sarif.servicio.Sesion;
import sarif.servicio.ValidacionException;

import java.io.File;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Controlador de la pestaña "Sincronización": CU07 Sincronizar focos, importación histórica (RFS08),
 * CU08 Sincronizar meteorología, la sincronización programada (RFS07) y la carga manual del clima (RFS12).
 * <p>
 * El operador elige la fuente (archivo local o remota: FIRMS para focos y Open-Meteo para el clima)
 * y dispara la operación. Si elige la remota y el servicio falla, el servicio pasa solo a la fuente de
 * archivo (flujo S2 del CU07) y acá lo aviso. Cada operación deja una línea en el registro de la
 * pantalla y una fila en la tabla de últimas sincronizaciones.
 */
public class SincronizacionControlador {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    // Componentes inyectados desde Sincronizacion.fxml. Para saber la fuente alcanza con rbArchivo (si no
    // está elegido, por el ToggleGroup es la remota); rbRemota lo uso solo para elegirla al abrir la pantalla.
    @FXML
    private RadioButton rbArchivo;
    @FXML
    private RadioButton rbRemota;
    @FXML
    private Spinner<Integer> spDias;
    @FXML
    private Label lblFecha;
    @FXML
    private TextArea txtRegistro;
    @FXML
    private TableView<Sincronizacion> tabla;
    @FXML
    private TableColumn<Sincronizacion, Integer> colId;
    @FXML
    private TableColumn<Sincronizacion, String> colFecha;
    @FXML
    private TableColumn<Sincronizacion, String> colTipo;
    @FXML
    private TableColumn<Sincronizacion, String> colOrigen;
    @FXML
    private TableColumn<Sincronizacion, String> colResultado;
    @FXML
    private TableColumn<Sincronizacion, Integer> colNuevos;
    @FXML
    private TableColumn<Sincronizacion, String> colDetalle;

    // Sincronización programada.
    @FXML
    private CheckBox chkProgramada;
    @FXML
    private Label lblProgramada;

    // Carga manual de meteorología (RFS12).
    @FXML
    private ComboBox<Zona> cmbZonaMeteo;
    @FXML
    private DatePicker dpFechaMeteo;
    @FXML
    private CheckBox chkPronostico;
    @FXML
    private TextField txtTemperatura;
    @FXML
    private TextField txtHumedad;
    @FXML
    private TextField txtViento;
    @FXML
    private TextField txtPrecipitacion;
    @FXML
    private TextField txtHumedadSuelo;

    private final ServicioSincronizacion servicio = new ServicioSincronizacion();
    private final ServicioZonas servicioZonas = new ServicioZonas();

    /**
     * Uso ScheduledService de JavaFX para la sincronización programada: repite la tarea cada cierto tiempo
     * en un hilo aparte, así la pantalla no se congela mientras espera a FIRMS u Open-Meteo. Los valores de
     * la pantalla (fuente, fecha) los leo en createTask(), que corre en el hilo de JavaFX, y se los paso a
     * la tarea, porque desde otro hilo no se deben tocar los controles.
     */
    private final ScheduledService<String> programada = new ScheduledService<>() {
        @Override
        protected Task<String> createTask() {
            boolean remota = !rbArchivo.isSelected();
            LocalDate fecha = Sesion.getFechaTrabajo();
            return new Task<>() {
                @Override
                protected String call() throws Exception {
                    // Las programadas no las dispara ninguna persona, por eso el usuario va en null.
                    ServicioSincronizacion.Resultado focos = servicio.sincronizarFocos(remota, servicio.diasProgramados(), fecha, null);
                    ServicioSincronizacion.Resultado meteo = servicio.sincronizarMeteo(remota, fecha, null);
                    return "Focos: " + focos.detalle() + " | Meteorología: " + meteo.detalle();
                }
            };
        }
    };

    /**
     * Configuro el spinner de días (de 1 a 10, arranca en 2), las columnas de la tabla y los eventos de la
     * sincronización programada. Uso lambdas en setCellValueFactory porque Sincronizacion es un record y
     * PropertyValueFactory solo encuentra getters del estilo getX().
     */
    @FXML
    private void initialize() {
        spDias.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 10, 2));
        colId.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().id()));
        colFecha.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().inicio() == null ? "" : c.getValue().inicio().format(Dialogos.FECHA_HORA)));
        colTipo.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().tipo()));
        colOrigen.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().origen().name()));
        colResultado.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().resultado()));
        // OK en verde y FALLIDA en rojo, siempre con el texto visible (ver Celdas).
        colResultado.setCellFactory(Celdas.etiqueta("resultado-"));
        colNuevos.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().registrosNuevos()));
        colDetalle.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().detalle()));
        tabla.setPlaceholder(new Label("Todavía no hay sincronizaciones."));

        // Estos eventos llegan en el hilo de JavaFX, así que acá sí puedo actualizar la pantalla.
        // Uso getValue() y no getLastValue(): ScheduledService actualiza lastValue después de avisar que
        // terminó, así que en este evento todavía tendría el valor anterior (null en la primera corrida).
        programada.setOnSucceeded(e -> {
            registrar("Programada", programada.getValue());
            actualizar();
        });
        programada.setOnFailed(e -> registrar("Programada", "error: " + programada.getException().getMessage()));
        lblProgramada.setText("Desactivada.");
        // Si hay clave de FIRMS, arranco con la fuente remota: los archivos de respaldo solo traen la jornada
        // de prueba del 20/01/2026, así que con cualquier otra fecha darían cero.
        if (servicio.hayClaveFirms()) {
            rbRemota.setSelected(true);
        }
    }

    /**
     * La llama PrincipalControlador al entrar a la pestaña; muestro la fecha y las últimas 20 corridas.
     * La lista de zonas del formulario manual la cargo una sola vez.
     */
    public void actualizar() {
        lblFecha.setText("Fecha de trabajo: " + Sesion.getFechaTrabajo().format(Dialogos.FECHA));
        if (dpFechaMeteo.getValue() == null) {
            dpFechaMeteo.setValue(Sesion.getFechaTrabajo());
        }
        try {
            tabla.setItems(FXCollections.observableArrayList(servicio.ultimasSincronizaciones(20)));
            if (cmbZonaMeteo.getItems().isEmpty()) {
                cmbZonaMeteo.setItems(FXCollections.observableArrayList(servicioZonas.listarZonas()));
            }
        } catch (SQLException e) {
            Dialogos.errorBase(e);
        }
    }

    /** Botón "Sincronizar focos" (CU07): trae los focos de calor de los últimos N días del spinner. */
    @FXML
    private void sincronizarFocos() {
        boolean remota = !rbArchivo.isSelected();
        ejecutar("Focos", () -> servicio.sincronizarFocos(remota, spDias.getValue(), Sesion.getFechaTrabajo(), idUsuario()));
    }

    /** Botón "Sincronizar meteorología" (CU08): trae los datos del clima para la fecha de trabajo. */
    @FXML
    private void sincronizarMeteo() {
        boolean remota = !rbArchivo.isSelected();
        ejecutar("Meteorología", () -> servicio.sincronizarMeteo(remota, Sesion.getFechaTrabajo(), idUsuario()));
    }

    /**
     * Botón "Sincronizar NDVI (Sentinel-2)": trae el NDVI de cada zona con la imagen despejada más reciente
     * hasta la fecha de trabajo. Siempre es remoto, así que no depende de la fuente elegida arriba.
     */
    @FXML
    private void sincronizarNdvi() {
        ejecutar("NDVI", () -> servicio.sincronizarNdvi(Sesion.getFechaTrabajo(), idUsuario()));
    }

    /**
     * Botón "Importar histórico..." (RFS08): el operador elige un CSV de FIRMS con focos de años
     * anteriores. Si existe la carpeta "datos" del proyecto, abro el selector ahí para ahorrarle pasos.
     */
    @FXML
    private void importarHistorico() {
        FileChooser selector = new FileChooser();
        selector.setTitle("Archivo histórico de focos (CSV de FIRMS)");
        selector.getExtensionFilters().add(new FileChooser.ExtensionFilter("Archivos CSV", "*.csv"));
        File carpeta = new File("datos");
        if (carpeta.isDirectory()) {
            selector.setInitialDirectory(carpeta);
        }
        // Uso la ventana de la tabla como dueña del diálogo para que quede modal sobre SARIF.
        File archivo = selector.showOpenDialog(tabla.getScene().getWindow());
        if (archivo != null) {
            ejecutar("Importación histórica", () -> servicio.importarHistorico(Path.of(archivo.getPath()), idUsuario()));
        }
    }

    /**
     * Casilla "Sincronización programada": la arranco con el intervalo del parámetro sincronizacion_minutos
     * o la detengo. La primera corrida es inmediata, así el operador ve enseguida que funciona.
     */
    @FXML
    private void cambiarProgramada() {
        if (!chkProgramada.isSelected()) {
            detenerProgramada();
            return;
        }
        try {
            int minutos = servicio.minutosProgramados();
            programada.setPeriod(Duration.minutes(minutos));
            programada.setDelay(Duration.ZERO);
            programada.restart();
            lblProgramada.setText("Activa: cada " + minutos + " minutos, con la fuente elegida arriba (desde las "
                    + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")) + ").");
        } catch (SQLException e) {
            chkProgramada.setSelected(false);
            Dialogos.errorBase(e);
        }
    }

    /** Detiene la sincronización programada. También la llama PrincipalControlador al cerrar la sesión. */
    public void detenerProgramada() {
        programada.cancel();
        chkProgramada.setSelected(false);
        lblProgramada.setText("Desactivada.");
    }

    /**
     * Botón "Cargar registro" (RFS12): convierto los campos y le paso el registro al servicio, que valida
     * los rangos, lo guarda con origen MANUAL y recalcula el índice de esa fecha.
     */
    @FXML
    private void cargarMeteoManual() {
        Zona zona = cmbZonaMeteo.getValue();
        try {
            RegistroMeteo registro = new RegistroMeteo(zona == null ? 0 : zona.getId(), dpFechaMeteo.getValue(),
                    numero(txtTemperatura, "temperatura máxima"), numero(txtHumedad, "humedad mínima"),
                    numero(txtViento, "viento máximo"),
                    txtPrecipitacion.getText().isBlank() ? 0 : numero(txtPrecipitacion, "precipitación"),
                    chkPronostico.isSelected(),
                    txtHumedadSuelo.getText().isBlank() ? null : numero(txtHumedadSuelo, "humedad del suelo"));
            ServicioSincronizacion.Resultado r = servicio.cargarMeteoManual(registro, idUsuario());
            registrar("Carga manual", r.detalle());
            for (TextField campo : new TextField[]{txtTemperatura, txtHumedad, txtViento, txtPrecipitacion, txtHumedadSuelo}) {
                campo.clear();
            }
            actualizar();
        } catch (ValidacionException e) {
            Dialogos.aviso(e.getMessage());
        } catch (SQLException e) {
            Dialogos.errorBase(e);
        }
    }

    /**
     * Cuando una sincronización exitosa no leyó nada, agrego una línea que explica por qué, así no parece
     * que "no anda": con la fuente de archivo suele ser la fecha, y con FIRMS, que no hubo focos.
     */
    private void explicarCero(String nombre, String fuente, ServicioSincronizacion.Resultado r) {
        if (!r.exitosa() || r.leidos() > 0) {
            return;
        }
        if (fuente.equals("archivo") && (nombre.equals("Focos") || nombre.equals("Meteorología"))) {
            registrar("Aviso", "Los archivos de respaldo solo tienen datos de la jornada de prueba (18 al 20/01/2026). "
                    + "Para la fecha de trabajo " + Sesion.getFechaTrabajo().format(Dialogos.FECHA)
                    + " elija la fuente Remota (FIRMS / Open-Meteo).");
        } else if (fuente.equals("remota") && nombre.equals("Focos")) {
            registrar("Aviso", "FIRMS respondió bien pero no detectó focos de calor dentro de las zonas vigiladas en los últimos "
                    + spDias.getValue() + " día(s). Es lo esperable fuera de la temporada de incendios.");
        }
    }

    /** Id del usuario de la sesión para dejar registrado quién lanzó la sincronización. */
    private Integer idUsuario() {
        return Sesion.getUsuario() == null ? null : Sesion.getUsuario().id();
    }

    /**
     * Ejecuta la sincronización y deja el resultado en el registro de la pantalla.
     * <p>
     * Los tres botones hacen lo mismo alrededor de la operación (cursor de espera, registro con la hora,
     * aviso si falló y refresco de la tabla), así que recibo la operación como lambda y evito repetir
     * ese código. Pongo el cursor en espera porque la fuente remota puede tardar unos segundos.
     */
    private void ejecutar(String nombre, Operacion operacion) {
        tabla.getScene().setCursor(Cursor.WAIT);
        try {
            ServicioSincronizacion.Resultado r = operacion.ejecutar();
            // La importación histórica siempre lee un archivo, sin importar qué fuente esté elegida.
            String fuente = !nombre.equals("NDVI") && (rbArchivo.isSelected() || nombre.startsWith("Importación") || r.usoRespaldo())
                    ? "archivo" : "remota";
            registrar(nombre + " (" + fuente + ")", r.detalle());
            explicarCero(nombre, fuente, r);
            if (!r.exitosa()) {
                Dialogos.aviso(r.detalle());
            } else if (r.usoRespaldo()) {
                Dialogos.aviso("No se pudo usar el servicio remoto, así que se usó la fuente de archivo.\n\n" + r.detalle());
            }
            actualizar();
        } catch (SQLException e) {
            Dialogos.errorBase(e);
        } finally {
            // Restauro el cursor pase lo que pase, incluso si hubo una excepción.
            tabla.getScene().setCursor(Cursor.DEFAULT);
        }
    }

    /** Agrego una línea con la hora al registro de la pantalla. */
    private void registrar(String operacion, String detalle) {
        txtRegistro.appendText(String.format("[%s] %s: %s%n", LocalTime.now().format(HORA), operacion, detalle));
    }

    /** Convierte el texto de un campo a número, aceptando coma o punto decimal. */
    private static double numero(TextField campo, String nombre) throws ValidacionException {
        try {
            return Double.parseDouble(campo.getText().trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new ValidacionException("El valor de " + nombre + " no es un número válido.");
        }
    }

    /**
     * Interfaz funcional propia para las operaciones de sincronización. No uso Supplier porque su
     * método no puede lanzar SQLException, y quiero manejar ese error en un solo lugar (ejecutar).
     */
    @FunctionalInterface
    private interface Operacion {
        ServicioSincronizacion.Resultado ejecutar() throws SQLException;
    }
}
