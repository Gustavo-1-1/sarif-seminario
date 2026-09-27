package sarif.controlador;

import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import sarif.modelo.FilaTablero;
import sarif.servicio.ServicioRiesgo;
import sarif.servicio.ServicioSincronizacion;
import sarif.servicio.ServicioZonas;
import sarif.servicio.Sesion;
import sarif.servicio.ValidacionException;

import java.sql.SQLException;
import java.util.List;

/**
 * Controlador de la pestaña "Tablero de riesgo": CU06 Tablero de riesgo y CU09 Calcular índice a pedido.
 * <p>
 * Es la primera pantalla que ve el operador: una fila por zona con su último índice de riesgo (0 a 100),
 * el nivel de riesgo que le corresponde y el nivel de alerta vigente. Los datos los arma ServicioZonas
 * y el cálculo del índice lo hace ServicioRiesgo; el controlador solo los muestra (MVC, RNF04).
 */
public class TableroControlador {

    // Componentes inyectados desde Tablero.fxml.
    @FXML
    private Label lblTitulo;
    @FXML
    private Label lblResumen;
    @FXML
    private Label lblMensaje;
    @FXML
    private Label lblAvisos;
    @FXML
    private TableView<FilaTablero> tabla;
    @FXML
    private TableColumn<FilaTablero, String> colZona;
    @FXML
    private TableColumn<FilaTablero, String> colVigilancia;
    @FXML
    private TableColumn<FilaTablero, String> colFecha;
    @FXML
    private TableColumn<FilaTablero, Double> colIndice;
    @FXML
    private TableColumn<FilaTablero, String> colNivelRiesgo;
    @FXML
    private TableColumn<FilaTablero, String> colNivelAlerta;

    private final ServicioZonas servicioZonas = new ServicioZonas();
    private final ServicioRiesgo servicioRiesgo = new ServicioRiesgo();
    private final ServicioSincronizacion servicioSincronizacion = new ServicioSincronizacion();

    /**
     * Armo las columnas. FilaTablero es un record (accesores nombre(), indice()...), por eso uso lambdas
     * en lugar de PropertyValueFactory, que solo busca métodos getX(). Los dos niveles los muestro con
     * color y texto a la vez, para no depender solo del color (accesibilidad).
     */
    @FXML
    private void initialize() {
        colZona.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().nombre()));
        colVigilancia.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().vigilanciaActiva() ? "Activa" : "Inactiva"));
        // Si la zona todavía no tiene índice calculado, muestro un guion en la fecha.
        colFecha.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().fechaIndice() == null ? "—" : c.getValue().fechaIndice().format(Dialogos.FECHA)));
        colIndice.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().indice()));
        colNivelRiesgo.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().nivelRiesgo()));
        colNivelRiesgo.setCellFactory(Celdas.etiqueta("nivel-"));
        colNivelAlerta.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().nivelAlerta()));
        colNivelAlerta.setCellFactory(Celdas.etiqueta("alerta-"));
        tabla.setPlaceholder(new Label("No hay zonas registradas."));
    }

    /**
     * Recarga el tablero y el resumen de arriba (cuántas zonas hay, cuántas vigiladas y cuántas en nivel
     * EXTREMO o ALTO), para que el operador vea de un vistazo lo más urgente. La llama
     * PrincipalControlador y también el botón "Actualizar" del FXML.
     */
    public void actualizar() {
        lblTitulo.setText("Tablero de riesgo — jornada del " + Sesion.getFechaTrabajo().format(Dialogos.FECHA));
        try {
            List<FilaTablero> filas = servicioZonas.tablero();
            tabla.setItems(FXCollections.observableArrayList(filas));
            long activas = filas.stream().filter(FilaTablero::vigilanciaActiva).count();
            long extremas = filas.stream().filter(f -> "EXTREMO".equals(f.nivelRiesgo())).count();
            long altas = filas.stream().filter(f -> "ALTO".equals(f.nivelRiesgo())).count();
            lblResumen.setText(String.format("%d zonas · %d con vigilancia activa · %d en nivel EXTREMO · %d en nivel ALTO",
                    filas.size(), activas, extremas, altas));
            // RFS20: si la última sincronización falló, lo muestro arriba de la tabla; si no, oculto el aviso.
            List<String> avisos = servicioSincronizacion.avisosDeSincronizacion();
            lblAvisos.setText(String.join("\n", avisos));
            lblAvisos.setVisible(!avisos.isEmpty());
            lblAvisos.setManaged(!avisos.isEmpty());
        } catch (SQLException e) {
            Dialogos.errorBase(e);
        }
    }

    /**
     * Botón "Calcular índice del día" (CU09): calcula el índice de riesgo de las zonas para la fecha de
     * trabajo, muestro el resumen que devuelve el servicio y refresco la tabla con los valores nuevos.
     */
    @FXML
    private void calcularIndice() {
        try {
            ServicioRiesgo.ResultadoCalculo resultado = servicioRiesgo.calcularIndices(Sesion.getFechaTrabajo());
            lblMensaje.setText(resultado.resumen());
            actualizar();
        } catch (ValidacionException e) {
            // Por ejemplo, si los pesos configurados del índice no suman 1.
            Dialogos.aviso(e.getMessage());
        } catch (SQLException e) {
            Dialogos.errorBase(e);
        }
    }
}
