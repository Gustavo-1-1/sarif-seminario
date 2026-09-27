package sarif.controlador;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.VPos;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.Image;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import sarif.modelo.ActivoProtegido;
import sarif.modelo.Confianza;
import sarif.modelo.DireccionViento;
import sarif.modelo.FocoCalor;
import sarif.modelo.IndiceRiesgo;
import sarif.modelo.MarcaMapa;
import sarif.modelo.NivelAlerta;
import sarif.modelo.NivelRiesgo;
import sarif.modelo.Permiso;
import sarif.modelo.PosicionRecurso;
import sarif.modelo.Provincia;
import sarif.modelo.Recurso;
import sarif.modelo.RegistroMeteo;
import sarif.modelo.RegistroNdvi;
import sarif.modelo.TipoMarca;
import sarif.modelo.TipoRecurso;
import sarif.modelo.Vertice;
import sarif.modelo.Zona;
import sarif.servicio.ProyeccionMercator;
import sarif.servicio.ServicioMapa;
import sarif.servicio.ServicioSincronizacion;
import sarif.servicio.ServicioUbicaciones;
import sarif.servicio.ServicioZonas;
import sarif.servicio.Sesion;
import sarif.servicio.ValidacionException;

import java.io.ByteArrayInputStream;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Controlador de la pestaña "Mapa de riesgo": un mapa topográfico por mosaicos (OpenTopoMap, con relieve y
 * curvas de nivel, de todo el país y los países vecinos) y, encima, las zonas pintadas con el color de su
 * nivel de riesgo del día, la imagen NDVI de Sentinel-2 de cada zona, los focos recientes y los activos
 * protegidos con su radio de alerta.
 * <p>
 * Cómo funciona el mapa: la vista tiene un zoom (con decimales) y la posición de su esquina superior izquierda
 * en píxeles del "mundo" de Web Mercator (ProyeccionMercator). En cada redibujo calculo qué mosaicos entran en
 * la pantalla y dibujo los que ya tengo en memoria. Los que faltan los pido en segundo plano (primero a la
 * caché del disco y al mapa general que trae el programa, y si no están, a internet) y mientras tanto dibujo
 * en su lugar un pedazo ampliado de un mosaico de menos zoom, así nunca queda un hueco blanco. Cuando llega
 * un mosaico, vuelvo a dibujar. Nada de esto frena la pantalla: la red y el disco van en hilos aparte.
 * <p>
 * Para dar de alta una zona (CU01) se puede arrastrar un rectángulo o dibujar un polígono haciendo clic en
 * cada vértice; la forma pasa a la pestaña Zonas, donde se completan los demás datos.
 * <p>
 * Sin esas herramientas activas, un clic en el mapa abre un menú para usar ese punto: registrar un activo
 * protegido (CU02), ubicar un recurso desplegado, poner o quitar una marca operativa y copiar las coordenadas.
 */
public class MapaControlador {

    // Vistas rápidas: lonMin, lonMax, latMin, latMax.
    private static final double[] ARGENTINA = {-76.0, -52.0, -55.5, -21.0};
    private static final double[] NEUQUEN = {-72.3, -67.7, -41.4, -35.8};
    private static final double ZOOM_MINIMO = 3;
    private static final double ZOOM_MAXIMO = 18;
    // Cuántos mosaicos guardo en memoria (unos 250 KB cada uno ya decodificado): los más viejos se descartan.
    private static final int MOSAICOS_EN_MEMORIA = 400;
    // Distancia en píxeles de pantalla por debajo de la cual un "arrastre" cuenta como clic.
    private static final double TOLERANCIA_CLIC = 5;

    @FXML
    private Pane contenedor;
    @FXML
    private Canvas lienzo;
    @FXML
    private Label lblFecha;
    @FXML
    private CheckBox chkZonas;
    @FXML
    private CheckBox chkNdvi;
    @FXML
    private CheckBox chkFocos;
    @FXML
    private CheckBox chkFocosRegion;
    @FXML
    private Label lblEstadoFocos;
    @FXML
    private CheckBox chkActivos;
    @FXML
    private CheckBox chkDespliegue;
    @FXML
    private ComboBox<Provincia> cmbProvincia;
    @FXML
    private CheckBox chkViento;
    @FXML
    private Spinner<Integer> spDiasFocos;
    @FXML
    private Label lblEstadoMapa;
    @FXML
    private Label lblEstadoNdvi;
    @FXML
    private VBox leyenda;
    @FXML
    private Label lblDetalle;
    @FXML
    private Label lblCoordenadas;
    @FXML
    private ToggleButton tbRectangulo;
    @FXML
    private ToggleButton tbPoligono;
    @FXML
    private Label lblAyudaForma;
    @FXML
    private Label lblSeleccion;
    @FXML
    private Button btnDeshacer;
    @FXML
    private Button btnBorrar;
    @FXML
    private Button btnCrearZona;
    @FXML
    private Label lblFuentes;

    private final ServicioMapa servicio = new ServicioMapa();
    private final ServicioUbicaciones servicioUbicaciones = new ServicioUbicaciones();
    private final ServicioZonas servicioZonas = new ServicioZonas();
    private ServicioMapa.DatosMapa datos;
    private LocalDate fechaDatos;

    // Recursos desplegados y marcas operativas que se ven en el mapa, y el menú del último clic.
    private List<PosicionRecurso> desplegados = List.of();
    private List<MarcaMapa> marcas = List.of();
    private ContextMenu menu;

    // Vista: zoom actual y esquina superior izquierda de la pantalla en píxeles del mundo a ese zoom.
    private double zoom = 6;
    private double origenX;
    private double origenY;
    private boolean encuadrado;

    // Mosaicos. La memoria es un LinkedHashMap en "orden de acceso": el primero es el que hace más tiempo
    // que no se usa, y removeEldestEntry lo descarta cuando me paso del tope. Solo lo toca el hilo de JavaFX.
    private final Map<String, Image> memoria = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Image> masViejo) {
            return size() > MOSAICOS_EN_MEMORIA;
        }
    };
    // Estos sí los tocan los hilos de fondo, por eso son concurrentes o volatile.
    private final Set<String> pendientes = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> faltantes = new ConcurrentHashMap<>();
    private volatile Set<String> buscados = Set.of();
    private volatile long sinConexionHasta;
    // Un hilo para leer del disco y dos para descargar (las reglas de OpenTopoMap piden pocas conexiones a la vez).
    private final ExecutorService lecturas = Executors.newSingleThreadExecutor(MapaControlador::hiloDeFondo);
    private final ExecutorService descargas = Executors.newFixedThreadPool(2, MapaControlador::hiloDeFondo);
    private final ExecutorService ndviEjecutor = Executors.newSingleThreadExecutor(MapaControlador::hiloDeFondo);
    private boolean redibujoPedido;

    // NDVI: imagen por "idZona_fecha", las que se están pidiendo y el último problema, para no insistir.
    private final Map<String, Image> imagenesNdvi = new ConcurrentHashMap<>();
    private final Set<String> ndviPendientes = ConcurrentHashMap.newKeySet();
    private volatile String problemaNdvi;

    // Focos satelitales de toda la región (FIRMS en vivo). Los pido en un hilo aparte y solo el hilo de
    // JavaFX cambia la lista; "pedidoFocos" me sirve para descartar una respuesta vieja si cambió la fecha.
    private final ExecutorService focosEjecutor = Executors.newSingleThreadExecutor(MapaControlador::hiloDeFondo);
    private List<FocoCalor> focosRegion = List.of();
    private String pedidoFocos;

    // Arrastre del mouse y forma de la zona nueva (en coordenadas geográficas, así no depende del zoom).
    private double presionX;
    private double presionY;
    private double arrastreX;
    private double arrastreY;
    private boolean moviendo;
    private double[] rectangulo;                       // {latA, lonA, latB, lonB}
    private final List<Vertice> poligono = new ArrayList<>();
    private boolean poligonoCerrado;
    private double mouseX = -1;
    private double mouseY = -1;
    private Consumer<double[]> alCrearRectangulo;
    private Consumer<List<Vertice>> alCrearPoligono;

    private static Thread hiloDeFondo(Runnable r) {
        Thread t = new Thread(r, "sarif-mapa");
        t.setDaemon(true);
        return t;
    }

    @FXML
    private void initialize() {
        lblFuentes.setText(ServicioMapa.ATRIBUCION + ". Los mosaicos que se ven quedan guardados en "
                + servicio.carpetaMosaicos() + " para usarlos sin conexión.");
        spDiasFocos.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 10, 2));
        cmbProvincia.getItems().setAll(Provincia.TODAS);
        // Celda propia para que, al limpiar la selección, vuelva a verse el texto de ayuda.
        cmbProvincia.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(Provincia p, boolean vacia) {
                super.updateItem(p, vacia);
                setText(vacia || p == null ? cmbProvincia.getPromptText() : p.nombre());
            }
        });
        cmbProvincia.valueProperty().addListener((obs, a, p) -> {
            if (p != null) {
                verProvincia(p);
            }
        });
        spDiasFocos.valueProperty().addListener((obs, a, b) -> actualizar());
        for (CheckBox capa : new CheckBox[]{chkZonas, chkFocos, chkActivos, chkDespliegue, chkViento}) {
            capa.selectedProperty().addListener((obs, a, b) -> redibujar());
        }
        chkViento.selectedProperty().addListener((obs, a, b) -> armarLeyenda());
        chkFocosRegion.selectedProperty().addListener((obs, a, activo) -> {
            if (activo) {
                cargarFocosRegion();
            } else {
                lblEstadoFocos.setText("");
            }
            redibujar();
        });
        chkNdvi.selectedProperty().addListener((obs, a, activo) -> {
            if (activo) {
                cargarNdvi();
            }
            armarLeyenda();
            redibujar();
        });
        // El Canvas no se agranda solo: lo ato al tamaño del panel y redibujo cada vez que cambia.
        lienzo.widthProperty().bind(contenedor.widthProperty());
        lienzo.heightProperty().bind(contenedor.heightProperty());
        lienzo.widthProperty().addListener((obs, a, b) -> alCambiarTamano());
        lienzo.heightProperty().addListener((obs, a, b) -> alCambiarTamano());
        Rectangle recorte = new Rectangle();
        recorte.widthProperty().bind(contenedor.widthProperty());
        recorte.heightProperty().bind(contenedor.heightProperty());
        contenedor.setClip(recorte);

        lienzo.setOnScroll(this::zoom);
        lienzo.setOnMousePressed(this::presionar);
        lienzo.setOnMouseDragged(this::arrastrar);
        lienzo.setOnMouseReleased(this::soltar);
        lienzo.setOnMouseMoved(this::mover);
        lienzo.setOnMouseExited(e -> {
            mouseX = -1;
            redibujar();
        });
        tbRectangulo.selectedProperty().addListener((obs, a, b) -> cambiarModo());
        tbPoligono.selectedProperty().addListener((obs, a, b) -> cambiarModo());
        cambiarModo();
    }

    /**
     * La ventana principal me pasa qué hacer con la forma marcada: abrir el alta de zona con el rectángulo
     * ({latMin, latMax, lonMin, lonMax}) o con los vértices del polígono.
     */
    public void setAlCrearZona(Consumer<double[]> rectangulo, Consumer<List<Vertice>> poligono) {
        this.alCrearRectangulo = rectangulo;
        this.alCrearPoligono = poligono;
    }

    /** Recargo los datos de la fecha de trabajo; lo llama PrincipalControlador al entrar a la pestaña. */
    public void actualizar() {
        fechaDatos = Sesion.getFechaTrabajo();
        lblFecha.setText("Riesgo del " + fechaDatos.format(Dialogos.FECHA)
                + " · focos de los últimos " + spDiasFocos.getValue() + " día(s)");
        try {
            datos = servicio.datos(fechaDatos, spDiasFocos.getValue());
            desplegados = servicioUbicaciones.desplegados();
            marcas = servicioUbicaciones.marcas();
            armarLeyenda();
            if (chkNdvi.isSelected()) {
                cargarNdvi();
            }
            if (chkFocosRegion.isSelected()) {
                cargarFocosRegion();
            }
            redibujar();
        } catch (SQLException e) {
            Dialogos.errorBase(e);
        }
    }

    /**
     * Pido a FIRMS los focos de toda la región para la fecha y los días elegidos. La consulta tarda unos
     * segundos, así que va en un hilo aparte; el servicio los guarda unos minutos, así que volver a entrar a
     * la pestaña no repite la consulta.
     */
    private void cargarFocosRegion() {
        LocalDate fecha = fechaDatos == null ? Sesion.getFechaTrabajo() : fechaDatos;
        int dias = spDiasFocos.getValue();
        String pedido = fecha + "/" + dias;
        pedidoFocos = pedido;
        lblEstadoFocos.setText("Pidiendo a NASA FIRMS los focos de la región...");
        focosEjecutor.submit(() -> {
            ServicioMapa.FocosRegion r = servicio.focosRegion(fecha, dias);
            Platform.runLater(() -> {
                if (!pedido.equals(pedidoFocos)) {
                    return;
                }
                focosRegion = r.focos();
                if (r.fallo()) {
                    lblEstadoFocos.setText("FIRMS no respondió: " + r.problema());
                } else {
                    lblEstadoFocos.setText(r.focos().isEmpty()
                            ? "FIRMS no detectó focos en la región en esos días."
                            : r.focos().size() + " foco(s) detectados por el satélite en la región (círculos chicos). "
                            + "Los que caen en las zonas se guardan al sincronizar.");
                }
                redibujar();
            });
        });
    }

    // ------------------------------------------------------------------ vista

    private void alCambiarTamano() {
        if (!encuadrado && lienzo.getWidth() > 0 && lienzo.getHeight() > 0) {
            encuadrado = true;
            verNeuquen();
        } else {
            redibujar();
        }
    }

    @FXML
    private void verArgentina() {
        cmbProvincia.getSelectionModel().clearSelection();
        encuadrar(ARGENTINA[2], ARGENTINA[3], ARGENTINA[0], ARGENTINA[1]);
    }

    @FXML
    private void verNeuquen() {
        cmbProvincia.getSelectionModel().clearSelection();
        encuadrar(NEUQUEN[2], NEUQUEN[3], NEUQUEN[0], NEUQUEN[1]);
    }

    /**
     * Combo "Ir a una provincia": encuadro su rectángulo con un margen del 5 % por lado. El mapa general que
     * trae el programa tiene todo el país hasta el zoom 6; el detalle de las demás provincias se descarga
     * la primera vez (sin conexión se ve ampliado, algo borroso).
     */
    private void verProvincia(Provincia p) {
        double margenLat = (p.latMax() - p.latMin()) * 0.05, margenLon = (p.lonMax() - p.lonMin()) * 0.05;
        encuadrar(p.latMin() - margenLat, p.latMax() + margenLat, p.lonMin() - margenLon, p.lonMax() + margenLon);
    }

    /** Botón "Zonas": encuadro el rectángulo que contiene a todas las zonas, con un margen. */
    @FXML
    private void verZonas() {
        cmbProvincia.getSelectionModel().clearSelection();
        if (datos == null || datos.zonas().isEmpty()) {
            verNeuquen();
            return;
        }
        double latMin = datos.zonas().stream().mapToDouble(Zona::getLatitudMin).min().orElseThrow();
        double latMax = datos.zonas().stream().mapToDouble(Zona::getLatitudMax).max().orElseThrow();
        double lonMin = datos.zonas().stream().mapToDouble(Zona::getLongitudMin).min().orElseThrow();
        double lonMax = datos.zonas().stream().mapToDouble(Zona::getLongitudMax).max().orElseThrow();
        double margenLat = (latMax - latMin) * 0.08, margenLon = (lonMax - lonMin) * 0.08;
        encuadrar(latMin - margenLat, latMax + margenLat, lonMin - margenLon, lonMax + margenLon);
    }

    // Elijo el zoom que hace entrar el área y la centro en la pantalla.
    private void encuadrar(double latMin, double latMax, double lonMin, double lonMax) {
        double ancho = lienzo.getWidth(), alto = lienzo.getHeight();
        zoom = limitarZoom(ProyeccionMercator.zoomParaEncuadrar(latMin, latMax, lonMin, lonMax, ancho, alto));
        double cx = (ProyeccionMercator.x(lonMin, zoom) + ProyeccionMercator.x(lonMax, zoom)) / 2;
        double cy = (ProyeccionMercator.y(latMin, zoom) + ProyeccionMercator.y(latMax, zoom)) / 2;
        origenX = cx - ancho / 2;
        origenY = cy - alto / 2;
        redibujar();
    }

    private static double limitarZoom(double z) {
        return Math.max(ZOOM_MINIMO, Math.min(ZOOM_MAXIMO, z));
    }

    /**
     * Rueda del mouse: cada paso acerca o aleja medio nivel de zoom, dejando quieto el punto bajo el cursor.
     * Al cambiar el zoom el mundo se agranda por 2^(diferencia), así que escalo la posición del cursor en el
     * mundo por ese factor y corro el origen para que siga debajo del mouse.
     */
    private void zoom(ScrollEvent e) {
        if (e.getDeltaY() != 0) {
            cambiarZoom(zoom + (e.getDeltaY() > 0 ? 0.5 : -0.5), e.getX(), e.getY());
        }
    }

    /** Botón "+": un nivel entero más de zoom (el doble de detalle), alrededor del centro de la vista. */
    @FXML
    private void acercar() {
        cambiarZoom(Math.floor(zoom) + 1, lienzo.getWidth() / 2, lienzo.getHeight() / 2);
    }

    /** Botón "−": un nivel entero menos de zoom, alrededor del centro de la vista. */
    @FXML
    private void alejar() {
        cambiarZoom(Math.ceil(zoom) - 1, lienzo.getWidth() / 2, lienzo.getHeight() / 2);
    }

    // Cambio el zoom dejando quieto el punto de pantalla (px, py): el del cursor o el centro.
    private void cambiarZoom(double pedido, double px, double py) {
        double nuevo = limitarZoom(pedido);
        double factor = Math.pow(2, nuevo - zoom);
        origenX = (origenX + px) * factor - px;
        origenY = (origenY + py) * factor - py;
        zoom = nuevo;
        redibujar();
    }

    private void presionar(MouseEvent e) {
        presionX = arrastreX = e.getX();
        presionY = arrastreY = e.getY();
        moviendo = false;
        if (tbRectangulo.isSelected() && e.getButton() == MouseButton.PRIMARY) {
            rectangulo = new double[]{latitudDe(e.getY()), longitudDe(e.getX()), latitudDe(e.getY()), longitudDe(e.getX())};
        }
    }

    /**
     * Arrastrar mueve el mapa, salvo en modo rectángulo con el botón izquierdo, que dibuja el rectángulo
     * (en ese modo el mapa se mueve arrastrando con el botón derecho).
     */
    private void arrastrar(MouseEvent e) {
        if (!moviendo && Math.hypot(e.getX() - presionX, e.getY() - presionY) > TOLERANCIA_CLIC) {
            moviendo = true;
            // Al mover el mapa dejo de estar "en" la provincia elegida, así se la puede volver a elegir.
            cmbProvincia.getSelectionModel().clearSelection();
        }
        if (tbRectangulo.isSelected() && e.getButton() == MouseButton.PRIMARY && rectangulo != null) {
            rectangulo[2] = latitudDe(e.getY());
            rectangulo[3] = longitudDe(e.getX());
            mostrarSeleccion();
        } else {
            origenX -= e.getX() - arrastreX;
            origenY -= e.getY() - arrastreY;
            arrastreX = e.getX();
            arrastreY = e.getY();
        }
        mover(e);
    }

    /**
     * Al soltar sin haber movido el mouse, es un clic. En modo polígono el clic izquierdo agrega un vértice
     * (o cierra el polígono si es sobre el primero, o con doble clic) y el derecho borra el último. Sin
     * herramienta de dibujo, cualquiera de los dos botones abre el menú del punto.
     */
    private void soltar(MouseEvent e) {
        if (moviendo) {
            return;
        }
        if (!tbPoligono.isSelected()) {
            if (!tbRectangulo.isSelected() && e.getClickCount() == 1
                    && (e.getButton() == MouseButton.PRIMARY || e.getButton() == MouseButton.SECONDARY)) {
                mostrarMenu(e);
            }
            return;
        }
        if (e.getButton() == MouseButton.SECONDARY) {
            deshacerVertice();
            return;
        }
        if (e.getButton() != MouseButton.PRIMARY || poligonoCerrado) {
            return;
        }
        boolean sobreElPrimero = poligono.size() >= 3 && Math.hypot(
                pantallaX(poligono.get(0).longitud()) - e.getX(), pantallaY(poligono.get(0).latitud()) - e.getY()) <= 9;
        if (sobreElPrimero || (e.getClickCount() >= 2 && poligono.size() >= 3)) {
            poligonoCerrado = true;
        } else if (poligono.size() < Zona.MAXIMO_VERTICES) {
            poligono.add(new Vertice(redondear(latitudDe(e.getY())), redondear(longitudDe(e.getX()))));
        }
        mostrarSeleccion();
        redibujar();
    }

    // ------------------------------------------------------------------ menú del punto

    /**
     * Menú del punto donde se hizo clic. Registrar el activo solo se puede dentro de una zona (las alertas son
     * por zona); ubicar recursos y poner marcas, en cualquier lado, porque un recurso en camino o un punto de
     * agua pueden estar fuera. Si el clic cae sobre una marca, ofrezco quitarla. Sin el permiso de operar la
     * guardia las opciones que escriben quedan deshabilitadas (el servicio lo controla igual).
     */
    private void mostrarMenu(MouseEvent e) {
        if (menu != null) {
            menu.hide();
        }
        double lat = redondear(latitudDe(e.getY())), lon = redondear(longitudDe(e.getX()));
        List<Zona> zonasDelPunto = datos == null ? List.of() : datos.zonas().stream().filter(z -> z.contiene(lat, lon)).toList();
        boolean opera = Restricciones.puede(Permiso.OPERAR);

        MenuItem encabezado = new MenuItem(DialogosMapa.coordenadas(lat, lon) + " · "
                + (zonasDelPunto.isEmpty() ? "fuera de las zonas" : zonasDelPunto.get(0).getNombre()));
        encabezado.setDisable(true);
        MenuItem activo = new MenuItem(zonasDelPunto.isEmpty()
                ? "Registrar activo protegido (solo dentro de una zona)" : "Registrar activo protegido aquí...");
        activo.setDisable(zonasDelPunto.isEmpty());
        activo.setOnAction(a -> registrarActivo(lat, lon, zonasDelPunto));
        MenuItem recurso = new MenuItem("Ubicar recurso desplegado aquí...");
        recurso.setDisable(!opera);
        recurso.setOnAction(a -> ubicarRecurso(lat, lon));
        MenuItem marca = new MenuItem("Agregar marca aquí (puesto de comando, punto de agua, peligro...)...");
        marca.setDisable(!opera);
        marca.setOnAction(a -> agregarMarca(lat, lon));
        MenuItem copiar = new MenuItem("Copiar coordenadas");
        copiar.setOnAction(a -> {
            ClipboardContent contenido = new ClipboardContent();
            contenido.putString(DialogosMapa.coordenadas(lat, lon));
            Clipboard.getSystemClipboard().setContent(contenido);
        });

        menu = new ContextMenu(encabezado, new SeparatorMenuItem(), activo, recurso, marca);
        MarcaMapa existente = chkDespliegue.isSelected() ? marcaEn(e.getX(), e.getY()) : null;
        if (existente != null) {
            MenuItem quitar = new MenuItem("Quitar la marca \"" + existente.descripcion() + "\"");
            quitar.setDisable(!opera);
            quitar.setOnAction(a -> quitarMarca(existente));
            menu.getItems().add(quitar);
        }
        menu.getItems().addAll(new SeparatorMenuItem(), copiar);
        menu.show(lienzo, e.getScreenX(), e.getScreenY());
    }

    /** CU02 desde el mapa: el punto ya viene dado; si el servicio rechaza los datos, reabro el diálogo con lo escrito. */
    private void registrarActivo(double lat, double lon, List<Zona> zonasDelPunto) {
        DialogosMapa.DatosActivo datosActivo = new DialogosMapa.DatosActivo(zonasDelPunto.get(0), "",
                ServicioZonas.TIPOS_ACTIVO.get(0), "5");
        while (true) {
            Optional<DialogosMapa.DatosActivo> respuesta = DialogosMapa.activo(lat, lon, zonasDelPunto, datosActivo);
            if (respuesta.isEmpty()) {
                return;
            }
            datosActivo = respuesta.get();
            double distancia;
            try {
                distancia = Double.parseDouble(datosActivo.distancia().trim().replace(',', '.'));
            } catch (NumberFormatException ex) {
                Dialogos.aviso("La distancia de alerta tiene que ser un número (por ejemplo 5 o 2,5).");
                continue;
            }
            Zona zona = datosActivo.zona();
            try {
                servicioZonas.registrarActivo(new ActivoProtegido(0, zona == null ? 0 : zona.getId(), datosActivo.nombre(),
                        datosActivo.tipo(), lat, lon, distancia));
                actualizar();
                return;
            } catch (ValidacionException ex) {
                Dialogos.aviso(ex.getMessage());
            } catch (SQLException ex) {
                Dialogos.errorBase(ex);
                return;
            }
        }
    }

    private void ubicarRecurso(double lat, double lon) {
        List<Recurso> asignados;
        try {
            asignados = servicioUbicaciones.recursosAsignados();
        } catch (SQLException ex) {
            Dialogos.errorBase(ex);
            return;
        }
        if (asignados.isEmpty()) {
            Dialogos.aviso("No hay recursos asignados. Un recurso queda desplegado al confirmar una asignación "
                    + "en la pestaña Despliegue; recién ahí se lo puede ubicar en el mapa.");
            return;
        }
        DialogosMapa.DatosUbicacion ubicacion = new DialogosMapa.DatosUbicacion(asignados.get(0), "");
        while (true) {
            Optional<DialogosMapa.DatosUbicacion> respuesta = DialogosMapa.ubicacion(lat, lon, asignados, ubicacion);
            if (respuesta.isEmpty()) {
                return;
            }
            ubicacion = respuesta.get();
            if (ubicacion.recurso() == null) {
                Dialogos.aviso("Elija el recurso a ubicar.");
                continue;
            }
            try {
                servicioUbicaciones.ubicarRecurso(ubicacion.recurso().id(), lat, lon, ubicacion.observaciones(),
                        Sesion.getUsuario().id());
                actualizar();
                return;
            } catch (ValidacionException ex) {
                Dialogos.aviso(ex.getMessage());
            } catch (SQLException ex) {
                Dialogos.errorBase(ex);
                return;
            }
        }
    }

    private void agregarMarca(double lat, double lon) {
        DialogosMapa.DatosMarca datosMarca = new DialogosMapa.DatosMarca(TipoMarca.PUESTO_COMANDO, "");
        while (true) {
            Optional<DialogosMapa.DatosMarca> respuesta = DialogosMapa.marca(lat, lon, datosMarca);
            if (respuesta.isEmpty()) {
                return;
            }
            datosMarca = respuesta.get();
            try {
                servicioUbicaciones.agregarMarca(datosMarca.tipo(), datosMarca.descripcion(), lat, lon, Sesion.getUsuario().id());
                actualizar();
                return;
            } catch (ValidacionException ex) {
                Dialogos.aviso(ex.getMessage());
            } catch (SQLException ex) {
                Dialogos.errorBase(ex);
                return;
            }
        }
    }

    private void quitarMarca(MarcaMapa marca) {
        if (!Dialogos.confirmar("¿Quitar del mapa la marca \"" + marca.descripcion() + "\" (" + marca.tipo().texto() + ")?")) {
            return;
        }
        try {
            servicioUbicaciones.quitarMarca(marca.id(), Sesion.getUsuario().id());
        } catch (ValidacionException ex) {
            Dialogos.aviso(ex.getMessage());
        } catch (SQLException ex) {
            Dialogos.errorBase(ex);
        }
        actualizar();
    }

    // Tolerancia en píxeles para "acertarle" a un símbolo con el mouse.
    private MarcaMapa marcaEn(double sx, double sy) {
        for (MarcaMapa m : marcas) {
            if (Math.hypot(pantallaX(m.longitud()) - sx, pantallaY(m.latitud()) - sy) <= 10) {
                return m;
            }
        }
        return null;
    }

    private PosicionRecurso recursoEn(double sx, double sy) {
        for (PosicionRecurso p : desplegados) {
            if (Math.hypot(pantallaX(p.longitud()) - sx, pantallaY(p.latitud()) - sy) <= 11) {
                return p;
            }
        }
        return null;
    }

    /** Al mover el mouse muestro las coordenadas del punto y el detalle de lo que haya debajo. */
    private void mover(MouseEvent e) {
        mouseX = e.getX();
        mouseY = e.getY();
        double lat = latitudDe(e.getY()), lon = longitudDe(e.getX());
        lblCoordenadas.setText(String.format(Locale.ROOT, "Lat %.5f · Lon %.5f", lat, lon));
        lblDetalle.setText(detalle(e.getX(), e.getY(), lat, lon));
        redibujar();
    }

    private double latitudDe(double pantallaY) {
        return ProyeccionMercator.latitud(origenY + pantallaY, zoom);
    }

    // La longitud la llevo al rango -180..180, por si se movió el mapa más allá del antimeridiano.
    private double longitudDe(double pantallaX) {
        double lon = ProyeccionMercator.longitud(origenX + pantallaX, zoom);
        return ((lon + 180) % 360 + 360) % 360 - 180;
    }

    private double pantallaX(double longitud) {
        return ProyeccionMercator.x(longitud, zoom) - origenX;
    }

    private double pantallaY(double latitud) {
        return ProyeccionMercator.y(latitud, zoom) - origenY;
    }

    // Cinco decimales, como las columnas DECIMAL(8,5) de la base (un metro, aprox.).
    private static double redondear(double grados) {
        return Math.round(grados * 1e5) / 1e5;
    }

    // ------------------------------------------------------------------ zona nueva

    private void cambiarModo() {
        rectangulo = null;
        poligono.clear();
        poligonoCerrado = false;
        boolean dibujando = tbRectangulo.isSelected() || tbPoligono.isSelected();
        lienzo.setCursor(dibujando ? Cursor.CROSSHAIR : Cursor.DEFAULT);
        if (tbRectangulo.isSelected()) {
            lblAyudaForma.setText("Arrastre con el botón izquierdo para marcar el rectángulo. "
                    + "Para mover el mapa, arrastre con el derecho.");
        } else if (tbPoligono.isSelected()) {
            lblAyudaForma.setText("Haga clic en cada vértice. Para cerrar el polígono, clic sobre el primer vértice "
                    + "o doble clic. Clic derecho: borra el último vértice. Arrastrar mueve el mapa.");
        } else {
            lblAyudaForma.setText("Elija la forma de la zona para dibujarla en el mapa.");
        }
        mostrarSeleccion();
        redibujar();
    }

    /** Muestro las coordenadas o los vértices de la forma y habilito los botones según corresponda. */
    private void mostrarSeleccion() {
        btnDeshacer.setDisable(!tbPoligono.isSelected() || poligono.isEmpty());
        btnBorrar.setDisable(rectangulo == null && poligono.isEmpty());
        btnCrearZona.setDisable(true);
        if (rectangulo != null) {
            double[] c = coordenadasRectangulo();
            lblSeleccion.setText(String.format(Locale.ROOT, "Lat %.4f a %.4f%nLon %.4f a %.4f", c[0], c[1], c[2], c[3]));
            btnCrearZona.setDisable(Math.abs(pantallaX(c[3]) - pantallaX(c[2])) < 3 || Math.abs(pantallaY(c[0]) - pantallaY(c[1])) < 3);
        } else if (tbPoligono.isSelected()) {
            if (poligono.isEmpty()) {
                lblSeleccion.setText("");
            } else if (!poligonoCerrado) {
                lblSeleccion.setText(poligono.size() + " vértice(s). Falta cerrar el polígono.");
            } else {
                // Uso la misma validación del alta, así el problema aparece acá y no recién al guardar.
                Zona prueba = new Zona();
                prueba.setNombre("prueba");
                prueba.setVertices(poligono);
                List<String> errores = prueba.validar();
                lblSeleccion.setText(errores.isEmpty()
                        ? String.format(Locale.ROOT, "Polígono cerrado de %d vértices.%nLat %.4f a %.4f · Lon %.4f a %.4f",
                        poligono.size(), prueba.getLatitudMin(), prueba.getLatitudMax(), prueba.getLongitudMin(), prueba.getLongitudMax())
                        : String.join("\n", errores));
                btnCrearZona.setDisable(!errores.isEmpty());
            }
        } else {
            lblSeleccion.setText("");
        }
    }

    // Devuelvo {latMin, latMax, lonMin, lonMax} del rectángulo marcado, sin importar hacia dónde se arrastró.
    private double[] coordenadasRectangulo() {
        return new double[]{redondear(Math.min(rectangulo[0], rectangulo[2])), redondear(Math.max(rectangulo[0], rectangulo[2])),
                redondear(Math.min(rectangulo[1], rectangulo[3])), redondear(Math.max(rectangulo[1], rectangulo[3]))};
    }

    @FXML
    private void deshacerVertice() {
        if (!poligono.isEmpty()) {
            if (poligonoCerrado) {
                poligonoCerrado = false;
            } else {
                poligono.remove(poligono.size() - 1);
            }
            mostrarSeleccion();
            redibujar();
        }
    }

    @FXML
    private void borrarSeleccion() {
        rectangulo = null;
        poligono.clear();
        poligonoCerrado = false;
        mostrarSeleccion();
        redibujar();
    }

    /** Botón "Crear zona con esta forma": paso la forma a la pestaña Zonas para completar el alta (CU01). */
    @FXML
    private void crearZona() {
        if (rectangulo != null && alCrearRectangulo != null) {
            double[] c = coordenadasRectangulo();
            tbRectangulo.setSelected(false);
            alCrearRectangulo.accept(c);
        } else if (poligonoCerrado && alCrearPoligono != null) {
            List<Vertice> vertices = List.copyOf(poligono);
            tbPoligono.setSelected(false);
            alCrearPoligono.accept(vertices);
        }
    }

    // ------------------------------------------------------------------ mosaicos

    /**
     * Pido un mosaico en segundo plano. Primero lo busco en el disco (caché o mapa general); si no está y
     * hay conexión, lo descargo. Si ya lo estoy buscando, o falló hace poco, no hago nada.
     */
    private void pedirMosaico(int z, int x, int y) {
        String clave = z + "/" + x + "/" + y;
        Long reintento = faltantes.get(clave);
        if (memoria.containsKey(clave) || (reintento != null && reintento > System.currentTimeMillis()) || !pendientes.add(clave)) {
            return;
        }
        lecturas.submit(() -> {
            byte[] png = servicio.mosaicoGuardado(z, x, y);
            if (png != null) {
                entregar(clave, png);
            } else if (System.currentTimeMillis() < sinConexionHasta) {
                faltantes.put(clave, System.currentTimeMillis() + 15_000);
                pendientes.remove(clave);
            } else {
                descargas.submit(() -> descargar(clave, z, x, y));
            }
        });
    }

    private void descargar(String clave, int z, int x, int y) {
        // Si mientras esperaba su turno el usuario se fue a otra parte del mapa, no lo bajo.
        if (!buscados.contains(clave)) {
            pendientes.remove(clave);
            return;
        }
        ServicioMapa.Descarga d = servicio.descargarMosaico(z, x, y);
        if (d.fallo()) {
            // Sin conexión: durante 30 segundos no intento descargar nada más y uso lo que hay en el disco.
            sinConexionHasta = System.currentTimeMillis() + 30_000;
            faltantes.put(clave, sinConexionHasta);
            pendientes.remove(clave);
            Platform.runLater(this::pedirRedibujo);
        } else if (d.datos() == null) {
            faltantes.put(clave, Long.MAX_VALUE);
            pendientes.remove(clave);
        } else {
            entregar(clave, d.datos());
        }
    }

    // Decodifico el PNG en el hilo de fondo y lo paso a la memoria desde el hilo de JavaFX.
    private void entregar(String clave, byte[] png) {
        Image imagen = new Image(new ByteArrayInputStream(png));
        Platform.runLater(() -> {
            if (!imagen.isError()) {
                memoria.put(clave, imagen);
            }
            pendientes.remove(clave);
            pedirRedibujo();
        });
    }

    // Cuando llegan muchos mosaicos seguidos, junto los redibujos en uno solo.
    private void pedirRedibujo() {
        if (!redibujoPedido) {
            redibujoPedido = true;
            Platform.runLater(() -> {
                redibujoPedido = false;
                redibujar();
            });
        }
    }

    /**
     * Dibujo los mosaicos que entran en la pantalla. Uso el nivel de zoom entero más cercano al actual y
     * estiro los mosaicos por la diferencia (por ejemplo, con zoom 9,5 dibujo los del 10 al 71 %).
     */
    private void dibujarMosaicos(GraphicsContext g, double ancho, double alto) {
        int z = (int) Math.max(0, Math.min(ServicioMapa.ZOOM_MAXIMO_MOSAICOS, Math.round(zoom)));
        double lado = ProyeccionMercator.TAMANO_MOSAICO * Math.pow(2, zoom - z);
        int n = 1 << z;
        int x0 = (int) Math.floor(origenX / lado), x1 = (int) Math.floor((origenX + ancho) / lado);
        int y0 = Math.max(0, (int) Math.floor(origenY / lado)), y1 = Math.min(n - 1, (int) Math.floor((origenY + alto) / lado));
        Set<String> nuevos = new HashSet<>();
        g.setImageSmoothing(true);
        for (int tx = x0; tx <= x1; tx++) {
            int x = Math.floorMod(tx, n);   // el mundo se repite hacia los costados
            for (int ty = y0; ty <= y1; ty++) {
                double px = tx * lado - origenX, py = ty * lado - origenY;
                String clave = z + "/" + x + "/" + ty;
                nuevos.add(clave);
                Image imagen = memoria.get(clave);
                if (imagen != null) {
                    // Medio píxel de más para que no se vean líneas finas entre mosaicos.
                    g.drawImage(imagen, px, py, lado + 0.5, lado + 0.5);
                    continue;
                }
                dibujarAntecesor(g, z, x, ty, px, py, lado, nuevos);
                pedirMosaico(z, x, ty);
            }
        }
        buscados = nuevos;
    }

    /**
     * Mientras llega un mosaico, busco en memoria uno de menos zoom que lo contenga y dibujo ampliado el
     * pedazo que le corresponde: un nivel menos es un cuarto del mosaico padre, dos niveles, un dieciseisavo...
     * Así se ve el mapa algo borroso en vez de un hueco. También pido el antecesor del mapa general
     * (zoom 9 o menos), que casi siempre está en el disco.
     */
    private void dibujarAntecesor(GraphicsContext g, int z, int x, int y, double px, double py, double lado, Set<String> nuevos) {
        boolean pedido = false;
        for (int d = 1; d <= Math.min(z, 8); d++) {
            int zp = z - d, xp = x >> d, yp = y >> d;
            String clave = zp + "/" + xp + "/" + yp;
            Image padre = memoria.get(clave);
            if (padre != null) {
                double porcion = ProyeccionMercator.TAMANO_MOSAICO / (double) (1 << d);
                double sx = (x - (xp << d)) * porcion, sy = (y - (yp << d)) * porcion;
                g.drawImage(padre, sx, sy, porcion, porcion, px, py, lado + 0.5, lado + 0.5);
                return;
            }
            if (!pedido && zp <= 9) {
                nuevos.add(clave);
                pedirMosaico(zp, xp, yp);
                pedido = true;
            }
        }
    }

    // ------------------------------------------------------------------ NDVI

    /**
     * Pido en segundo plano la imagen NDVI de cada zona para la fecha de trabajo. Si ya la bajé antes sale
     * de la caché del disco; si no, de Sentinel Hub. Si falla (por ejemplo, faltan las credenciales), muestro
     * el motivo y no insisto con las demás zonas hasta que se vuelva a tildar la capa.
     */
    private void cargarNdvi() {
        if (datos == null) {
            return;
        }
        problemaNdvi = null;
        lblEstadoNdvi.setText("Buscando las imágenes NDVI de las zonas...");
        LocalDate fecha = fechaDatos;
        List<Zona> zonas = new ArrayList<>(datos.zonas());
        for (Zona zona : zonas) {
            String clave = zona.getId() + "_" + fecha;
            if (imagenesNdvi.containsKey(clave) || !ndviPendientes.add(clave)) {
                continue;
            }
            ndviEjecutor.submit(() -> {
                try {
                    if (problemaNdvi == null) {
                        ServicioMapa.Descarga d = servicio.imagenNdvi(zona, fecha, true);
                        if (d.fallo()) {
                            problemaNdvi = d.problema();
                        } else if (d.datos() != null) {
                            Image imagen = new Image(new ByteArrayInputStream(d.datos()));
                            if (!imagen.isError()) {
                                imagenesNdvi.put(clave, imagen);
                            }
                        }
                    }
                } finally {
                    ndviPendientes.remove(clave);
                    Platform.runLater(() -> {
                        actualizarEstadoNdvi();
                        redibujar();
                    });
                }
            });
        }
        actualizarEstadoNdvi();
    }

    private void actualizarEstadoNdvi() {
        if (!chkNdvi.isSelected()) {
            lblEstadoNdvi.setText("");
        } else if (problemaNdvi != null) {
            lblEstadoNdvi.setText("No se pudo obtener el NDVI: " + problemaNdvi);
        } else if (!ndviPendientes.isEmpty()) {
            lblEstadoNdvi.setText("Descargando NDVI de Sentinel-2 (" + ndviPendientes.size() + " zona(s) pendientes)...");
        } else {
            lblEstadoNdvi.setText("Pasada menos nublada de los 30 días previos al " + fechaDatos.format(Dialogos.FECHA)
                    + ". Nubes y agua quedan transparentes.");
        }
    }

    /**
     * La imagen viene en coordenadas geográficas (grados parejos) y el mapa está en Mercator; para zonas de
     * unas decenas de kilómetros la diferencia es de menos de un píxel, así que la estiro sobre el rectángulo.
     */
    private void dibujarNdvi(GraphicsContext g) {
        for (Zona z : datos.zonas()) {
            Image imagen = imagenesNdvi.get(z.getId() + "_" + fechaDatos);
            if (imagen != null) {
                double x = pantallaX(z.getLongitudMin()), y = pantallaY(z.getLatitudMax());
                g.drawImage(imagen, x, y, pantallaX(z.getLongitudMax()) - x, pantallaY(z.getLatitudMin()) - y);
            }
        }
    }

    // ------------------------------------------------------------------ dibujo

    private void redibujar() {
        GraphicsContext g = lienzo.getGraphicsContext2D();
        double ancho = lienzo.getWidth(), alto = lienzo.getHeight();
        if (ancho <= 0 || alto <= 0) {
            return;
        }
        // Que el mapa no se pueda arrastrar tan lejos que desaparezca por arriba o por abajo.
        double mundo = ProyeccionMercator.tamanoMundo(zoom);
        origenY = Math.max(-alto / 2, Math.min(mundo - alto / 2, origenY));
        g.setFill(Color.web("#dfe6ea"));
        g.fillRect(0, 0, ancho, alto);
        dibujarMosaicos(g, ancho, alto);
        if (datos != null) {
            if (chkNdvi.isSelected()) {
                dibujarNdvi(g);
            }
            if (chkZonas.isSelected()) {
                dibujarZonas(g);
            }
            if (chkActivos.isSelected()) {
                dibujarActivos(g);
            }
            if (chkFocosRegion.isSelected()) {
                dibujarFocosRegion(g, ancho, alto);
            }
            if (chkFocos.isSelected()) {
                dibujarFocos(g);
            }
            if (chkViento.isSelected()) {
                dibujarViento(g);
            }
            if (chkDespliegue.isSelected()) {
                dibujarDespliegue(g);
            }
            // Los rótulos de las zonas van al final, con fondo, para que no los tapen los focos.
            if (chkZonas.isSelected()) {
                rotularZonas(g);
            }
        }
        dibujarFormaNueva(g);
        dibujarEscala(g, alto);
        dibujarAtribucion(g, ancho, alto);
        actualizarEstadoMapa();
    }

    private void actualizarEstadoMapa() {
        String estado = String.format(Locale.ROOT, "Zoom %.1f", zoom);
        if (System.currentTimeMillis() < sinConexionHasta) {
            estado += " · Sin conexión: se ve lo guardado en el equipo; lo demás aparece borroso.";
        } else if (!pendientes.isEmpty()) {
            estado += " · Cargando " + pendientes.size() + " mosaico(s)...";
        }
        lblEstadoMapa.setText(estado);
    }

    /**
     * Cada zona es su contorno (rectángulo o polígono) con el color de su nivel de riesgo; sin índice va
     * punteada en gris. Con la capa NDVI activa no la relleno, para que se vea la imagen de abajo.
     */
    private void dibujarZonas(GraphicsContext g) {
        boolean rellenar = !chkNdvi.isSelected();
        for (Zona z : datos.zonas()) {
            List<Vertice> contorno = z.getContorno();
            double[] xs = new double[contorno.size()], ys = new double[contorno.size()];
            for (int i = 0; i < contorno.size(); i++) {
                xs[i] = pantallaX(contorno.get(i).longitud());
                ys[i] = pantallaY(contorno.get(i).latitud());
            }
            IndiceRiesgo indice = datos.indices().get(z.getId());
            Color color = colorNivel(indice);
            if (!z.isVigilanciaActiva()) {
                g.setStroke(Color.web("#37474f"));
                g.setLineWidth(2.2);
                g.setLineDashes(4, 4);
            } else if (indice == null) {
                g.setStroke(Color.web("#455a64"));
                g.setLineWidth(2);
                g.setLineDashes(8, 5);
            } else {
                if (rellenar) {
                    g.setFill(Color.color(color.getRed(), color.getGreen(), color.getBlue(), 0.32));
                    g.fillPolygon(xs, ys, xs.length);
                }
                g.setStroke(color);
                g.setLineWidth(2.5);
                g.setLineDashes();
            }
            g.strokePolygon(xs, ys, xs.length);
            g.setLineDashes();
        }
    }

    /** Rótulo de cada zona sobre su borde superior: nombre, índice y nivel, en una etiqueta con el color del nivel. */
    private void rotularZonas(GraphicsContext g) {
        if (zoom < 7) {
            return;   // Con toda la provincia a la vista los rótulos se amontonan; aparecen al acercarse.
        }
        g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 11.5));
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.CENTER);
        for (Zona z : datos.zonas()) {
            IndiceRiesgo indice = datos.indices().get(z.getId());
            String rotulo;
            Color borde;
            if (!z.isVigilanciaActiva()) {
                rotulo = z.getNombre() + " (sin vigilancia)";
                borde = Color.web("#616161");
            } else if (indice == null) {
                rotulo = z.getNombre() + " · sin índice";
                borde = Color.web("#455a64");
            } else {
                rotulo = String.format(Locale.ROOT, "%s · %.1f %s", z.getNombre(), indice.valorFinal(), indice.nivel());
                borde = colorNivel(indice);
            }
            Text medida = new Text(rotulo);
            medida.setFont(g.getFont());
            double ancho = medida.getLayoutBounds().getWidth() + 12;
            double x = pantallaX(z.getLongitudMin()), y = pantallaY(z.getLatitudMax()) - 11;
            g.setFill(Color.web("#ffffff", 0.92));
            g.fillRoundRect(x, y - 9, ancho, 18, 8, 8);
            g.setStroke(borde);
            g.setLineWidth(1.5);
            g.strokeRoundRect(x, y - 9, ancho, 18, 8, 8);
            g.setFill(Color.web("#1f2a36"));
            g.fillText(rotulo, x + 6, y);
        }
    }

    private void dibujarActivos(GraphicsContext g) {
        for (ActivoProtegido a : datos.activos()) {
            double x = pantallaX(a.longitud()), y = pantallaY(a.latitud());
            double r = ProyeccionMercator.radioEnPixeles(a.distanciaAlertaKm(), a.latitud(), zoom);
            g.setStroke(Color.web("#6a1b9a", 0.85));
            g.setLineWidth(1.5);
            g.setLineDashes(5, 4);
            g.strokeOval(x - r, y - r, 2 * r, 2 * r);
            g.setLineDashes();
            g.setFill(Color.web("#6a1b9a"));
            g.fillRect(x - 5, y - 5, 10, 10);
            g.setStroke(Color.WHITE);
            g.strokeRect(x - 5, y - 5, 10, 10);
            if (zoom >= 10) {
                texto(g, a.nombre(), x + 8, y, 11, FontWeight.NORMAL);
            }
        }
    }

    /**
     * Marcas: un círculo con la letra y el color de su tipo. Recursos desplegados: un rectángulo verde
     * (terrestre) o azul (aéreo) con la dotación adentro, así de un vistazo se ve cuánta gente hay en cada punto.
     * Los nombres aparecen al acercarse, para no tapar el mapa.
     */
    private void dibujarDespliegue(GraphicsContext g) {
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.CENTER);
        for (MarcaMapa m : marcas) {
            double x = pantallaX(m.longitud()), y = pantallaY(m.latitud());
            g.setFill(Color.web(m.tipo().color()));
            g.fillOval(x - 8, y - 8, 16, 16);
            g.setStroke(Color.WHITE);
            g.setLineWidth(1.8);
            g.strokeOval(x - 8, y - 8, 16, 16);
            g.setFill(Color.WHITE);
            g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 10.5));
            g.setTextAlign(TextAlignment.CENTER);
            g.fillText(m.tipo().letra(), x, y);
            if (zoom >= 11) {
                texto(g, m.descripcion(), x + 11, y, 11, FontWeight.NORMAL);
            }
        }
        for (PosicionRecurso p : desplegados) {
            double x = pantallaX(p.longitud()), y = pantallaY(p.latitud());
            g.setFill(colorRecurso(p.tipo()));
            g.fillRoundRect(x - 11, y - 8, 22, 16, 5, 5);
            g.setStroke(Color.WHITE);
            g.setLineWidth(1.8);
            g.strokeRoundRect(x - 11, y - 8, 22, 16, 5, 5);
            g.setFill(Color.WHITE);
            g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 10.5));
            g.setTextAlign(TextAlignment.CENTER);
            g.fillText(String.valueOf(p.dotacion()), x, y);
            if (zoom >= 9) {
                texto(g, p.denominacion(), x + 14, y, 11, FontWeight.BOLD);
            }
        }
    }

    /**
     * Flecha de viento en el centro de cada zona, apuntando hacia donde sopla (hacia donde empujaría el fuego).
     * El largo crece con la velocidad máxima y el color marca la intensidad: azul hasta 20 km/h, naranja hasta
     * 40 y rojo por encima. Al lado va la velocidad. Las zonas sin dirección (por ejemplo, carga manual) no la llevan.
     */
    private void dibujarViento(GraphicsContext g) {
        for (Zona z : datos.zonas()) {
            RegistroMeteo m = datos.meteo().get(z.getId());
            if (m == null || m.direccionVientoGrados() == null) {
                continue;
            }
            double angulo = Math.toRadians(DireccionViento.haciaDonde(m.direccionVientoGrados()));
            double largo = 20 + Math.min(m.vientoMaxKmh(), 80) * 0.6;
            // En pantalla la y crece hacia abajo, por eso el norte (0°) es -y.
            double dx = Math.sin(angulo), dy = -Math.cos(angulo);
            double cx = pantallaX(z.getLongitudCentro()), cy = pantallaY(z.getLatitudCentro());
            double x1 = cx - dx * largo / 2, y1 = cy - dy * largo / 2, x2 = cx + dx * largo / 2, y2 = cy + dy * largo / 2;
            double[] puntaX = {x2, x2 - dx * 11 - dy * 6, x2 - dx * 11 + dy * 6};
            double[] puntaY = {y2, y2 - dy * 11 + dx * 6, y2 - dy * 11 - dx * 6};
            Color color = m.vientoMaxKmh() > 40 ? Color.web("#c62828") : m.vientoMaxKmh() > 20 ? Color.web("#ef6c00") : Color.web("#1565c0");
            g.setLineCap(StrokeLineCap.ROUND);
            g.setStroke(Color.WHITE);
            g.setLineWidth(6);
            g.strokeLine(x1, y1, x2, y2);
            g.strokePolygon(puntaX, puntaY, 3);
            g.setStroke(color);
            g.setLineWidth(3);
            g.strokeLine(x1, y1, x2, y2);
            g.setFill(color);
            g.fillPolygon(puntaX, puntaY, 3);
            g.setLineCap(StrokeLineCap.SQUARE);
            texto(g, String.format(Locale.ROOT, "%.0f km/h", m.vientoMaxKmh()), x1 + 6, y1 + 10, 11, FontWeight.BOLD);
        }
    }

    private static Color colorRecurso(TipoRecurso tipo) {
        return tipo == TipoRecurso.AEREO ? Color.web("#1565c0") : Color.web("#2e7d32");
    }

    /** El tamaño del foco crece con su potencia (FRP) y el color indica la confianza; la baja va en amarillo. */
    private void dibujarFocos(GraphicsContext g) {
        // Dibujo primero los de menor potencia para que los más intensos queden arriba.
        for (FocoCalor f : datos.focos().stream()
                .sorted(Comparator.comparingDouble(f -> f.potenciaFrpMw() == null ? 0 : f.potenciaFrpMw())).toList()) {
            double x = pantallaX(f.longitud()), y = pantallaY(f.latitud());
            double r = radioFoco(f);
            g.setFill(colorFoco(f.confianza()));
            g.fillOval(x - r, y - r, 2 * r, 2 * r);
            g.setStroke(Color.web("#3e2723"));
            g.setLineWidth(1.2);
            g.strokeOval(x - r, y - r, 2 * r, 2 * r);
        }
    }

    /**
     * Focos de toda la región: círculos más chicos que los de las zonas, para que se distingan de los
     * sincronizados y no tapen el mapa cuando hay cientos. Salteo los que quedan fuera de la pantalla.
     */
    private void dibujarFocosRegion(GraphicsContext g, double ancho, double alto) {
        double r = radioFocoRegion();
        g.setStroke(Color.web("#3e2723", 0.8));
        g.setLineWidth(0.8);
        for (FocoCalor f : focosRegion) {
            double x = pantallaX(f.longitud()), y = pantallaY(f.latitud());
            if (x < -r || y < -r || x > ancho + r || y > alto + r) {
                continue;
            }
            g.setFill(colorFoco(f.confianza()));
            g.fillOval(x - r, y - r, 2 * r, 2 * r);
            g.strokeOval(x - r, y - r, 2 * r, 2 * r);
        }
    }

    private double radioFocoRegion() {
        return zoom < 6 ? 2.5 : zoom < 9 ? 3.5 : 4.5;
    }

    /** La forma que se está marcando: el rectángulo, o el polígono con sus vértices y la línea hasta el mouse. */
    private void dibujarFormaNueva(GraphicsContext g) {
        Color azul = Color.web("#1565c0");
        g.setStroke(azul);
        g.setLineWidth(2);
        if (rectangulo != null) {
            double xa = pantallaX(rectangulo[1]), ya = pantallaY(rectangulo[0]);
            double xb = pantallaX(rectangulo[3]), yb = pantallaY(rectangulo[2]);
            g.setFill(Color.web("#1565c0", 0.15));
            g.fillRect(Math.min(xa, xb), Math.min(ya, yb), Math.abs(xb - xa), Math.abs(yb - ya));
            g.setLineDashes(8, 5);
            g.strokeRect(Math.min(xa, xb), Math.min(ya, yb), Math.abs(xb - xa), Math.abs(yb - ya));
            g.setLineDashes();
        }
        if (poligono.isEmpty()) {
            return;
        }
        double[] xs = new double[poligono.size()], ys = new double[poligono.size()];
        for (int i = 0; i < poligono.size(); i++) {
            xs[i] = pantallaX(poligono.get(i).longitud());
            ys[i] = pantallaY(poligono.get(i).latitud());
        }
        if (poligonoCerrado) {
            g.setFill(Color.web("#1565c0", 0.15));
            g.fillPolygon(xs, ys, xs.length);
            g.strokePolygon(xs, ys, xs.length);
        } else {
            g.strokePolyline(xs, ys, xs.length);
            if (mouseX >= 0) {
                g.setLineDashes(6, 4);
                g.strokeLine(xs[xs.length - 1], ys[ys.length - 1], mouseX, mouseY);
                g.setLineDashes();
            }
        }
        for (int i = 0; i < xs.length; i++) {
            g.setFill(i == 0 ? Color.web("#0d47a1") : Color.WHITE);
            g.fillOval(xs[i] - 4.5, ys[i] - 4.5, 9, 9);
            g.strokeOval(xs[i] - 4.5, ys[i] - 4.5, 9, 9);
        }
    }

    private static double radioFoco(FocoCalor f) {
        return 4 + Math.min(6, (f.potenciaFrpMw() == null ? 0 : f.potenciaFrpMw()) / 15);
    }

    private static Color colorFoco(Confianza c) {
        return switch (c) {
            case ALTA -> Color.web("#d50000");
            case NOMINAL -> Color.web("#ff6d00");
            case BAJA -> Color.web("#ffd600");
        };
    }

    private Color colorNivel(IndiceRiesgo indice) {
        if (indice == null) {
            return Color.GRAY;
        }
        NivelRiesgo n = datos.nivelesRiesgo().get(indice.nivel());
        return n == null ? Color.GRAY : Color.web(n.color());
    }

    /**
     * Barra de escala: en Mercator los metros por píxel dependen de la latitud, así que la calculo en el
     * centro de la pantalla y busco una distancia redonda que ocupe al menos 70 píxeles.
     */
    private void dibujarEscala(GraphicsContext g, double alto) {
        double metrosPorPixel = ProyeccionMercator.metrosPorPixel(latitudDe(alto / 2), zoom);
        double metros = 50;
        for (double opcion : new double[]{50, 100, 200, 500, 1_000, 2_000, 5_000, 10_000, 20_000, 50_000, 100_000,
                200_000, 500_000, 1_000_000}) {
            metros = opcion;
            if (opcion / metrosPorPixel >= 70) {
                break;
            }
        }
        double largo = metros / metrosPorPixel;
        double x = 16, y = alto - 30;
        g.setFill(Color.web("#ffffff", 0.8));
        g.fillRoundRect(x - 8, y - 22, largo + 78, 34, 6, 6);
        g.setStroke(Color.web("#263238"));
        g.setLineWidth(3);
        g.setLineCap(StrokeLineCap.BUTT);
        g.strokeLine(x, y, x + largo, y);
        g.setLineWidth(1.5);
        g.strokeLine(x, y - 6, x, y + 4);
        g.strokeLine(x + largo, y - 6, x + largo, y + 4);
        String rotulo = metros >= 1000 ? String.format(Locale.ROOT, "%.0f km", metros / 1000) : String.format(Locale.ROOT, "%.0f m", metros);
        texto(g, rotulo, x + largo + 8, y, 12, FontWeight.BOLD);
        texto(g, "N ↑", x, y - 16, 12, FontWeight.BOLD);
    }

    /** La licencia de OpenTopoMap (CC-BY-SA) pide mostrar la atribución sobre el mapa. */
    private void dibujarAtribucion(GraphicsContext g, double ancho, double alto) {
        g.setFont(Font.font("Segoe UI", 10));
        Text medida = new Text(ServicioMapa.ATRIBUCION);
        medida.setFont(g.getFont());
        double w = medida.getLayoutBounds().getWidth() + 10;
        g.setFill(Color.web("#ffffff", 0.75));
        g.fillRect(ancho - w, alto - 16, w, 16);
        g.setFill(Color.web("#37474f"));
        g.setTextAlign(TextAlignment.RIGHT);
        g.setTextBaseline(VPos.CENTER);
        g.fillText(ServicioMapa.ATRIBUCION, ancho - 5, alto - 8);
    }

    /** Texto con un borde blanco alrededor, para que se lea sobre cualquier color del mapa. */
    private static void texto(GraphicsContext g, String texto, double x, double y, double tamano, FontWeight peso) {
        g.setFont(Font.font("Segoe UI", peso, tamano));
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.CENTER);
        g.setStroke(Color.web("#ffffff", 0.9));
        g.setLineWidth(3);
        g.strokeText(texto, x, y);
        g.setFill(Color.web("#1f2a36"));
        g.fillText(texto, x, y);
    }

    // ------------------------------------------------------------------ detalle y leyenda

    /**
     * Busco qué hay bajo el cursor, en orden de importancia: un recurso desplegado o una marca (van dibujados
     * encima), un foco, un activo o una zona. Para los puntos uso una tolerancia de unos píxeles de pantalla,
     * así no hay que acertar justo.
     */
    private String detalle(double sx, double sy, double lat, double lon) {
        if (datos == null) {
            return "";
        }
        if (chkDespliegue.isSelected()) {
            PosicionRecurso p = recursoEn(sx, sy);
            if (p != null) {
                return String.format("Recurso desplegado: %s%n%s, %d personas · %s%nUbicado el %s por %s%s",
                        p.denominacion(), p.tipo() == TipoRecurso.AEREO ? "Aéreo" : "Terrestre", p.dotacion(),
                        p.zona() == null ? "fuera de las zonas" : p.zona(), p.fechaHora().format(Dialogos.FECHA_HORA),
                        p.usuario(), p.observaciones() == null ? "" : "\n" + p.observaciones());
            }
            MarcaMapa m = marcaEn(sx, sy);
            if (m != null) {
                return String.format("%s: %s%n%s%nMarcada el %s por %s%nClic para quitarla.", m.tipo().texto(), m.descripcion(),
                        m.zona() == null ? "Fuera de las zonas" : m.zona(), m.fechaHora().format(Dialogos.FECHA_HORA), m.usuario());
            }
        }
        if (chkFocos.isSelected()) {
            for (FocoCalor f : datos.focos()) {
                if (Math.hypot(pantallaX(f.longitud()) - sx, pantallaY(f.latitud()) - sy) <= radioFoco(f) + 3) {
                    return String.format(Locale.ROOT, "Foco de calor%n%s UTC · %s%nConfianza %s%s",
                            f.fechaHoraUtc().format(Dialogos.FECHA_HORA), f.satelite(), f.confianza(),
                            f.potenciaFrpMw() == null ? "" : String.format(Locale.ROOT, "%nPotencia %.1f MW", f.potenciaFrpMw()));
                }
            }
        }
        if (chkFocosRegion.isSelected()) {
            for (FocoCalor f : focosRegion) {
                if (Math.hypot(pantallaX(f.longitud()) - sx, pantallaY(f.latitud()) - sy) <= radioFocoRegion() + 3) {
                    return String.format(Locale.ROOT, "Foco satelital (FIRMS, fuera de las zonas vigiladas)%n%s UTC · %s%nConfianza %s%s",
                            f.fechaHoraUtc().format(Dialogos.FECHA_HORA), f.satelite(), f.confianza(),
                            f.potenciaFrpMw() == null ? "" : String.format(Locale.ROOT, "%nPotencia %.1f MW", f.potenciaFrpMw()));
                }
            }
        }
        if (chkActivos.isSelected()) {
            for (ActivoProtegido a : datos.activos()) {
                if (Math.hypot(pantallaX(a.longitud()) - sx, pantallaY(a.latitud()) - sy) <= 8) {
                    return String.format(Locale.ROOT, "Activo protegido: %s%n%s · alerta a %.1f km", a.nombre(), a.tipo(),
                            a.distanciaAlertaKm());
                }
            }
        }
        if (chkZonas.isSelected() || chkNdvi.isSelected()) {
            for (Zona z : datos.zonas()) {
                if (z.contiene(lat, lon)) {
                    return detalleZona(z);
                }
            }
        }
        return "Pase el mouse sobre una zona, un foco o un activo. Clic en un punto: más opciones.";
    }

    private String detalleZona(Zona z) {
        IndiceRiesgo i = datos.indices().get(z.getId());
        NivelAlerta alerta = datos.nivelesAlerta().get(z.getId());
        RegistroNdvi ndvi = datos.ndvi().get(z.getId());
        long focos = datos.focos().stream().filter(f -> f.idZona() == z.getId()).count();
        RegistroMeteo m = datos.meteo().get(z.getId());
        String viento = m == null ? "Viento: sin datos del día (sincronizar meteorología)"
                : String.format(Locale.ROOT, "Viento máx. %.0f km/h%s%s", m.vientoMaxKmh(),
                        m.rafagaMaxKmh() == null ? "" : String.format(Locale.ROOT, ", ráfagas %.0f", m.rafagaMaxKmh()),
                        m.direccionVientoGrados() == null ? "" : " · " + DireccionViento.describir(m.direccionVientoGrados()));
        return String.format(Locale.ROOT, "%s (%s)%n%s%nNivel de alerta: %s%n%s%n%s%nFocos recientes: %d · Recursos con base: %d",
                z.getNombre(), z.esPoligono() ? "polígono de " + z.getVertices().size() + " vértices" : "rectángulo",
                i == null ? "Sin índice para la fecha" : String.format(Locale.ROOT,
                        "Índice %.2f (%s)%nH %.2f · M %.2f · C %.2f", i.valorFinal(), i.nivel(),
                        i.compHistorica(), i.compMeteorologica(), i.compCombustible()),
                alerta == null ? "sin nivel" : alerta.nombre(),
                ndvi == null ? "NDVI: sin datos (sincronizar NDVI)" : String.format(Locale.ROOT, "NDVI %.2f, %s (imagen del %s)",
                        ndvi.ndviMedio(), ServicioSincronizacion.describirNdvi(ndvi.ndviMedio()), ndvi.fechaImagen().format(Dialogos.FECHA)),
                viento, focos, datos.recursosPorZona().getOrDefault(z.getId(), 0L));
    }

    /** La leyenda toma los colores del catálogo de niveles de riesgo, así coincide con el tablero. */
    private void armarLeyenda() {
        leyenda.getChildren().clear();
        if (datos == null) {
            return;
        }
        datos.nivelesRiesgo().values().stream().sorted(Comparator.comparingInt(NivelRiesgo::orden)).forEach(n -> {
            Rectangle muestra = new Rectangle(16, 12, Color.web(n.color(), 0.6));
            muestra.setStroke(Color.web(n.color()));
            leyenda.getChildren().add(new HBox(6, muestra, new Label(String.format(Locale.ROOT, "%s (%.0f a %.0f)",
                    n.nombre(), n.umbralMin(), n.umbralMax()))));
        });
        if (chkNdvi.isSelected()) {
            // Los mismos colores que usa el script de la imagen en FuenteSentinel.
            String[][] escala = {{"#b8b8b8", "NDVI < 0,1: roca, nieve o suelo"}, {"#9e7347", "0,1 a 0,25: suelo desnudo"},
                    {"#edd66b", "0,25 a 0,4: vegetación rala o seca"}, {"#9ecc4d", "0,4 a 0,6: vegetación moderada"},
                    {"#339933", "0,6 a 0,8: vegetación densa"}, {"#005c1a", "> 0,8: muy densa"}};
            for (String[] fila : escala) {
                leyenda.getChildren().add(new HBox(6, new Rectangle(16, 12, Color.web(fila[0])), new Label(fila[1])));
            }
        }
        for (Confianza c : Confianza.values()) {
            Circle punto = new Circle(6, colorFoco(c));
            punto.setStroke(Color.web("#3e2723"));
            leyenda.getChildren().add(new HBox(6, punto, new Label("Foco, confianza " + c.name().toLowerCase())));
        }
        if (chkViento.isSelected()) {
            String[][] vientos = {{"#1565c0", "hasta 20 km/h"}, {"#ef6c00", "20 a 40 km/h"}, {"#c62828", "más de 40 km/h"}};
            for (String[] fila : vientos) {
                Label flecha = new Label("➜");
                flecha.setStyle("-fx-text-fill: " + fila[0] + "; -fx-font-weight: bold;");
                leyenda.getChildren().add(new HBox(6, flecha, new Label("Viento " + fila[1] + " (hacia dónde sopla)")));
            }
        }
        Rectangle activo = new Rectangle(10, 10, Color.web("#6a1b9a"));
        leyenda.getChildren().add(new HBox(6, activo, new Label("Activo protegido y radio de alerta")));
        for (TipoRecurso tipo : TipoRecurso.values()) {
            Rectangle muestra = new Rectangle(18, 12, colorRecurso(tipo));
            muestra.setArcWidth(5);
            muestra.setArcHeight(5);
            leyenda.getChildren().add(new HBox(6, muestra, new Label("Recurso " + (tipo == TipoRecurso.AEREO ? "aéreo" : "terrestre")
                    + " desplegado (número: dotación)")));
        }
        for (TipoMarca tipo : TipoMarca.values()) {
            Circle muestra = new Circle(7, Color.web(tipo.color()));
            Label letra = new Label(tipo.letra());
            letra.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 9px;");
            StackPane simbolo = new StackPane(muestra, letra);
            leyenda.getChildren().add(new HBox(6, simbolo, new Label(tipo.texto())));
        }
    }
}
