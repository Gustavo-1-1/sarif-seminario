package sarif.modelo;

import java.util.List;

/**
 * Provincia argentina (o la Ciudad de Buenos Aires) con el rectángulo de latitud y longitud que la encierra,
 * para encuadrarla en el mapa. Los límites son aproximados, redondeados al décimo de grado: alcanzan para
 * ver la provincia entera en pantalla, no para decidir si un punto está adentro. En Tierra del Fuego tomo la
 * parte argentina de la Isla Grande.
 */
public record Provincia(String nombre, double latMin, double latMax, double lonMin, double lonMax) {

    public static final List<Provincia> TODAS = List.of(
            new Provincia("Buenos Aires", -41.1, -33.2, -63.4, -56.6),
            new Provincia("Catamarca", -30.1, -25.1, -69.1, -64.9),
            new Provincia("Chaco", -28.0, -24.0, -63.4, -58.3),
            new Provincia("Chubut", -46.0, -42.0, -72.2, -63.6),
            new Provincia("Ciudad de Buenos Aires", -34.71, -34.53, -58.54, -58.33),
            new Provincia("Córdoba", -35.0, -29.5, -65.8, -61.8),
            new Provincia("Corrientes", -30.8, -27.2, -59.7, -55.6),
            new Provincia("Entre Ríos", -34.1, -30.1, -60.8, -57.8),
            new Provincia("Formosa", -27.0, -22.1, -62.4, -57.5),
            new Provincia("Jujuy", -24.6, -21.8, -67.3, -64.1),
            new Provincia("La Pampa", -39.3, -35.0, -68.3, -63.4),
            new Provincia("La Rioja", -32.0, -27.7, -69.7, -65.4),
            new Provincia("Mendoza", -37.6, -32.0, -70.6, -66.5),
            new Provincia("Misiones", -28.2, -25.5, -56.1, -53.6),
            new Provincia("Neuquén", -41.1, -36.1, -71.95, -68.0),
            new Provincia("Río Negro", -42.0, -37.5, -71.95, -62.8),
            new Provincia("Salta", -26.4, -22.0, -68.6, -62.3),
            new Provincia("San Juan", -32.6, -28.4, -70.6, -67.0),
            new Provincia("San Luis", -36.0, -31.8, -67.5, -64.9),
            new Provincia("Santa Cruz", -52.4, -46.0, -73.6, -65.7),
            new Provincia("Santa Fe", -34.4, -28.0, -62.9, -58.8),
            new Provincia("Santiago del Estero", -30.4, -25.6, -65.2, -61.6),
            new Provincia("Tierra del Fuego", -55.1, -52.6, -68.7, -63.8),
            new Provincia("Tucumán", -28.0, -26.0, -66.2, -64.5));

    @Override
    public String toString() {
        return nombre;
    }
}
