package sarif.controlador;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import sarif.modelo.FuenteConfigurada;
import sarif.modelo.Permiso;
import sarif.servicio.ServicioFuentes;
import sarif.servicio.Sesion;
import sarif.servicio.ValidacionException;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Controlador de la pestaña "Fuentes de datos". Muestro las conexiones, las pruebo y guardo la
 * configuración de la sincronización.
 * <p>
 * Las pruebas de los servicios remotos pueden tardar varios segundos (o hasta el timeout si no hay
 * internet), así que las corro en un Task de JavaFX, en un hilo aparte, y actualizo la tabla cuando
 * terminan. Mientras tanto deshabilito los botones para que no se lancen dos pruebas a la vez.
 */
public class FuentesControlador {

    @FXML
    private TableView<FuenteConfigurada> tabla;
    @FXML
    private TableColumn<FuenteConfigurada, String> colNombre;
    @FXML
    private TableColumn<FuenteConfigurada, String> colTipo;
    @FXML
    private TableColumn<FuenteConfigurada, String> colDireccion;
    @FXML
    private TableColumn<FuenteConfigurada, String> colConfiguracion;
    @FXML
    private TableColumn<FuenteConfigurada, String> colUltima;
    @FXML
    private TableColumn<FuenteConfigurada, String> colEstado;
    @FXML
    private Label lblDetalle;
    @FXML
    private Button btnProbar;
    @FXML
    private Button btnProbarTodas;
    @FXML
    private ComboBox<String> cmbProducto;
    @FXML
    private Spinner<Integer> spMinutos;
    @FXML
    private Spinner<Integer> spDias;
    @FXML
    private Button btnGuardar;
    @FXML
    private Label lblPermisoConfig;

    private final ServicioFuentes servicio = new ServicioFuentes();

    @FXML
    private void initialize() {
        colNombre.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().nombre()));
        colTipo.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().tipo()));
        colDireccion.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().direccion()));
        colConfiguracion.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().configuracion()));
        colUltima.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().ultimaSincronizacion()));
        colEstado.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().estado()));
        colEstado.setCellFactory(Celdas.etiqueta("fuente-"));
        tabla.getSelectionModel().selectedItemProperty().addListener((obs, anterior, fila) -> mostrarDetalle(fila));

        cmbProducto.setItems(FXCollections.observableArrayList(ServicioFuentes.PRODUCTOS_FIRMS));
        // Los rangos del spinner coinciden con los de validarConfiguracion; igual el servicio vuelve a validar.
        spMinutos.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(15, 1440, 180, 15));
        spDias.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 10, 2));
        Restricciones.aplicar(Permiso.CONFIGURAR, lblPermisoConfig, cmbProducto, spMinutos, spDias, btnGuardar);
    }

    /** La llama PrincipalControlador al entrar a la pestaña: recargo las fuentes y la configuración. */
    public void actualizar() {
        try {
            tabla.setItems(FXCollections.observableArrayList(servicio.fuentes()));
            Map<String, String> conf = servicio.configuracion();
            cmbProducto.setValue(conf.get("firms_producto"));
            spMinutos.getValueFactory().setValue(entero(conf.get("sincronizacion_minutos"), 180));
            spDias.getValueFactory().setValue(entero(conf.get("dias_sincronizacion"), 2));
            tabla.getSelectionModel().selectFirst();
        } catch (SQLException e) {
            Dialogos.errorBase(e);
        }
    }

    /** Botón "Probar conexión": pruebo solo la fuente seleccionada. */
    @FXML
    private void probarSeleccionada() {
        FuenteConfigurada fila = tabla.getSelectionModel().getSelectedItem();
        if (fila == null) {
            Dialogos.aviso("Seleccione una fuente de la tabla.");
            return;
        }
        probar(List.of(fila));
    }

    /** Botón "Probar todas": pruebo todas las fuentes, una detrás de otra, en el mismo hilo aparte. */
    @FXML
    private void probarTodas() {
        probar(new ArrayList<>(tabla.getItems()));
    }

    /**
     * Corro las pruebas en un Task. Le paso la lista ya armada porque desde el hilo aparte no se debe
     * leer ni tocar la tabla; el resultado lo vuelco en la pantalla en setOnSucceeded, que corre en el
     * hilo de JavaFX.
     */
    private void probar(List<FuenteConfigurada> aProbar) {
        Task<List<FuenteConfigurada>> tarea = new Task<>() {
            @Override
            protected List<FuenteConfigurada> call() throws Exception {
                List<FuenteConfigurada> resultados = new ArrayList<>();
                for (FuenteConfigurada f : aProbar) {
                    resultados.add(servicio.probar(f));
                }
                return resultados;
            }
        };
        tarea.setOnSucceeded(e -> {
            for (FuenteConfigurada r : tarea.getValue()) {
                reemplazar(r);
            }
            terminarPrueba();
        });
        tarea.setOnFailed(e -> {
            terminarPrueba();
            Throwable error = tarea.getException();
            if (error instanceof SQLException sql) {
                Dialogos.errorBase(sql);
            } else {
                Dialogos.aviso("No se pudo completar la prueba: " + error.getMessage());
            }
        });
        btnProbar.setDisable(true);
        btnProbarTodas.setDisable(true);
        lblDetalle.setText("Probando " + (aProbar.size() == 1 ? aProbar.get(0).nombre() : "las fuentes") + "...");
        Thread hilo = new Thread(tarea, "prueba-fuentes");
        // Hilo daemon: si el operador cierra SARIF en medio de una prueba, no deja la aplicación abierta.
        hilo.setDaemon(true);
        hilo.start();
    }

    /** Botón "Guardar configuración". La sincronización programada toma el intervalo nuevo al volver a activarla. */
    @FXML
    private void guardar() {
        try {
            // Los spinners son editables: confirmo lo que el operador escribió antes de leer el valor.
            spMinutos.commitValue();
            spDias.commitValue();
        } catch (NumberFormatException e) {
            Dialogos.aviso("Los minutos y los días deben ser números enteros.");
            return;
        }
        try {
            servicio.guardarConfiguracion(cmbProducto.getValue(), spMinutos.getValue(), spDias.getValue(),
                    Sesion.getUsuario().id());
            Dialogos.info("Configuración guardada. Si la sincronización programada está activa, "
                    + "desactivala y volvé a activarla para aplicar el nuevo intervalo.");
            actualizar();
        } catch (ValidacionException e) {
            Dialogos.aviso(e.getMessage());
        } catch (SQLException e) {
            Dialogos.errorBase(e);
        }
    }

    // Cambio la fila de la fuente probada por la versión con el resultado, manteniendo la selección.
    private void reemplazar(FuenteConfigurada resultado) {
        for (int i = 0; i < tabla.getItems().size(); i++) {
            if (tabla.getItems().get(i).nombre().equals(resultado.nombre())) {
                boolean seleccionada = tabla.getSelectionModel().getSelectedIndex() == i;
                tabla.getItems().set(i, resultado);
                if (seleccionada) {
                    tabla.getSelectionModel().select(i);
                }
            }
        }
    }

    private void terminarPrueba() {
        btnProbar.setDisable(false);
        btnProbarTodas.setDisable(false);
        mostrarDetalle(tabla.getSelectionModel().getSelectedItem());
    }

    private void mostrarDetalle(FuenteConfigurada fila) {
        lblDetalle.setText(fila == null ? "" : fila.nombre() + ": " + fila.detalleEstado());
    }

    private static int entero(String valor, int porDefecto) {
        try {
            return Integer.parseInt(valor.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return porDefecto;
        }
    }
}
