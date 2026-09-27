package sarif.datos;

import sarif.modelo.OrigenDatos;
import sarif.modelo.RegistroMeteo;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
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
public class MeteoDAO {

    private static final String COLUMNAS = "id_zona, fecha, temperatura_max_c, humedad_min_pct, viento_max_kmh, "
            + "precipitacion_mm, es_pronostico, humedad_suelo_m3m3, temperatura_min_c, rafaga_max_kmh, "
            + "direccion_viento_grados, prob_precipitacion_pct, evapotranspiracion_mm";

    private final Connection cn;

    // Recibo la conexión para que todo el lote de meteorología se grabe en la transacción del servicio.
    public MeteoDAO(Connection cn) {
        this.cn = cn;
    }

    /**
     * Guarda el registro; si ya existía el de la misma zona, fecha y tipo (observado o pronóstico), lo reemplaza.
     * Uso ON DUPLICATE KEY UPDATE sobre la clave única (id_zona, fecha, es_pronostico): si vuelvo a
     * sincronizar el mismo día, me quedo con el dato más reciente en lugar de tener dos registros.
     * Guardo también el origen (remoto, archivo o manual) y la hora de carga para saber de dónde vino.
     */
    public void guardar(RegistroMeteo m, OrigenDatos origen) throws SQLException {
        String sql = "INSERT INTO registro_meteo (" + COLUMNAS + ", origen) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE temperatura_max_c = VALUES(temperatura_max_c), "
                + "humedad_min_pct = VALUES(humedad_min_pct), viento_max_kmh = VALUES(viento_max_kmh), "
                + "precipitacion_mm = VALUES(precipitacion_mm), origen = VALUES(origen), "
                + "humedad_suelo_m3m3 = VALUES(humedad_suelo_m3m3), temperatura_min_c = VALUES(temperatura_min_c), "
                + "rafaga_max_kmh = VALUES(rafaga_max_kmh), direccion_viento_grados = VALUES(direccion_viento_grados), "
                + "prob_precipitacion_pct = VALUES(prob_precipitacion_pct), "
                + "evapotranspiracion_mm = VALUES(evapotranspiracion_mm), fecha_hora_carga = NOW()";
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setInt(1, m.idZona());
            ps.setObject(2, m.fecha());
            // Las columnas son DECIMAL con un decimal, así que redondeo a esa escala.
            ps.setBigDecimal(3, ConexionBD.decimal(m.temperaturaMaxC(), 1));
            ps.setBigDecimal(4, ConexionBD.decimal(m.humedadMinPct(), 1));
            ps.setBigDecimal(5, ConexionBD.decimal(m.vientoMaxKmh(), 1));
            ps.setBigDecimal(6, ConexionBD.decimal(m.precipitacionMm(), 1));
            ps.setBoolean(7, m.esPronostico());
            // Las opcionales que faltan van como NULL y no como 0: un 0 significaría suelo seco o calma total.
            ps.setBigDecimal(8, decimalONulo(m.humedadSueloM3m3(), 3));
            ps.setBigDecimal(9, decimalONulo(m.temperaturaMinC(), 1));
            ps.setBigDecimal(10, decimalONulo(m.rafagaMaxKmh(), 1));
            if (m.direccionVientoGrados() == null) {
                ps.setNull(11, Types.SMALLINT);
            } else {
                ps.setInt(11, m.direccionVientoGrados());
            }
            ps.setBigDecimal(12, decimalONulo(m.probPrecipitacionPct(), 1));
            ps.setBigDecimal(13, decimalONulo(m.evapotranspiracionMm(), 1));
            ps.setString(14, origen.name());
            ps.executeUpdate();
        }
    }

    /**
     * Devuelve el registro de la zona para la fecha: el observado o, en su defecto, el pronosticado.
     * Lo resuelvo con ORDER BY es_pronostico LIMIT 1: como FALSE (observado) va antes que TRUE,
     * si están los dos me quedo con el observado, que es más confiable.
     */
    public Optional<RegistroMeteo> obtener(int idZona, LocalDate fecha) throws SQLException {
        String sql = "SELECT " + COLUMNAS + " FROM registro_meteo WHERE id_zona = ? AND fecha = ? "
                + "ORDER BY es_pronostico LIMIT 1";
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setInt(1, idZona);
            ps.setObject(2, fecha);
            try (ResultSet rs = ps.executeQuery()) {
                // Si no hay datos devuelvo vacío: el servicio omite esa zona del cálculo (RFS20).
                return rs.next() ? Optional.of(leer(rs)) : Optional.empty();
            }
        }
    }

    /**
     * Pronóstico extendido de la zona: un registro por día entre las dos fechas, en orden. Si un día tiene
     * observado y pronóstico, me quedo con el observado, con el mismo criterio que obtener().
     */
    public List<RegistroMeteo> entre(int idZona, LocalDate desde, LocalDate hasta) throws SQLException {
        String sql = "SELECT " + COLUMNAS + " FROM registro_meteo WHERE id_zona = ? AND fecha BETWEEN ? AND ? "
                + "ORDER BY fecha, es_pronostico";
        Map<LocalDate, RegistroMeteo> porDia = new LinkedHashMap<>();
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setInt(1, idZona);
            ps.setObject(2, desde);
            ps.setObject(3, hasta);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    RegistroMeteo r = leer(rs);
                    porDia.putIfAbsent(r.fecha(), r);
                }
            }
        }
        return new ArrayList<>(porDia.values());
    }

    /** Registro del día de cada zona que lo tenga (observado antes que pronóstico), para el viento del mapa. */
    public Map<Integer, RegistroMeteo> delDia(LocalDate fecha) throws SQLException {
        String sql = "SELECT " + COLUMNAS + " FROM registro_meteo WHERE fecha = ? ORDER BY id_zona, es_pronostico";
        Map<Integer, RegistroMeteo> porZona = new LinkedHashMap<>();
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setObject(1, fecha);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    RegistroMeteo r = leer(rs);
                    porZona.putIfAbsent(r.idZona(), r);
                }
            }
        }
        return porZona;
    }

    /** Cuándo se cargó el último pronóstico de la zona, para mostrar qué tan nuevo es. Vacío si nunca se cargó. */
    public Optional<LocalDateTime> ultimaCargaPronostico(int idZona) throws SQLException {
        String sql = "SELECT MAX(fecha_hora_carga) FROM registro_meteo WHERE id_zona = ? AND es_pronostico";
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setInt(1, idZona);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return Optional.ofNullable(rs.getObject(1, LocalDateTime.class));
            }
        }
    }

    // Total de registros meteorológicos; me sirve para verificar las cargas.
    public int contar() throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement("SELECT COUNT(*) FROM registro_meteo"); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
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

    private static BigDecimal decimalONulo(Double valor, int escala) {
        return valor == null ? null : ConexionBD.decimal(valor, escala);
    }
}
