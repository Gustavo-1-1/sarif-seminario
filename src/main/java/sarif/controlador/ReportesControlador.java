package sarif.controlador;

import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.stage.FileChooser;
import sarif.modelo.EventoGuardia;
import sarif.modelo.FilaDesempeno;
import sarif.modelo.FilaReporteZona;
import sarif.modelo.ResumenGuardia;
import sarif.modelo.Zona;
import sarif.servicio.ReporteHtml;
import sarif.servicio.ServicioReportes;
import sarif.servicio.ServicioZonas;
import sarif.servicio.Sesion;
import sarif.servicio.ValidacionException;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Controlador de la pestaña "Reportes" (CU13): la cronología de la guardia, el histórico por zona (RFS18)
 * y el desempeño del índice (RFS19). El período arranca, por defecto, en el inicio de la temporada de la
 * fecha de trabajo. "Exportar HTML..." guarda en un archivo exactamente lo que se generó en pantalla.
 */
public class ReportesControlador {

    @FXML
    private DatePicker dpDesde;
    @FXML
    private DatePicker dpHasta;
    @FXML
    private ComboBox<Zona> cmbZona;
    @FXML
    private Button btnExportar;
    @FXML
    private Label lblGenerado;
    @FXML
    private Label lblResumen;
    @FXML
    private TableView<EventoGuardia> tablaEventos;
    @FXML
    private TableColumn<EventoGuardia, String> colMomento;
    @FXML
    private TableColumn<EventoGuardia, String> colEventoZona;
    @FXML
    private TableColumn<EventoGuardia, String> colHito;
    @FXML
    private TableColumn<EventoGuardia, String> colDetalle;
    @FXML
    private TableColumn<EventoGuardia, String> colNivelAlerta;
    @FXML
    private TableColumn<EventoGuardia, String> colResponsable;
    @FXML
    private TableColumn<EventoGuardia, String> colDemora;
    @FXML
    private TableView<FilaReporteZona> tablaZonas;
    @FXML
    private TableColumn<FilaReporteZona, String> colZona;
    @FXML
    private TableColumn<FilaReporteZona, Integer> colFocos;
    @FXML
    private TableColumn<FilaReporteZona, Integer> colAlta;
    @FXML
    private TableColumn<FilaReporteZona, Double> colFrp;
    @FXML
    private TableColumn<FilaReporteZona, Integer> colDias;
    @FXML
    private TableColumn<FilaReporteZona, Double> colPromedio;
    @FXML
    private TableColumn<FilaReporteZona, Double> colMaximo;
    @FXML
    private TableColumn<FilaReporteZona, Integer> colAltos;
    @FXML
    private TableView<FilaDesempeno> tablaDesempeno;
    @FXML
    private TableColumn<FilaDesempeno, String> colNivel;
    @FXML
    private TableColumn<FilaDesempeno, Integer> colDiasZona;
    @FXML
    private TableColumn<FilaDesempeno, Integer> colDiasFocos;
    @FXML
    private TableColumn<FilaDesempeno, Double> colPorcentaje;
    @FXML
    private TableColumn<FilaDesempeno, Integer> colFocosNivel;
    @FXML
    private Label lblConclusion;

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    // Opción "todas" del combo de zonas: una zona ficticia con id 0, que el servicio interpreta como sin filtro.
    private static final Zona TODAS = new Zona(0, "Todas las zonas", null, 0, 0, 0, 0, true);

    private final ServicioReportes servicio = new ServicioReportes();
    private final ServicioZonas servicioZonas = new ServicioZonas();
    private LocalDate fechaPropuesta;
    // Lo último que se generó, para que la exportación coincida con lo que se ve en pantalla.
    private ReporteHtml.Datos ultimo;

    /** Armo las columnas con lambdas porque las filas son records (sin getters getX()). */
    @FXML
    private void initialize() {
        colMomento.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().fechaHora() == null ? "" : c.getValue().fechaHora().format(Dialogos.FECHA_HORA)));
        colEventoZona.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().zona()));
        colHito.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().tipo().texto()));
        colDetalle.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().detalle()));
        // El detalle puede ser largo (descripción de la alerta, fundamento): lo muestro completo al pasar el mouse.
        colDetalle.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String texto, boolean vacia) {
                super.updateItem(texto, vacia);
                setText(vacia ? null : texto);
                setTooltip(vacia || texto == null ? null : new Tooltip(texto));
            }
        });
        colNivelAlerta.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().nivelAlerta()));
        colNivelAlerta.setCellFactory(Celdas.etiqueta("alerta-"));
        colResponsable.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().usuario()));
        colDemora.setCellValueFactory(c -> new SimpleStringProperty(ReporteHtml.duracion(c.getValue().minutosDesdeAlerta())));
        tablaEventos.setPlaceholder(new Label("No hay hitos en el período: alertas, notificaciones, cambios de nivel ni asignaciones."));

        colZona.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().zona()));
        colFocos.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().focos()));
        colAlta.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().focosAltaConfianza()));
        colFrp.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().frpPromedioMw()));
        colDias.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().diasConIndice()));
        colPromedio.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().indicePromedio()));
        colMaximo.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().indiceMaximo()));
        colAltos.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().diasAltoOExtremo()));
        // RFS20: mensaje informativo cuando no hay zonas o todavía no se generó el reporte.
        tablaZonas.setPlaceholder(new Label("Elija el período y presione \"Generar\"."));

        colNivel.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().nivel()));
        colNivel.setCellFactory(Celdas.etiqueta("nivel-"));
        colDiasZona.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().diasZona()));
        colDiasFocos.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().diasConFocos()));
        colPorcentaje.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().porcentajeConFocos()));
        colFocosNivel.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().focos()));
        tablaDesempeno.setPlaceholder(new Label("Sin datos para el período."));
        btnExportar.setDisable(true);
    }

    /**
     * La llama PrincipalControlador. Propongo el período solo la primera vez o cuando cambió la fecha de
     * trabajo, para no pisar un período que el operador eligió. El fin es la fecha de trabajo o hoy, la que
     * sea posterior: los hitos se registran con la hora real, así que si se trabaja sobre una jornada pasada
     * (por ejemplo el 20/01/2026), lo que la guardia hizo hoy igual entra en la cronología.
     */
    public void actualizar() {
        LocalDate fecha = Sesion.getFechaTrabajo();
        if (!fecha.equals(fechaPropuesta)) {
            fechaPropuesta = fecha;
            LocalDate hoy = LocalDate.now();
            dpHasta.setValue(fecha.isAfter(hoy) ? fecha : hoy);
            dpDesde.setValue(ServicioReportes.inicioTemporada(fecha));
        }
        cargarZonas();
        generar();
    }

    // Recargo el combo por si se dio de alta una zona, conservando la elegida.
    private void cargarZonas() {
        Zona elegida = cmbZona.getValue();
        try {
            List<Zona> opciones = new ArrayList<>();
            opciones.add(TODAS);
            opciones.addAll(servicioZonas.listarZonas());
            cmbZona.setItems(FXCollections.observableArrayList(opciones));
            cmbZona.setValue(opciones.stream().filter(z -> elegida != null && z.getId() == elegida.getId())
                    .findFirst().orElse(TODAS));
        } catch (SQLException e) {
            Dialogos.errorBase(e);
        }
    }

    /** Botón "Generar": armo la cronología y los dos reportes estadísticos del período elegido. */
    @FXML
    private void generar() {
        Zona zona = cmbZona.getValue() == null ? TODAS : cmbZona.getValue();
        LocalDate desde = dpDesde.getValue();
        LocalDate hasta = dpHasta.getValue();
        try {
            List<EventoGuardia> eventos = servicio.cronologia(desde, hasta, zona.getId());
            List<FilaReporteZona> zonas = servicio.historicoPorZona(desde, hasta);
            List<FilaDesempeno> desempeno = servicio.desempeno(desde, hasta);
            ResumenGuardia resumen = ServicioReportes.resumir(eventos);
            tablaEventos.setItems(FXCollections.observableArrayList(eventos));
            tablaZonas.setItems(FXCollections.observableArrayList(zonas));
            tablaDesempeno.setItems(FXCollections.observableArrayList(desempeno));
            if (zonas.isEmpty()) {
                tablaZonas.setPlaceholder(new Label("No hay zonas registradas."));
            }
            lblResumen.setText(textoResumen(resumen));
            lblConclusion.setText(conclusion(desempeno));
            lblGenerado.setText("Reporte generado a las " + LocalDateTime.now().format(HORA) + ": " + eventos.size()
                    + " hitos (" + zona.getNombre() + ") entre el " + desde.format(Dialogos.FECHA)
                    + " y el " + hasta.format(Dialogos.FECHA) + ". El filtro de zona se aplica a la cronología.");
            ultimo = new ReporteHtml.Datos(desde, hasta, zona.getNombre(), Sesion.getUsuario().nombreCompleto(),
                    eventos, resumen, zonas, desempeno);
            btnExportar.setDisable(false);
        } catch (ValidacionException e) {
            Dialogos.aviso(e.getMessage());
        } catch (SQLException e) {
            Dialogos.errorBase(e);
        }
    }

    /**
     * Botón "Exportar HTML...": guardo el último reporte generado en un archivo y ofrezco abrirlo en el
     * navegador, desde donde se puede imprimir o guardar como PDF.
     */
    @FXML
    private void exportar() {
        if (ultimo == null) {
            Dialogos.aviso("Primero genere el reporte.");
            return;
        }
        FileChooser elegir = new FileChooser();
        elegir.setTitle("Exportar reporte de la guardia");
        elegir.setInitialFileName("reporte_guardia_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")) + ".html");
        elegir.getExtensionFilters().add(new FileChooser.ExtensionFilter("Página HTML", "*.html"));
        File archivo = elegir.showSaveDialog(tablaEventos.getScene().getWindow());
        if (archivo == null) {
            return;
        }
        try {
            servicio.exportarHtml(archivo.toPath(), ultimo);
        } catch (IOException e) {
            Dialogos.aviso("No se pudo guardar el reporte: " + e.getMessage());
            return;
        }
        if (Dialogos.confirmar("Reporte guardado en:\n" + archivo + "\n\n¿Abrirlo en el navegador?")) {
            abrir(archivo);
        }
    }

    // Abro el archivo con el programa predeterminado del sistema en otro hilo, para no trabar la pantalla.
    private static void abrir(File archivo) {
        if (!Desktop.isDesktopSupported()) {
            Dialogos.aviso("El sistema no permite abrir el archivo desde SARIF; ábralo desde la carpeta donde lo guardó.");
            return;
        }
        Thread hilo = new Thread(() -> {
            try {
                Desktop.getDesktop().open(archivo);
            } catch (IOException e) {
                // Si no hay navegador asociado no es un error del reporte: el archivo ya quedó guardado.
            }
        }, "abrir-reporte");
        hilo.setDaemon(true);
        hilo.start();
    }

    // "8 alertas · 5 notificadas · 2 cerradas · ... · Tiempo de aviso: promedio 12 min, máximo 40 min".
    private static String textoResumen(ResumenGuardia r) {
        return r.alertas() + " alertas generadas · " + r.notificadas() + " notificadas · " + r.cerradas() + " cerradas · "
                + r.descartadas() + " descartadas · " + r.cambiosNivel() + " cambios de nivel · " + r.asignaciones()
                + " asignaciones de recursos · Tiempo de aviso: " + ReporteHtml.tiempoAviso(r);
    }

    /**
     * Resumo el desempeño en una frase. Si no hay índices calculados en el período, lo digo (RFS20);
     * si los hay, muestro cuántos días-zona se evaluaron para que se entienda el peso del porcentaje.
     */
    private static String conclusion(List<FilaDesempeno> filas) {
        int dias = filas.stream().mapToInt(FilaDesempeno::diasZona).sum();
        if (dias == 0) {
            return "No hay índices calculados en el período, así que todavía no se puede evaluar el desempeño.";
        }
        int conFocos = filas.stream().mapToInt(FilaDesempeno::diasConFocos).sum();
        return String.format("Se evaluaron %d días-zona con índice calculado; en %d hubo focos. Si el índice anticipa bien, "
                + "el porcentaje de días con focos crece de BAJO a EXTREMO.", dias, conFocos);
    }
}
