package sarif.datos;

import sarif.modelo.OrigenDatos;
import sarif.modelo.RegistroMeteo;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Acceso a los registros meteorológicos diarios de cada zona (CU08), que después uso para la
 * componente meteorológica del índice (CU09), para el pronóstico extendido y para el viento del mapa.
 * Todas las consultas van con sentencias preparadas para evitar inyección SQL.
 */
public class MeteoDAO extends DaoBase {

    private static final String COLUMNAS = "id_zona, fecha, temperatura_max_c, humedad_min_pct, viento_max_kmh, "
            + "precipitacion_mm, es_pronostico, humedad_suelo_m3m3, temperatura_min_c, rafaga_max_kmh, "
            + "direccion_viento_grados, prob_precipitacion_pct, evapotranspiracion_mm";

    // Recibo la conexión para que todo el lote de meteorología se grabe en la transacción del servicio.
    public MeteoDAO(Connection cn) {
        super(cn);
    }

    /**
     * Guarda el registro; si ya existía el de la misma zona, fecha y tipo (observado o pronóstico), lo reemplaza.
     * Uso ON DUPLICATE KEY UPDATE sobre la clave única (id_zona, fecha, es_pronostico): si vuelvo a
     * sincronizar el mismo día, me quedo con el dato más reciente en lugar de tener dos registros.
     * Guardo también el origen (remoto, archivo o manual) y la hora de carga para saber de dónde vino.
     * Las columnas son DECIMAL con un decimal (tres la humedad del suelo), así que redondeo a esa escala;
     * las opcionales que faltan van como NULL y no como 0: un 0 significaría suelo seco o calma total.
     */
    public void guardar(RegistroMeteo m, OrigenDatos origen) throws SQLException {
        actualizar("INSERT INTO registro_meteo (" + COLUMNAS + ", origen) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE temperatura_max_c = VALUES(temperatura_max_c), "
                        + "humedad_min_pct = VALUES(humedad_min_pct), viento_max_kmh = VALUES(viento_max_kmh), "
                        + "precipitacion_mm = VALUES(precipitacion_mm), origen = VALUES(origen), "
                        + "humedad_suelo_m3m3 = VALUES(humedad_suelo_m3m3), temperatura_min_c = VALUES(temperatura_min_c), "
                        + "rafaga_max_kmh = VALUES(rafaga_max_kmh), direccion_viento_grados = VALUES(direccion_viento_grados), "
                        + "prob_precipitacion_pct = VALUES(prob_precipitacion_pct), "
                        + "evapotranspiracion_mm = VALUES(evapotranspiracion_mm), fecha_hora_carga = NOW()",
                m.idZona(), m.fecha(),
                ConexionBD.decimal(m.temperaturaMaxC(), 1), ConexionBD.decimal(m.humedadMinPct(), 1),
                ConexionBD.decimal(m.vientoMaxKmh(), 1), ConexionBD.decimal(m.precipitacionMm(), 1), m.esPronostico(),
                decimal(m.humedadSueloM3m3(), 3), decimal(m.temperaturaMinC(), 1), decimal(m.rafagaMaxKmh(), 1),
                m.direccionVientoGrados(), decimal(m.probPrecipitacionPct(), 1), decimal(m.evapotranspiracionMm(), 1),
                origen);
    }

    /**
     * Devuelve el registro de la zona para la fecha: el observado o, en su defecto, el pronosticado.
     * Lo resuelvo con ORDER BY es_pronostico LIMIT 1: como FALSE (observado) va antes que TRUE,
     * si están los dos me quedo con el observado, que es más confiable.
     * Si no hay datos devuelvo vacío: el servicio omite esa zona del cálculo (RFS20).
     */
    public Optional<RegistroMeteo> obtener(int idZona, LocalDate fecha) throws SQLException {
        return consultarUno("SELECT " + COLUMNAS + " FROM registro_meteo WHERE id_zona = ? AND fecha = ? "
                + "ORDER BY es_pronostico LIMIT 1", MeteoDAO::leer, idZona, fecha);
    }

    /**
     * Pronóstico extendido de la zona: un registro por día entre las dos fechas, en orden. Si un día tiene
     * observado y pronóstico, me quedo con el observado, con el mismo criterio que obtener().
     */
    public List<RegistroMeteo> entre(int idZona, LocalDate desde, LocalDate hasta) throws SQLException {
        Map<LocalDate, RegistroMeteo> porDia = new LinkedHashMap<>();
        for (RegistroMeteo r : consultar("SELECT " + COLUMNAS + " FROM registro_meteo WHERE id_zona = ? "
                + "AND fecha BETWEEN ? AND ? ORDER BY fecha, es_pronostico", MeteoDAO::leer, idZona, desde, hasta)) {
            porDia.putIfAbsent(r.fecha(), r);
        }
        return new ArrayList<>(porDia.values());
    }

    /** Registro del día de cada zona que lo tenga (observado antes que pronóstico), para el viento del mapa. */
    public Map<Integer, RegistroMeteo> delDia(LocalDate fecha) throws SQLException {
        Map<Integer, RegistroMeteo> porZona = new LinkedHashMap<>();
        for (RegistroMeteo r : consultar("SELECT " + COLUMNAS + " FROM registro_meteo WHERE fecha = ? "
                + "ORDER BY id_zona, es_pronostico", MeteoDAO::leer, fecha)) {
            porZona.putIfAbsent(r.idZona(), r);
        }
        return porZona;
    }

    /** Cuándo se cargó el último pronóstico de la zona, para mostrar qué tan nuevo es. Vacío si nunca se cargó. */
    public Optional<LocalDateTime> ultimaCargaPronostico(int idZona) throws SQLException {
        return consultarUno("SELECT MAX(fecha_hora_carga) FROM registro_meteo WHERE id_zona = ? AND es_pronostico",
                rs -> rs.getObject(1, LocalDateTime.class), idZona);
    }

    // Total de registros meteorológicos; me sirve para verificar las cargas.
    public int contar() throws SQLException {
        return entero("SELECT COUNT(*) FROM registro_meteo");
    }

    /** Arma el registro con las columnas de COLUMNAS, en ese orden. getObject devuelve null en las opcionales vacías. */
    private static RegistroMeteo leer(ResultSet rs) throws SQLException {
        Integer direccion = rs.getObject(11, Integer.class);
        return new RegistroMeteo(rs.getInt(1), rs.getObject(2, LocalDate.class), rs.getDouble(3), rs.getDouble(4),
                rs.getDouble(5), rs.getDouble(6), rs.getBoolean(7), doble(rs, 8), doble(rs, 9), doble(rs, 10),
                direccion, doble(rs, 12), doble(rs, 13));
    }

    private static Double doble(ResultSet rs, int columna) throws SQLException {
        BigDecimal valor = rs.getBigDecimal(columna);
        return valor == null ? null : valor.doubleValue();
    }
}
