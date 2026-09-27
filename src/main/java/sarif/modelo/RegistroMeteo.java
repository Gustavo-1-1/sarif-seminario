package sarif.modelo;

import java.time.LocalDate;

/**
 * Variables meteorológicas diarias de una zona (CU08), observadas o pronosticadas según esPronostico.
 * Las que uso para la componente meteorológica del índice son la temperatura máxima, la humedad mínima,
 * el viento máximo, la precipitación y, si está disponible, la humedad del suelo de 0 a 10 cm en m³/m³ (RC06).
 * <p>
 * El resto completa el vector del viento (ráfaga máxima y dirección dominante, en grados desde donde sopla)
 * y el pronóstico extendido (temperatura mínima, probabilidad de lluvia y evapotranspiración de referencia).
 * No entran en el índice: las guardo para analizar hacia dónde puede avanzar un incendio. Todas las
 * opcionales son objetos (Double, Integer) porque pueden faltar, y "sin dato" no es lo mismo que 0.
 */
public record RegistroMeteo(int idZona, LocalDate fecha, double temperaturaMaxC, double humedadMinPct,
                            double vientoMaxKmh, double precipitacionMm, boolean esPronostico, Double humedadSueloM3m3,
                            Double temperaturaMinC, Double rafagaMaxKmh, Integer direccionVientoGrados,
                            Double probPrecipitacionPct, Double evapotranspiracionMm) {

    /** Registro sin humedad del suelo, como los de la jornada de prueba y los de la primera versión. */
    public RegistroMeteo(int idZona, LocalDate fecha, double temperaturaMaxC, double humedadMinPct,
                         double vientoMaxKmh, double precipitacionMm, boolean esPronostico) {
        this(idZona, fecha, temperaturaMaxC, humedadMinPct, vientoMaxKmh, precipitacionMm, esPronostico, null);
    }

    /** Registro con las variables del índice solamente (sin el vector del viento ni el pronóstico extendido). */
    public RegistroMeteo(int idZona, LocalDate fecha, double temperaturaMaxC, double humedadMinPct,
                         double vientoMaxKmh, double precipitacionMm, boolean esPronostico, Double humedadSueloM3m3) {
        this(idZona, fecha, temperaturaMaxC, humedadMinPct, vientoMaxKmh, precipitacionMm, esPronostico, humedadSueloM3m3,
                null, null, null, null, null);
    }

    // Igual que en FocoCalor: el record es inmutable, así que para asignarle la zona devuelvo una copia.
    public RegistroMeteo conZona(int idZona) {
        return new RegistroMeteo(idZona, fecha, temperaturaMaxC, humedadMinPct, vientoMaxKmh, precipitacionMm, esPronostico,
                humedadSueloM3m3, temperaturaMinC, rafagaMaxKmh, direccionVientoGrados, probPrecipitacionPct,
                evapotranspiracionMm);
    }

    /**
     * Humedad del suelo en porcentaje de volumen (m³/m³ × 100), que es como se la lee habitualmente:
     * 0,08 m³/m³ es un suelo con 8 % de agua. Null si no hay dato.
     */
    public Double humedadSueloPct() {
        return humedadSueloM3m3 == null ? null : humedadSueloM3m3 * 100;
    }

    /**
     * Regla 30-30-30 de los combatientes: más de 30 °C, menos de 30 % de humedad y más de 30 km/h de viento.
     * Cuando se cumplen las tres a la vez, un incendio se propaga rápido y es difícil de controlar.
     */
    public boolean cumpleRegla30() {
        return temperaturaMaxC > 30 && humedadMinPct < 30 && vientoMaxKmh > 30;
    }
}
