package sarif.controlador;

import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.GridPane;
import sarif.modelo.Alerta;
import sarif.modelo.Confianza;
import sarif.modelo.EstadoAlerta;
import sarif.modelo.MedioNotificacion;
import sarif.modelo.Notificacion;
import sarif.modelo.Permiso;
import sarif.servicio.ServicioAlertas;
import sarif.servicio.Sesion;
import sarif.servicio.ValidacionException;

import java.sql.SQLException;
import java.util.Optional;

/**
 * Controlador de la pestaña "Alertas": consulta de las alertas que genera el sistema (CU14, CU15 y las
 * de riesgo), cambio de estado y aplicación del nivel de alerta sugerido.
 * <p>
 * Las alertas las crea el sistema solo: la sincronización cuando un foco cae dentro de una zona o cerca
 * de un activo protegido, y el cálculo del índice cuando una zona pasa a riesgo ALTO o EXTREMO o tiene
 * focos con riesgo alto. Acá el operador las revisa, registra a quién avisó (NOTIFICADA), las pasa a
 * CERRADA o DESCARTADA y,
 * si quiere, aplica el nivel que sugieren. Como en todos los controladores, hablo con el servicio y nunca con un DAO (patrón MVC,
 * RNF04): las reglas de qué transición de estado es válida viven en la capa de servicio.
 */
public class AlertasControlador {

    // Componentes inyectados desde Alertas.fxml; el nombre de cada atributo coincide con su fx:id.
    @FXML
    private Label lblResumen;
    @FXML
    private TableView<Alerta> tabla;
    @FXML
    private TableColumn<Alerta, Integer> colId;
    @FXML
    private TableColumn<Alerta, String> colFecha;
    @FXML
    private TableColumn<Alerta, String> colZona;
    @FXML
    private TableColumn<Alerta, String> colTipo;
    @FXML
    private TableColumn<Alerta, String> colActivo;
    @FXML
    private TableColumn<Alerta, Double> colDistancia;
    @FXML
    private TableColumn<Alerta, String> colConfianza;
    @FXML
    private TableColumn<Alerta, String> colEstado;
    @FXML
    private TableColumn<Alerta, String> colSugerido;
    @FXML
    private TableColumn<Alerta, String> colNotificacion;
    @FXML
    private TableColumn<Alerta, String> colDescripcion;
    @FXML
    private Button btnAplicarSugerencia;
    @FXML
    private Label lblPermisoNivel;

    private final ServicioAlertas servicio = new ServicioAlertas();

    /**
     * Armo las columnas de la tabla. Alerta es un record, así que sus accesores se llaman id(),
     * fechaHora(), etc., y no getId(); por eso PropertyValueFactory no me sirve (busca getters con
     * el prefijo get) y uso lambdas que envuelven cada valor en una propiedad observable.
     */
    @FXML
    private void initialize() {
        Restricciones.aplicar(Permiso.CAMBIAR_NIVEL_ALERTA, lblPermisoNivel, btnAplicarSugerencia);
        colId.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().id()));
        colFecha.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().fechaHora() == null ? "" : c.getValue().fechaHora().format(Dialogos.FECHA_HORA)));
        colZona.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().nombreZona()));
        // Muestro el texto legible del tipo (el enum lo trae) en vez del nombre técnico.
        colTipo.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().tipo().texto()));
        // Las alertas de riesgo van en negrita para que se distingan de las de un foco suelto.
        colTipo.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String texto, boolean vacia) {
                super.updateItem(texto, vacia);
                Alerta a = vacia || getTableRow() == null ? null : getTableRow().getItem();
                setText(vacia ? null : texto);
                getStyleClass().remove("alerta-riesgo");
                if (a != null && a.tipo().esDeRiesgo()) {
                    getStyleClass().add("alerta-riesgo");
                }
            }
        });
        colSugerido.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().nivelSugerido() == null ? "—" : c.getValue().nivelSugerido().nombre()));
        // Las alertas de tipo "foco en zona" no tienen activo asociado; muestro un guion en ese caso.
        colActivo.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().nombreActivo() == null ? "—" : c.getValue().nombreActivo()));
        colDistancia.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().distanciaKm()));
        // La alerta de riesgo elevado no sale de un foco, así que no tiene confianza.
        colConfianza.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().confianza() == null ? "—" : c.getValue().confianza().name()));
        // La confianza también va como etiqueta: la BAJA en amarillo, para que se verifique antes de actuar.
        colConfianza.setCellFactory(Celdas.etiqueta("confianza-"));
        colEstado.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().estado().name()));
        // El estado lo muestro como etiqueta de color con el texto adentro (clases estado-PENDIENTE, etc.).
        colEstado.setCellFactory(Celdas.etiqueta("estado-"));
        colNotificacion.setCellValueFactory(c -> new SimpleStringProperty(textoNotificacion(c.getValue().notificacion())));
        colDescripcion.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().descripcion()));
        tabla.setPlaceholder(new Label("No hay alertas registradas."));
    }

    // "Defensa Civil (Teléfono) · Marcela Ruiz, 20/01/2026 19:05", o un guion si todavía no se avisó.
    private static String textoNotificacion(Notificacion n) {
        if (n == null) {
            return "—";
        }
        return n.destinatario() + " (" + n.medio().texto() + ") · " + n.usuario() + ", "
                + (n.fechaHora() == null ? "" : n.fechaHora().format(Dialogos.FECHA_HORA));
    }

    /**
     * Recarga la lista de alertas y el resumen. Es pública porque la llama PrincipalControlador cada vez
     * que el operador entra a esta pestaña, y además la uso desde el botón "Actualizar" del FXML.
     */
    public void actualizar() {
        try {
            tabla.setItems(FXCollections.observableArrayList(servicio.listar()));
            long pendientes = tabla.getItems().stream().filter(a -> a.estado() == EstadoAlerta.PENDIENTE).count();
            long bajas = tabla.getItems().stream().filter(a -> a.confianza() == Confianza.BAJA).count();
            long riesgo = tabla.getItems().stream().filter(a -> a.tipo().esDeRiesgo()).count();
            lblResumen.setText(tabla.getItems().size() + " alertas · " + pendientes + " pendientes de notificar · "
                    + riesgo + " de riesgo · " + bajas + " de confianza baja (verificar)");
        } catch (SQLException e) {
            Dialogos.errorBase(e);
        }
    }

    /**
     * Botón "Notificar...": SARIF no manda el aviso; el operador avisa por radio, teléfono, etc. y acá deja
     * registrado a quién y por qué medio. La alerta pasa a NOTIFICADA a su nombre y con la hora de la base.
     * Si el servicio rechaza los datos, vuelvo a abrir el diálogo con lo que había escrito.
     */
    @FXML
    private void notificar() {
        Alerta alerta = tabla.getSelectionModel().getSelectedItem();
        if (alerta == null) {
            Dialogos.aviso("Seleccione una alerta de la lista.");
            return;
        }
        if (alerta.estado() != EstadoAlerta.PENDIENTE) {
            Dialogos.aviso("Solo se notifican las alertas PENDIENTES; esta está " + alerta.estado() + ".");
            return;
        }
        DatosAviso datos = new DatosAviso("", MedioNotificacion.TELEFONO);
        while (true) {
            Optional<DatosAviso> respuesta = pedirNotificacion(alerta, datos);
            if (respuesta.isEmpty()) {
                return;
            }
            datos = respuesta.get();
            try {
                servicio.notificar(alerta, datos.destinatario(), datos.medio(), Sesion.getUsuario().id());
                actualizar();
                return;
            } catch (ValidacionException e) {
                Dialogos.aviso(e.getMessage());
            } catch (SQLException e) {
                Dialogos.errorBase(e);
                return;
            }
        }
    }

    // Lo que escribe el operador en el diálogo de notificación.
    private record DatosAviso(String destinatario, MedioNotificacion medio) {
    }

    // Diálogo con el destinatario y el medio, precargado con los datos anteriores; vacío si se canceló.
    private Optional<DatosAviso> pedirNotificacion(Alerta alerta, DatosAviso anterior) {
        Dialog<DatosAviso> dialogo = new Dialog<>();
        dialogo.setTitle("SARIF");
        dialogo.setHeaderText("Notificar la alerta #" + alerta.id() + " (" + alerta.nombreZona() + ")\n"
                + "SARIF no envía el aviso: registre a quién avisó usted y por qué medio.");
        dialogo.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        TextField txtDestinatario = new TextField(anterior.destinatario());
        txtDestinatario.setPromptText("Ej.: Defensa Civil de Aluminé, jefe de brigada Zapala");
        txtDestinatario.setPrefColumnCount(32);
        ComboBox<MedioNotificacion> cmbMedio = new ComboBox<>(FXCollections.observableArrayList(MedioNotificacion.elegibles()));
        cmbMedio.setValue(anterior.medio());
        GridPane grilla = new GridPane();
        grilla.setHgap(10);
        grilla.setVgap(8);
        grilla.addRow(0, new Label("A quién se avisó:"), txtDestinatario);
        grilla.addRow(1, new Label("Medio:"), cmbMedio);
        dialogo.getDialogPane().setContent(grilla);
        dialogo.setOnShown(e -> txtDestinatario.requestFocus());
        dialogo.setResultConverter(boton -> boton == ButtonType.OK
                ? new DatosAviso(txtDestinatario.getText(), cmbMedio.getValue())
                : null);
        return dialogo.showAndWait();
    }

    /** Botón "Cerrar": la situación que originó la alerta ya se resolvió. */
    @FXML
    private void cerrar() {
        cambiarEstado(EstadoAlerta.CERRADA);
    }

    /** Botón "Descartar": la alerta no correspondía (por ejemplo, un falso positivo del satélite). */
    @FXML
    private void descartar() {
        cambiarEstado(EstadoAlerta.DESCARTADA);
    }

    /**
     * Botón "Aplicar nivel sugerido...": las alertas de riesgo proponen un nivel de alerta para la zona.
     * Le muestro al operador el fundamento ya armado con los datos de la alerta para que lo revise o lo
     * cambie, y recién cuando confirma registro el cambio de nivel (CU12) a su nombre.
     */
    @FXML
    private void aplicarSugerencia() {
        Alerta alerta = tabla.getSelectionModel().getSelectedItem();
        if (alerta == null) {
            Dialogos.aviso("Seleccione una alerta de la lista.");
            return;
        }
        if (alerta.nivelSugerido() == null) {
            Dialogos.aviso("La alerta seleccionada no sugiere ningún cambio de nivel.");
            return;
        }
        String propuesto = "Alerta automática #" + alerta.id() + ": " + alerta.descripcion();
        TextInputDialog dialogo = new TextInputDialog(propuesto.substring(0, Math.min(250, propuesto.length())));
        dialogo.setTitle("SARIF");
        dialogo.setHeaderText("Pasar " + alerta.nombreZona() + " a nivel " + alerta.nivelSugerido().nombre()
                + "\nRevise el fundamento que quedará registrado (hasta 250 caracteres).");
        dialogo.setContentText("Fundamento:");
        dialogo.getEditor().setPrefColumnCount(50);
        dialogo.showAndWait().ifPresent(fundamento -> {
            try {
                servicio.aplicarSugerencia(alerta, fundamento, Sesion.getUsuario().id());
                actualizar();
                Dialogos.info("Nivel de alerta de " + alerta.nombreZona() + " actualizado a " + alerta.nivelSugerido().nombre() + ".");
            } catch (ValidacionException e) {
                Dialogos.aviso(e.getMessage());
            } catch (SQLException e) {
                Dialogos.errorBase(e);
            }
        });
    }

    /**
     * Código común de los tres botones. Si la transición no es válida (por ejemplo, cerrar una alerta
     * que todavía está PENDIENTE, o tocar una CERRADA o DESCARTADA, que son estados finales), el servicio
     * lanza ValidacionException y yo solo muestro el mensaje como aviso.
     */
    private void cambiarEstado(EstadoAlerta nuevo) {
        Alerta alerta = tabla.getSelectionModel().getSelectedItem();
        if (alerta == null) {
            Dialogos.aviso("Seleccione una alerta de la lista.");
            return;
        }
        try {
            servicio.cambiarEstado(alerta, nuevo, Sesion.getUsuario().id());
            actualizar();
        } catch (ValidacionException e) {
            Dialogos.aviso(e.getMessage());
        } catch (SQLException e) {
            Dialogos.errorBase(e);
        }
    }
}
