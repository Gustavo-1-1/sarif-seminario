package sarif.controlador;

import javafx.collections.FXCollections;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.util.StringConverter;
import sarif.modelo.Recurso;
import sarif.modelo.TipoMarca;
import sarif.modelo.TipoRecurso;
import sarif.modelo.Zona;
import sarif.servicio.ServicioZonas;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Diálogos del menú que aparece al hacer clic en el mapa. Los separé de MapaControlador, que ya es largo:
 * cada uno arma la grilla, la precarga con lo que el operador escribió antes (si el servicio rechazó los datos,
 * el controlador lo vuelve a abrir con lo mismo) y devuelve lo ingresado, o vacío si se canceló. No validan
 * nada: las reglas están en los servicios.
 */
final class DialogosMapa {

    private DialogosMapa() {
    }

    /** Lo que se escribe en el alta del activo; la distancia va como texto y la convierte el controlador. */
    record DatosActivo(Zona zona, String nombre, String tipo, String distancia) {
    }

    record DatosUbicacion(Recurso recurso, String observaciones) {
    }

    record DatosMarca(TipoMarca tipo, String descripcion) {
    }

    /** Alta de un activo protegido en el punto (CU02). La zona se elige entre las que contienen el punto. */
    static Optional<DatosActivo> activo(double lat, double lon, List<Zona> zonas, DatosActivo anterior) {
        Dialog<DatosActivo> dialogo = crear("Registrar activo protegido en " + coordenadas(lat, lon),
                "Se lo tendrá en cuenta en las alertas por foco cercano de la zona.");
        ComboBox<Zona> cmbZona = new ComboBox<>(FXCollections.observableArrayList(zonas));
        cmbZona.setValue(anterior.zona());
        TextField txtNombre = new TextField(anterior.nombre());
        txtNombre.setPromptText("Ej.: Escuela rural, antena, planta de gas");
        txtNombre.setPrefColumnCount(28);
        ComboBox<String> cmbTipo = new ComboBox<>(FXCollections.observableArrayList(ServicioZonas.TIPOS_ACTIVO));
        cmbTipo.setValue(anterior.tipo());
        TextField txtDistancia = new TextField(anterior.distancia());
        txtDistancia.setPrefColumnCount(6);
        GridPane grilla = grilla();
        grilla.addRow(0, new Label("Zona:"), cmbZona);
        grilla.addRow(1, new Label("Nombre:"), txtNombre);
        grilla.addRow(2, new Label("Tipo:"), cmbTipo);
        grilla.addRow(3, new Label("Distancia de alerta (km):"), txtDistancia);
        dialogo.getDialogPane().setContent(grilla);
        dialogo.setOnShown(e -> txtNombre.requestFocus());
        dialogo.setResultConverter(boton -> boton == ButtonType.OK
                ? new DatosActivo(cmbZona.getValue(), txtNombre.getText(), cmbTipo.getValue(), txtDistancia.getText())
                : null);
        return dialogo.showAndWait();
    }

    /** Ubicación de un recurso desplegado en el punto; solo se ofrecen los recursos asignados. */
    static Optional<DatosUbicacion> ubicacion(double lat, double lon, List<Recurso> asignados, DatosUbicacion anterior) {
        Dialog<DatosUbicacion> dialogo = crear("Ubicar un recurso desplegado en " + coordenadas(lat, lon),
                "Si el recurso ya estaba en el mapa, pasa a este punto; queda registrado quién lo ubicó y cuándo.");
        ComboBox<Recurso> cmbRecurso = new ComboBox<>(FXCollections.observableArrayList(asignados));
        cmbRecurso.setConverter(new StringConverter<>() {
            @Override
            public String toString(Recurso r) {
                return r == null ? "" : r.denominacion() + " (" + (r.tipo() == TipoRecurso.AEREO ? "aéreo" : "terrestre")
                        + ", " + r.dotacion() + " personas)";
            }

            @Override
            public Recurso fromString(String texto) {
                return null;
            }
        });
        cmbRecurso.setValue(anterior.recurso());
        TextField txtObservaciones = new TextField(anterior.observaciones());
        txtObservaciones.setPromptText("Opcional. Ej.: combatiendo el flanco norte");
        txtObservaciones.setPrefColumnCount(28);
        GridPane grilla = grilla();
        grilla.addRow(0, new Label("Recurso:"), cmbRecurso);
        grilla.addRow(1, new Label("Observaciones:"), txtObservaciones);
        dialogo.getDialogPane().setContent(grilla);
        dialogo.setResultConverter(boton -> boton == ButtonType.OK
                ? new DatosUbicacion(cmbRecurso.getValue(), txtObservaciones.getText())
                : null);
        return dialogo.showAndWait();
    }

    /** Marca operativa en el punto. */
    static Optional<DatosMarca> marca(double lat, double lon, DatosMarca anterior) {
        Dialog<DatosMarca> dialogo = crear("Agregar una marca en " + coordenadas(lat, lon),
                "Queda visible en el mapa para toda la guardia hasta que alguien la quite.");
        ComboBox<TipoMarca> cmbTipo = new ComboBox<>(FXCollections.observableArrayList(TipoMarca.values()));
        cmbTipo.setValue(anterior.tipo());
        TextField txtDescripcion = new TextField(anterior.descripcion());
        txtDescripcion.setPromptText("Ej.: Tanque australiano de la estancia, huella cortada");
        txtDescripcion.setPrefColumnCount(28);
        GridPane grilla = grilla();
        grilla.addRow(0, new Label("Tipo:"), cmbTipo);
        grilla.addRow(1, new Label("Descripción:"), txtDescripcion);
        dialogo.getDialogPane().setContent(grilla);
        dialogo.setOnShown(e -> txtDescripcion.requestFocus());
        dialogo.setResultConverter(boton -> boton == ButtonType.OK
                ? new DatosMarca(cmbTipo.getValue(), txtDescripcion.getText())
                : null);
        return dialogo.showAndWait();
    }

    static String coordenadas(double lat, double lon) {
        return String.format(Locale.ROOT, "%.5f, %.5f", lat, lon);
    }

    private static <T> Dialog<T> crear(String titulo, String explicacion) {
        Dialog<T> dialogo = new Dialog<>();
        dialogo.setTitle("SARIF");
        dialogo.setHeaderText(titulo + "\n" + explicacion);
        dialogo.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        return dialogo;
    }

    private static GridPane grilla() {
        GridPane grilla = new GridPane();
        grilla.setHgap(10);
        grilla.setVgap(8);
        return grilla;
    }
}
