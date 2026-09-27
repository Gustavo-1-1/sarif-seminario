package sarif.controlador;

import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;
import sarif.App;
import sarif.modelo.Alerta;
import sarif.modelo.Permiso;
import sarif.modelo.Usuario;
import sarif.servicio.ServicioAlertas;
import sarif.servicio.ServicioUsuarios;
import sarif.servicio.Sesion;
import sarif.servicio.ValidacionException;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

/**
 * Controlador de la ventana principal: barra superior con el usuario y la fecha de trabajo, y las
 * pestañas (tablero, mapa de riesgo, pronóstico, zonas, activos y recursos, sincronización, fuentes de datos,
 * despliegue, alertas, reportes y, solo para el administrador, usuarios). Lo que cada rol puede hacer
 * dentro de las pestañas lo resuelve cada controlador con Restricciones.
 * <p>
 * Cada pestaña tiene su propio FXML y su propio controlador, y este controlador funciona como
 * coordinador: cuando cambia la pestaña seleccionada o la fecha de trabajo, le pide a la pestaña
 * visible que se actualice. Así solo consulto la base para lo que el operador está mirando.
 * <p>
 * Además vigila las alertas: cada pocos segundos cuenta las pendientes (las muestra en el título de la
 * pestaña Alertas) y, si el sistema generó alertas nuevas (por ejemplo en la sincronización programada),
 * abre un aviso en la esquina de la ventana. Así el sistema le avisa al operador sin que tenga que ir a buscar.
 */
public class PrincipalControlador {

    private static final int PESTANA_ZONAS = 3;
    private static final int PESTANA_ALERTAS = 8;

    @FXML
    private Label lblUsuario;
    @FXML
    private DatePicker dpFechaTrabajo;
    @FXML
    private TabPane pestanas;
    @FXML
    private Tab tabAlertas;
    @FXML
    private Tab tabUsuarios;

    // Controladores de las vistas incluidas (fx:include con fx:id + "Controller").
    // Cuando en Principal.fxml escribo <fx:include fx:id="tablero" .../>, el FXMLLoader inyecta el nodo
    // raíz en un atributo "tablero" y el controlador de ese FXML en "tableroController". Por eso los
    // nombres de estos atributos tienen que respetar exactamente esa convención; si no, quedan en null.
    @FXML
    private TableroControlador tableroController;
    @FXML
    private MapaControlador mapaController;
    @FXML
    private PronosticoControlador pronosticoController;
    @FXML
    private ZonasControlador zonasController;
    @FXML
    private ActivosRecursosControlador activosRecursosController;
    @FXML
    private SincronizacionControlador sincronizacionController;
    @FXML
    private FuentesControlador fuentesController;
    @FXML
    private DespliegueControlador despliegueController;
    @FXML
    private AlertasControlador alertasController;
    @FXML
    private ReportesControlador reportesController;
    @FXML
    private UsuariosControlador usuariosController;

    private final ServicioAlertas servicioAlertas = new ServicioAlertas();
    private final ServicioUsuarios servicioUsuarios = new ServicioUsuarios();
    private Timeline vigilancia;
    private int ultimaAlertaVista = -1;
    private Stage aviso;

    /**
     * Muestro el usuario logueado, cargo la fecha de trabajo de la sesión y registro los listeners:
     * uno para la fecha y otro para el cambio de pestaña. Al final actualizo la pestaña inicial y
     * arranco la vigilancia de alertas.
     */
    @FXML
    private void initialize() {
        Usuario u = Sesion.getUsuario();
        lblUsuario.setText(u == null ? "" : u.nombreCompleto() + " (" + u.rolTexto() + ")");
        // La pestaña Usuarios es la última, así que quitarla no corre el índice de las demás.
        if (!Restricciones.puede(Permiso.ADMINISTRAR_USUARIOS)) {
            pestanas.getTabs().remove(tabUsuarios);
        }
        dpFechaTrabajo.setValue(Sesion.getFechaTrabajo());
        // La fecha de trabajo permite revisar jornadas anteriores; la guardo en Sesion para que la
        // lean todos los controladores y refresco la pestaña que se está viendo.
        dpFechaTrabajo.valueProperty().addListener((obs, anterior, nueva) -> {
            if (nueva != null) {
                Sesion.setFechaTrabajo(nueva);
                actualizarPestanaActual();
            }
        });
        pestanas.getSelectionModel().selectedIndexProperty().addListener((obs, anterior, nueva) -> actualizarPestanaActual());
        // Desde el mapa se puede marcar el rectángulo o dibujar el polígono de una zona nueva: paso a Zonas con esa forma.
        mapaController.setAlCrearZona(c -> {
            pestanas.getSelectionModel().select(PESTANA_ZONAS);
            zonasController.precargarArea(c[0], c[1], c[2], c[3]);
        }, vertices -> {
            pestanas.getSelectionModel().select(PESTANA_ZONAS);
            zonasController.precargarPoligono(vertices);
        });
        actualizarPestanaActual();

        // Reviso las alertas cada 10 segundos. La primera revisión la demoro un poco para que la ventana
        // ya esté en pantalla y el aviso pueda ubicarse sobre ella.
        vigilancia = new Timeline(new KeyFrame(Duration.seconds(10), e -> revisarAlertas()));
        vigilancia.setCycleCount(Timeline.INDEFINITE);
        PauseTransition inicio = new PauseTransition(Duration.seconds(1.5));
        inicio.setOnFinished(e -> {
            revisarAlertas();
            vigilancia.play();
        });
        inicio.play();
    }

    /**
     * Llama a actualizar() del controlador de la pestaña seleccionada. El índice sigue el orden de los
     * Tab en Principal.fxml; si querés agregar una pestaña, sumá un Tab allá, su controlador arriba y
     * un case nuevo acá.
     */
    private void actualizarPestanaActual() {
        switch (pestanas.getSelectionModel().getSelectedIndex()) {
            case 0 -> tableroController.actualizar();
            case 1 -> mapaController.actualizar();
            case 2 -> pronosticoController.actualizar();
            case PESTANA_ZONAS -> zonasController.actualizar();
            case 4 -> activosRecursosController.actualizar();
            case 5 -> sincronizacionController.actualizar();
            case 6 -> fuentesController.actualizar();
            case 7 -> despliegueController.actualizar();
            case PESTANA_ALERTAS -> alertasController.actualizar();
            case 9 -> reportesController.actualizar();
            case 10 -> usuariosController.actualizar();
            default -> {
            }
        }
    }

    /**
     * Cuento las pendientes para el título de la pestaña y busco alertas registradas desde la última
     * revisión. En la primera revisión (al ingresar) aviso cuántas pendientes hay; después, solo las nuevas.
     * Si la base no responde no muestro errores acá: lo intento de nuevo en la próxima vuelta.
     */
    private void revisarAlertas() {
        try {
            int pendientes = servicioAlertas.contarPendientes();
            tabAlertas.setText(pendientes == 0 ? "Alertas" : "Alertas (" + pendientes + ")");
            tabAlertas.getStyleClass().remove("pestana-con-alertas");
            if (pendientes > 0) {
                tabAlertas.getStyleClass().add("pestana-con-alertas");
            }
            if (ultimaAlertaVista < 0) {
                ultimaAlertaVista = servicioAlertas.ultimoId();
                if (pendientes > 0) {
                    mostrarAviso("Hay " + pendientes + " alerta(s) pendiente(s) de atender.", List.of());
                }
                return;
            }
            List<Alerta> nuevas = servicioAlertas.listarPosteriores(ultimaAlertaVista);
            if (!nuevas.isEmpty()) {
                ultimaAlertaVista = nuevas.get(0).id();
                mostrarAviso(nuevas.size() + " alerta(s) nueva(s)", nuevas);
                // Si el operador está mirando la pestaña de alertas, la refresco para que vea las nuevas.
                if (pestanas.getSelectionModel().getSelectedIndex() == PESTANA_ALERTAS) {
                    alertasController.actualizar();
                }
            }
        } catch (SQLException e) {
            // Sin base disponible no hay nada que avisar; reintento en la próxima revisión.
        }
    }

    /**
     * Aviso emergente en la esquina inferior derecha de la ventana, con las primeras alertas nuevas y un
     * botón para ir a la pestaña Alertas. Se cierra solo a los 20 segundos. Además suena el aviso del
     * sistema operativo para llamar la atención aunque el operador esté mirando otra cosa.
     */
    private void mostrarAviso(String titulo, List<Alerta> alertas) {
        Window ventana = pestanas.getScene() == null ? null : pestanas.getScene().getWindow();
        if (ventana == null) {
            return;
        }
        if (aviso != null) {
            aviso.close();
        }
        VBox contenido = new VBox(6);
        contenido.getStyleClass().add("aviso-alertas");
        contenido.setPadding(new Insets(12));
        Label lblTitulo = new Label("⚠ " + titulo);
        lblTitulo.getStyleClass().add("aviso-titulo");
        contenido.getChildren().add(lblTitulo);
        for (Alerta a : alertas.stream().limit(4).toList()) {
            Label linea = new Label(a.tipo().texto() + " · " + a.nombreZona()
                    + (a.nivelSugerido() == null ? "" : " · sugiere " + a.nivelSugerido().nombre()));
            linea.setWrapText(true);
            contenido.getChildren().add(linea);
        }
        if (alertas.size() > 4) {
            contenido.getChildren().add(new Label("y " + (alertas.size() - 4) + " más..."));
        }
        Button ver = new Button("Ver alertas");
        ver.getStyleClass().add("boton-principal");
        Button cerrar = new Button("Cerrar");
        HBox botones = new HBox(8, ver, cerrar);
        botones.setAlignment(Pos.CENTER_RIGHT);
        contenido.getChildren().add(botones);

        Stage stage = new Stage(StageStyle.UTILITY);
        stage.initOwner(ventana);
        stage.setTitle("SARIF - Alertas");
        stage.setAlwaysOnTop(true);
        Scene escena = new Scene(contenido, 360, -1);
        escena.getStylesheets().addAll(pestanas.getScene().getStylesheets());
        stage.setScene(escena);
        ver.setOnAction(e -> {
            stage.close();
            pestanas.getSelectionModel().select(PESTANA_ALERTAS);
        });
        cerrar.setOnAction(e -> stage.close());
        stage.show();
        stage.setX(ventana.getX() + ventana.getWidth() - stage.getWidth() - 24);
        stage.setY(ventana.getY() + ventana.getHeight() - stage.getHeight() - 24);
        aviso = stage;
        try {
            java.awt.Toolkit.getDefaultToolkit().beep();
        } catch (Exception | Error e) {
            // Si el sistema no tiene sonido, el aviso visual alcanza.
        }
        PauseTransition autocierre = new PauseTransition(Duration.seconds(20));
        autocierre.setOnFinished(e -> stage.close());
        autocierre.play();
    }

    /** Botón "Cambiar clave": cualquier usuario cambia la suya; pido la actual para confirmar que es la persona. */
    @FXML
    private void cambiarClave() {
        Dialogos.claves("Cambiar clave", "Clave de " + Sesion.getUsuario().nombreUsuario()
                        + " (al menos " + ServicioUsuarios.LARGO_MINIMO_CLAVE + " caracteres)",
                "Clave actual", "Clave nueva", "Repetir clave nueva").ifPresent(c -> {
            try {
                servicioUsuarios.cambiarMiClave(Sesion.getUsuario().id(), c[0], c[1], c[2]);
                Dialogos.info("Clave cambiada. La próxima vez ingresá con la clave nueva.");
            } catch (ValidacionException e) {
                Dialogos.aviso(e.getMessage());
            } catch (SQLException e) {
                Dialogos.errorBase(e);
            }
        });
    }

    /**
     * Botón "Salir": freno la sincronización programada y la vigilancia de alertas (si no, seguirían
     * corriendo sin nadie logueado), vacío la sesión y vuelvo a la pantalla de ingreso.
     */
    @FXML
    private void cerrarSesion() throws IOException {
        sincronizacionController.detenerProgramada();
        vigilancia.stop();
        if (aviso != null) {
            aviso.close();
        }
        Sesion.iniciar(null);
        App.mostrarIngreso();
    }
}
