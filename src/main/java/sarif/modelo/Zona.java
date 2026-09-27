package sarif.modelo;

import java.util.ArrayList;
import java.util.List;

/**
 * Zona de vigilancia que el organismo monitorea. Puede ser de dos formas:
 * <ul>
 *   <li>un rectángulo, definido por la latitud y la longitud mínimas y máximas (como las cargué al principio);</li>
 *   <li>un polígono, con la lista de vértices que el operador dibuja en el mapa, para seguir un valle o el
 *       borde de un bosque sin incluir de más.</li>
 * </ul>
 * En los dos casos guardo el rectángulo: en un polígono es el rectángulo que lo encierra, y me sirve para
 * pedirle datos a las fuentes (FIRMS y Sentinel trabajan con rectángulos) y para descartar rápido los
 * puntos que quedan lejos antes de hacer la cuenta del polígono.
 * <p>
 * Es la única entidad del modelo con comportamiento propio (si contiene un punto, si se superpone con
 * otra y la validación de sus datos); por eso es una clase común y no un record como las demás.
 * Además la necesito mutable, con getters y setters, para cargarla desde el formulario de alta.
 * Decidí no guardar acá el combustible, el índice ni el nivel de alerta vigentes: los derivo del historial
 * con las vistas v_combustible_vigente, v_nivel_alerta_vigente y v_tablero_zonas, así no hay datos duplicados
 * que se puedan desincronizar.
 */
public class Zona {

    /** Tope de vértices de un polígono: alcanza para dibujar con detalle y evita contornos absurdos. */
    public static final int MAXIMO_VERTICES = 100;
    // Tolerancia en grados (menos de un milímetro) para decidir si un punto está sobre un borde.
    private static final double TOLERANCIA = 1e-9;
    // Corrimiento en grados (un centímetro, aprox.) que uso para mirar "apenas adentro" de un borde.
    private static final double CORRIMIENTO = 1e-7;

    private int id;
    private String nombre;
    private String descripcion;
    private double latitudMin;
    private double latitudMax;
    private double longitudMin;
    private double longitudMax;
    private boolean vigilanciaActiva;
    // Vacía si la zona es un rectángulo.
    private List<Vertice> vertices = List.of();

    // Constructor vacío para armar la zona de a poco desde el formulario (con los setters).
    public Zona() {
    }

    // Constructor completo: lo uso en el DAO cuando leo una zona de la base.
    public Zona(int id, String nombre, String descripcion, double latitudMin, double latitudMax,
                double longitudMin, double longitudMax, boolean vigilanciaActiva) {
        this.id = id;
        this.nombre = nombre;
        this.descripcion = descripcion;
        this.latitudMin = latitudMin;
        this.latitudMax = latitudMax;
        this.longitudMin = longitudMin;
        this.longitudMax = longitudMax;
        this.vigilanciaActiva = vigilanciaActiva;
    }

    public boolean esPoligono() {
        return !vertices.isEmpty();
    }

    public List<Vertice> getVertices() {
        return vertices;
    }

    /**
     * Convierte la zona en un polígono con esos vértices y recalcula el rectángulo que lo encierra, así
     * los dos datos nunca quedan desparejos. Con una lista vacía (o null) la zona vuelve a ser un rectángulo.
     */
    public void setVertices(List<Vertice> nuevos) {
        vertices = nuevos == null ? List.of() : List.copyOf(nuevos);
        if (vertices.isEmpty()) {
            return;
        }
        latitudMin = vertices.stream().mapToDouble(Vertice::latitud).min().orElseThrow();
        latitudMax = vertices.stream().mapToDouble(Vertice::latitud).max().orElseThrow();
        longitudMin = vertices.stream().mapToDouble(Vertice::longitud).min().orElseThrow();
        longitudMax = vertices.stream().mapToDouble(Vertice::longitud).max().orElseThrow();
    }

    /** El contorno de la zona: sus vértices o, si es un rectángulo, sus cuatro esquinas. Lo uso para dibujarla. */
    public List<Vertice> getContorno() {
        if (esPoligono()) {
            return vertices;
        }
        return List.of(new Vertice(latitudMin, longitudMin), new Vertice(latitudMin, longitudMax),
                new Vertice(latitudMax, longitudMax), new Vertice(latitudMax, longitudMin));
    }

    /**
     * Indica si el punto pertenece al área de la zona. Los bordes cuentan como adentro, para que
     * un foco que cae justo en el límite no quede sin zona. Lo uso al asignar cada foco a su zona (CU07).
     * Primero miro el rectángulo (es una comparación barata que descarta casi todo) y, si la zona es un
     * polígono, recién ahí hago la cuenta del polígono.
     */
    public boolean contiene(double latitud, double longitud) {
        if (!dentroDelRectangulo(latitud, longitud)) {
            return false;
        }
        return !esPoligono() || sobreElBorde(vertices, latitud, longitud) || adentro(vertices, latitud, longitud);
    }

    /** Solo el rectángulo que encierra la zona (en un rectángulo es lo mismo que contiene). */
    public boolean dentroDelRectangulo(double latitud, double longitud) {
        return latitud >= latitudMin && latitud <= latitudMax
                && longitud >= longitudMin && longitud <= longitudMax;
    }

    /**
     * Indica si el área de esta zona se superpone con la de otra. Dos zonas que solo comparten un borde
     * no se consideran superpuestas, así se pueden dibujar zonas vecinas.
     * <p>
     * Si los rectángulos ni se tocan, listo. Si las dos zonas son rectángulos, que se toquen los rectángulos
     * ya es la respuesta. Con algún polígono, reviso tres cosas: que dos bordes se crucen, que un vértice
     * de una quede adentro de la otra, o que el punto que está "apenas adentro" del medio de un borde de
     * una caiga adentro de la otra (esto último detecta dos zonas iguales, donde nada se cruza).
     */
    public boolean seSuperponeCon(Zona otra) {
        boolean rectangulos = latitudMin < otra.latitudMax && otra.latitudMin < latitudMax
                && longitudMin < otra.longitudMax && otra.longitudMin < longitudMax;
        if (!rectangulos || (!esPoligono() && !otra.esPoligono())) {
            return rectangulos;
        }
        List<Vertice> a = getContorno(), b = otra.getContorno();
        for (int i = 0; i < a.size(); i++) {
            for (int j = 0; j < b.size(); j++) {
                if (seCruzan(a.get(i), a.get((i + 1) % a.size()), b.get(j), b.get((j + 1) % b.size()))) {
                    return true;
                }
            }
        }
        return entraEn(a, b) || entraEn(b, a);
    }

    /**
     * Valida los datos de la zona y devuelve la lista de errores encontrados (vacía si es válida).
     * Prefiero juntar todos los errores en vez de lanzar una excepción con el primero, así el usuario
     * ve de una vez todo lo que tiene que corregir en el formulario de alta (CU01). El límite de 80 caracteres
     * coincide con el largo de la columna nombre en la base.
     */
    public List<String> validar() {
        List<String> errores = new ArrayList<>();
        if (nombre == null || nombre.isBlank()) {
            errores.add("El nombre es obligatorio.");
        } else if (nombre.length() > 80) {
            errores.add("El nombre no puede superar los 80 caracteres.");
        }
        if (esPoligono()) {
            validarPoligono(errores);
            return errores;
        }
        if (latitudMin < -90 || latitudMax > 90 || latitudMin >= latitudMax) {
            errores.add("Las latitudes deben estar entre -90 y 90 y la mínima debe ser menor que la máxima.");
        }
        if (longitudMin < -180 || longitudMax > 180 || longitudMin >= longitudMax) {
            errores.add("Las longitudes deben estar entre -180 y 180 y la mínima debe ser menor que la máxima.");
        }
        return errores;
    }

    // Un polígono necesita al menos 3 vértices, coordenadas válidas, superficie y bordes que no se crucen entre sí
    // (un contorno "en forma de 8" no deja claro qué queda adentro).
    private void validarPoligono(List<String> errores) {
        if (vertices.size() < 3) {
            errores.add("El polígono necesita al menos 3 vértices.");
            return;
        }
        if (vertices.size() > MAXIMO_VERTICES) {
            errores.add("El polígono no puede tener más de " + MAXIMO_VERTICES + " vértices.");
        }
        if (vertices.stream().anyMatch(v -> v.latitud() < -90 || v.latitud() > 90 || v.longitud() < -180 || v.longitud() > 180)) {
            errores.add("Las latitudes deben estar entre -90 y 90 y las longitudes entre -180 y 180.");
        }
        int n = vertices.size();
        for (int i = 0; i < n; i++) {
            // Comparo cada borde con los que no son vecinos suyos (los vecinos comparten un vértice).
            for (int j = i + 2; j < n; j++) {
                if (i == 0 && j == n - 1) {
                    continue;
                }
                if (seCruzan(vertices.get(i), vertices.get(i + 1), vertices.get(j), vertices.get((j + 1) % n))) {
                    errores.add("Los bordes del polígono no pueden cruzarse entre sí.");
                    return;
                }
            }
        }
        // La superficie la miro después: en un "moño" las dos mitades se restan y también puede dar cero.
        if (Math.abs(superficieGrados()) < 1e-8) {
            errores.add("El polígono no encierra ninguna superficie.");
        }
    }

    // Fórmula del "cordón de zapato": superficie del polígono en grados cuadrados (solo la uso para ver que no sea cero).
    private double superficieGrados() {
        double suma = 0;
        for (int i = 0; i < vertices.size(); i++) {
            Vertice p = vertices.get(i), q = vertices.get((i + 1) % vertices.size());
            suma += p.longitud() * q.latitud() - q.longitud() * p.latitud();
        }
        return suma / 2;
    }

    /**
     * Punto de referencia de la zona (por ejemplo, para pedir la meteorología). En un rectángulo es el centro.
     * En un polígono el centro del rectángulo puede caer afuera (pensá en una zona en forma de "C"), así que
     * trazo una línea horizontal a media altura y tomo el medio del primer tramo que queda adentro.
     */
    public double getLatitudCentro() {
        return (latitudMin + latitudMax) / 2;
    }

    public double getLongitudCentro() {
        double centro = (longitudMin + longitudMax) / 2;
        if (!esPoligono() || contiene(getLatitudCentro(), centro)) {
            return centro;
        }
        double lat = getLatitudCentro();
        List<Double> cortes = new ArrayList<>();
        for (int i = 0; i < vertices.size(); i++) {
            Vertice p = vertices.get(i), q = vertices.get((i + 1) % vertices.size());
            if ((p.latitud() > lat) != (q.latitud() > lat)) {
                cortes.add(p.longitud() + (lat - p.latitud()) * (q.longitud() - p.longitud()) / (q.latitud() - p.latitud()));
            }
        }
        cortes.sort(null);
        return cortes.size() >= 2 ? (cortes.get(0) + cortes.get(1)) / 2 : centro;
    }

    // ------------------------------------------------------------------ geometría

    /**
     * Regla del rayo: desde el punto tiro una línea horizontal hacia el este y cuento cuántos bordes corta.
     * Si corta una cantidad impar, el punto está adentro. Tomo la longitud como x y la latitud como y;
     * para zonas de pocas decenas de kilómetros, tratar los grados como un plano no cambia el resultado.
     */
    static boolean adentro(List<Vertice> poligono, double latitud, double longitud) {
        boolean dentro = false;
        for (int i = 0, j = poligono.size() - 1; i < poligono.size(); j = i++) {
            Vertice p = poligono.get(i), q = poligono.get(j);
            if ((p.latitud() > latitud) != (q.latitud() > latitud)
                    && longitud < q.longitud() + (latitud - q.latitud()) * (p.longitud() - q.longitud()) / (p.latitud() - q.latitud())) {
                dentro = !dentro;
            }
        }
        return dentro;
    }

    // El punto está sobre algún borde si su distancia a ese segmento es prácticamente cero.
    static boolean sobreElBorde(List<Vertice> poligono, double latitud, double longitud) {
        for (int i = 0; i < poligono.size(); i++) {
            Vertice p = poligono.get(i), q = poligono.get((i + 1) % poligono.size());
            double dx = q.longitud() - p.longitud(), dy = q.latitud() - p.latitud();
            double largo2 = dx * dx + dy * dy;
            double t = largo2 == 0 ? 0 : ((longitud - p.longitud()) * dx + (latitud - p.latitud()) * dy) / largo2;
            t = Math.max(0, Math.min(1, t));
            if (Math.hypot(longitud - (p.longitud() + t * dx), latitud - (p.latitud() + t * dy)) <= TOLERANCIA) {
                return true;
            }
        }
        return false;
    }

    // Adentro de verdad: descarto los puntos que están sobre el borde, que en la superposición no cuentan.
    private static boolean estrictamenteAdentro(List<Vertice> poligono, double latitud, double longitud) {
        return !sobreElBorde(poligono, latitud, longitud) && adentro(poligono, latitud, longitud);
    }

    /**
     * Dos segmentos se cruzan "de verdad" si cada uno deja los extremos del otro a lados opuestos. Si solo
     * se tocan en una punta o van uno encima del otro (bordes compartidos), no lo cuento como cruce.
     */
    static boolean seCruzan(Vertice a, Vertice b, Vertice c, Vertice d) {
        double o1 = orientacion(a, b, c), o2 = orientacion(a, b, d);
        double o3 = orientacion(c, d, a), o4 = orientacion(c, d, b);
        return o1 * o2 < 0 && o3 * o4 < 0;
    }

    // Producto vectorial: positivo si c queda a la izquierda de la recta a→b, negativo a la derecha, 0 si está sobre ella.
    private static double orientacion(Vertice a, Vertice b, Vertice c) {
        double v = (b.longitud() - a.longitud()) * (c.latitud() - a.latitud())
                - (b.latitud() - a.latitud()) * (c.longitud() - a.longitud());
        return Math.abs(v) < 1e-18 ? 0 : v;
    }

    // Algún vértice de "a", o algún punto apenas adentro del medio de sus bordes, cae adentro de "b".
    private static boolean entraEn(List<Vertice> a, List<Vertice> b) {
        for (int i = 0; i < a.size(); i++) {
            Vertice p = a.get(i), q = a.get((i + 1) % a.size());
            if (estrictamenteAdentro(b, p.latitud(), p.longitud())) {
                return true;
            }
            double medioLat = (p.latitud() + q.latitud()) / 2, medioLon = (p.longitud() + q.longitud()) / 2;
            double dLat = q.latitud() - p.latitud(), dLon = q.longitud() - p.longitud();
            double largo = Math.hypot(dLat, dLon);
            if (largo == 0) {
                continue;
            }
            // Me corro un poquito en perpendicular al borde, hacia el lado que queda adentro de "a".
            double cLat = medioLat + dLon / largo * CORRIMIENTO, cLon = medioLon - dLat / largo * CORRIMIENTO;
            if (!adentro(a, cLat, cLon)) {
                cLat = medioLat - dLon / largo * CORRIMIENTO;
                cLon = medioLon + dLat / largo * CORRIMIENTO;
            }
            if (estrictamenteAdentro(b, cLat, cLon)) {
                return true;
            }
        }
        return false;
    }

    // Getters y setters: los necesito para el formulario de zonas y para los DAO.
    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public void setDescripcion(String descripcion) {
        this.descripcion = descripcion;
    }

    public double getLatitudMin() {
        return latitudMin;
    }

    public void setLatitudMin(double latitudMin) {
        this.latitudMin = latitudMin;
    }

    public double getLatitudMax() {
        return latitudMax;
    }

    public void setLatitudMax(double latitudMax) {
        this.latitudMax = latitudMax;
    }

    public double getLongitudMin() {
        return longitudMin;
    }

    public void setLongitudMin(double longitudMin) {
        this.longitudMin = longitudMin;
    }

    public double getLongitudMax() {
        return longitudMax;
    }

    public void setLongitudMax(double longitudMax) {
        this.longitudMax = longitudMax;
    }

    public boolean isVigilanciaActiva() {
        return vigilanciaActiva;
    }

    public void setVigilanciaActiva(boolean vigilanciaActiva) {
        this.vigilanciaActiva = vigilanciaActiva;
    }

    // Devuelvo el nombre para que las listas y ComboBox de JavaFX muestren la zona de forma legible.
    @Override
    public String toString() {
        return nombre;
    }
}
