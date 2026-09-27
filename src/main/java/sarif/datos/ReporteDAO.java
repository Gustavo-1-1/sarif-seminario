package sarif.datos;

import sarif.modelo.EventoGuardia;
import sarif.modelo.FilaDesempeno;
import sarif.modelo.FilaReporteZona;
import sarif.modelo.MedioNotificacion;
import sarif.modelo.TipoAlerta;
import sarif.modelo.TipoEvento;
import sarif.modelo.TipoMarca;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Consultas de los reportes (CU13). Son solo lecturas: las que agrupan focos e índices por zona o por nivel
 * las resuelvo en SQL con GROUP BY en lugar de traer todos los registros a memoria, y la cronología de la
 * guardia junta en una sola consulta los hitos de las alertas, los cambios de nivel y las asignaciones.
 * Uso sentencias preparadas para las fechas del período.
 */
public class ReporteDAO {

    private final Connection cn;

    public ReporteDAO(Connection cn) {
        this.cn = cn;
    }

    /**
     * RFS18: focos e índices de cada zona en el período. Agrupo los focos y los índices en dos subconsultas
     * por separado y después las uno a las zonas con LEFT JOIN: si las juntara en una sola consulta, cada
     * foco se multiplicaría por cada índice de la zona y los conteos saldrían inflados.
     * Para los focos tomo desde el primer instante de "desde" hasta antes del día siguiente a "hasta".
     */
    public List<FilaReporteZona> historicoPorZona(LocalDate desde, LocalDate hasta) throws SQLException {
        String sql = "SELECT z.nombre, COALESCE(f.focos, 0), COALESCE(f.alta, 0), f.frp, COALESCE(i.dias, 0), "
                + "i.promedio, i.maximo, COALESCE(i.altos, 0) "
                + "FROM zona_vigilancia z "
                + "LEFT JOIN (SELECT id_zona, COUNT(*) AS focos, SUM(confianza = 'ALTA') AS alta, "
                + "           AVG(potencia_frp_mw) AS frp FROM foco_calor "
                + "           WHERE fecha_hora_utc >= ? AND fecha_hora_utc < ? GROUP BY id_zona) f ON f.id_zona = z.id_zona "
                + "LEFT JOIN (SELECT i.id_zona, COUNT(*) AS dias, AVG(i.valor_final) AS promedio, MAX(i.valor_final) AS maximo, "
                + "           SUM(n.nombre IN ('ALTO', 'EXTREMO')) AS altos FROM indice_riesgo i "
                + "           JOIN nivel_riesgo n ON n.id_nivel_riesgo = i.id_nivel_riesgo "
                + "           WHERE i.fecha BETWEEN ? AND ? GROUP BY i.id_zona) i ON i.id_zona = z.id_zona "
                + "ORDER BY COALESCE(f.focos, 0) DESC, z.nombre";
        List<FilaReporteZona> filas = new ArrayList<>();
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setObject(1, desde.atStartOfDay());
            ps.setObject(2, hasta.plusDays(1).atStartOfDay());
            ps.setObject(3, desde);
            ps.setObject(4, hasta);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    filas.add(new FilaReporteZona(rs.getString(1), rs.getInt(2), rs.getInt(3), redondear(rs, 4, 1),
                            rs.getInt(5), redondear(rs, 6, 2), redondear(rs, 7, 2), rs.getInt(8)));
                }
            }
        }
        return filas;
    }

    /**
     * RFS19: para cada nivel de riesgo, cuántos días-zona tuvieron ese nivel previsto y en cuántos hubo focos
     * ese mismo día. Parto de nivel_riesgo con LEFT JOIN para que aparezcan los cuatro niveles aunque alguno
     * no se haya dado en el período. El día del foco es su fecha UTC, que es la que informa FIRMS.
     */
    public List<FilaDesempeno> desempeno(LocalDate desde, LocalDate hasta) throws SQLException {
        String sql = "SELECT n.nombre, COUNT(i.id_indice), SUM(COALESCE(f.focos, 0) > 0), SUM(COALESCE(f.focos, 0)) "
                + "FROM nivel_riesgo n "
                + "LEFT JOIN indice_riesgo i ON i.id_nivel_riesgo = n.id_nivel_riesgo AND i.fecha BETWEEN ? AND ? "
                + "LEFT JOIN (SELECT id_zona, DATE(fecha_hora_utc) AS dia, COUNT(*) AS focos FROM foco_calor "
                + "           WHERE fecha_hora_utc >= ? AND fecha_hora_utc < ? "
                + "           GROUP BY id_zona, DATE(fecha_hora_utc)) f ON f.id_zona = i.id_zona AND f.dia = i.fecha "
                + "GROUP BY n.id_nivel_riesgo, n.nombre, n.orden ORDER BY n.orden";
        List<FilaDesempeno> filas = new ArrayList<>();
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setObject(1, desde);
            ps.setObject(2, hasta);
            ps.setObject(3, desde.atStartOfDay());
            ps.setObject(4, hasta.plusDays(1).atStartOfDay());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    filas.add(new FilaDesempeno(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)));
                }
            }
        }
        return filas;
    }

    // Zona de una alerta: sale del foco, del índice o del cambio de nivel, igual que en el listado de alertas.
    private static final String ALERTA_CON_ZONA = "FROM alerta a LEFT JOIN foco_calor f ON f.id_foco = a.id_foco "
            + "LEFT JOIN indice_riesgo i ON i.id_indice = a.id_indice LEFT JOIN cambio_nivel_alerta ca ON ca.id_cambio = a.id_cambio ";

    /**
     * Cronología de la guardia: todos los hitos del período en orden de fecha y hora. Junto con UNION ALL
     * ocho consultas que devuelven las mismas columnas: la alerta generada, su notificación, su cierre o
     * descarte, los cambios de nivel, las asignaciones de recursos, las ubicaciones de recursos en el mapa y
     * las marcas puestas y quitadas (en esas dos uso la columna tipo_alerta para llevar el tipo de marca).
     * Las ubicaciones y marcas pueden estar fuera de las zonas, por eso la zona va con LEFT JOIN; al filtrar
     * por una zona quedan afuera. Después agrego el nombre de la zona y
     * el nivel de alerta que estaba vigente en ese momento, con una subconsulta que busca el último cambio
     * de nivel de la zona anterior al hito (para el hito de cambio de nivel, uso el nivel nuevo).
     * El período se filtra por la fecha y hora en que se registró cada hito.
     *
     * @param idZona 0 para todas las zonas
     */
    public List<EventoGuardia> cronologia(LocalDate desde, LocalDate hasta, int idZona) throws SQLException {
        String sql = "SELECT e.momento, COALESCE(z.nombre, 'Fuera de las zonas') AS zona, e.evento, e.id_alerta, e.tipo_alerta, e.texto, e.extra, "
                + "e.usuario, e.minutos, COALESCE(e.nivel_nuevo, (SELECT n.nombre FROM cambio_nivel_alerta c "
                + "   JOIN nivel_alerta n ON n.id_nivel_alerta = c.id_nivel_alerta "
                + "   WHERE c.id_zona = e.id_zona AND c.fecha_hora <= e.momento "
                + "   ORDER BY c.fecha_hora DESC, c.id_cambio DESC LIMIT 1)) AS nivel "
                + "FROM ("
                + " SELECT a.fecha_hora AS momento, COALESCE(f.id_zona, i.id_zona, ca.id_zona) AS id_zona, 'ALERTA_GENERADA' AS evento, "
                + "   a.id_alerta, a.tipo AS tipo_alerta, a.descripcion AS texto, NULL AS extra, 'Sistema' AS usuario, "
                + "   NULL AS minutos, NULL AS nivel_nuevo " + ALERTA_CON_ZONA
                + " UNION ALL"
                + " SELECT a.fecha_hora_notificacion, COALESCE(f.id_zona, i.id_zona, ca.id_zona), 'ALERTA_NOTIFICADA', a.id_alerta, a.tipo, "
                + "   a.notificado_a, a.medio_notificacion, CONCAT(u.nombre, ' ', u.apellido), "
                + "   TIMESTAMPDIFF(MINUTE, a.fecha_hora, a.fecha_hora_notificacion), NULL " + ALERTA_CON_ZONA
                + "   JOIN usuario u ON u.id_usuario = a.id_usuario_notifica"
                + " UNION ALL"
                + " SELECT a.fecha_hora_resolucion, COALESCE(f.id_zona, i.id_zona, ca.id_zona), CONCAT('ALERTA_', a.estado), a.id_alerta, a.tipo, "
                + "   NULL, NULL, CONCAT(u.nombre, ' ', u.apellido), "
                + "   TIMESTAMPDIFF(MINUTE, a.fecha_hora, a.fecha_hora_resolucion), NULL " + ALERTA_CON_ZONA
                + "   JOIN usuario u ON u.id_usuario = a.id_usuario_resolucion"
                + " UNION ALL"
                + " SELECT c.fecha_hora, c.id_zona, 'CAMBIO_NIVEL', NULL, NULL, c.fundamento, NULL, "
                + "   CONCAT(u.nombre, ' ', u.apellido), NULL, n.nombre "
                + " FROM cambio_nivel_alerta c JOIN nivel_alerta n ON n.id_nivel_alerta = c.id_nivel_alerta "
                + "   JOIN usuario u ON u.id_usuario = c.id_usuario"
                + " UNION ALL"
                + " SELECT s.fecha_hora_registro, s.id_zona, 'ASIGNACION', NULL, NULL, "
                + "   CONCAT(r.denominacion, ' (', IF(r.tipo = 'AEREO', 'aéreo', 'terrestre'), ', dotación ', r.dotacion, "
                + "     ') para el ', DATE_FORMAT(s.fecha, '%d/%m/%Y')), "
                + "   CONCAT_WS(' · ', CONCAT('índice ', FORMAT(ir.valor_final, 2, 'es_AR'), ' ', nr.nombre), s.observaciones), "
                + "   CONCAT(u.nombre, ' ', u.apellido), NULL, NULL "
                + " FROM asignacion s JOIN recurso r ON r.id_recurso = s.id_recurso "
                + "   JOIN usuario u ON u.id_usuario = s.id_usuario "
                + "   LEFT JOIN indice_riesgo ir ON ir.id_indice = s.id_indice "
                + "   LEFT JOIN nivel_riesgo nr ON nr.id_nivel_riesgo = ir.id_nivel_riesgo"
                + " UNION ALL"
                + " SELECT p.fecha_hora, p.id_zona, 'RECURSO_UBICADO', NULL, NULL, "
                + "   CONCAT(r.denominacion, ' (', IF(r.tipo = 'AEREO', 'aéreo', 'terrestre'), ', dotación ', r.dotacion, ') en ', "
                + "     FORMAT(p.latitud, 5, 'en_US'), ', ', FORMAT(p.longitud, 5, 'en_US')), "
                + "   p.observaciones, CONCAT(u.nombre, ' ', u.apellido), NULL, NULL "
                + " FROM posicion_recurso p JOIN recurso r ON r.id_recurso = p.id_recurso "
                + "   JOIN usuario u ON u.id_usuario = p.id_usuario"
                + " UNION ALL"
                + " SELECT m.fecha_hora, m.id_zona, 'MARCA_AGREGADA', NULL, m.tipo, m.descripcion, "
                + "   CONCAT(FORMAT(m.latitud, 5, 'en_US'), ', ', FORMAT(m.longitud, 5, 'en_US')), "
                + "   CONCAT(u.nombre, ' ', u.apellido), NULL, NULL "
                + " FROM marca_mapa m JOIN usuario u ON u.id_usuario = m.id_usuario"
                + " UNION ALL"
                + " SELECT m.fecha_hora_baja, m.id_zona, 'MARCA_QUITADA', NULL, m.tipo, m.descripcion, NULL, "
                + "   CONCAT(u.nombre, ' ', u.apellido), NULL, NULL "
                + " FROM marca_mapa m JOIN usuario u ON u.id_usuario = m.id_usuario_baja"
                + ") e LEFT JOIN zona_vigilancia z ON z.id_zona = e.id_zona "
                + "WHERE e.momento >= ? AND e.momento < ? AND (? = 0 OR e.id_zona = ?) "
                + "ORDER BY e.momento, FIELD(e.evento, 'CAMBIO_NIVEL', 'ALERTA_GENERADA', 'ALERTA_NOTIFICADA', "
                + "'ASIGNACION', 'RECURSO_UBICADO', 'MARCA_AGREGADA', 'MARCA_QUITADA', 'ALERTA_CERRADA', 'ALERTA_DESCARTADA'), "
                + "e.id_alerta";
        List<EventoGuardia> eventos = new ArrayList<>();
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setObject(1, desde.atStartOfDay());
            ps.setObject(2, hasta.plusDays(1).atStartOfDay());
            ps.setInt(3, idZona);
            ps.setInt(4, idZona);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    eventos.add(leerEvento(rs));
                }
            }
        }
        return eventos;
    }

    // Armo el texto del hito según su tipo; los nombres técnicos (tipo de alerta, medio) los paso a texto legible.
    private static EventoGuardia leerEvento(ResultSet rs) throws SQLException {
        TipoEvento tipo = TipoEvento.valueOf(rs.getString("evento"));
        Integer idAlerta = rs.getObject("id_alerta") == null ? null : rs.getInt("id_alerta");
        String alerta = idAlerta == null ? "" : "#" + idAlerta + " " + TipoAlerta.valueOf(rs.getString("tipo_alerta")).texto();
        String texto = rs.getString("texto");
        String extra = rs.getString("extra");
        String detalle = switch (tipo) {
            case ALERTA_GENERADA -> alerta + ": " + texto;
            case ALERTA_NOTIFICADA -> alerta + ": avisada a " + texto + " (" + MedioNotificacion.valueOf(extra).texto() + ")";
            case ALERTA_CERRADA -> alerta + ": la situación se resolvió";
            case ALERTA_DESCARTADA -> alerta + ": descartada (no correspondía o falso positivo)";
            case CAMBIO_NIVEL -> "Pasa a " + rs.getString("nivel") + ". Fundamento: " + texto;
            case ASIGNACION, RECURSO_UBICADO -> extra == null || extra.isBlank() ? texto : texto + " · " + extra;
            case MARCA_AGREGADA -> TipoMarca.valueOf(rs.getString("tipo_alerta")).texto() + ": " + texto + " en " + extra;
            case MARCA_QUITADA -> TipoMarca.valueOf(rs.getString("tipo_alerta")).texto() + ": " + texto;
        };
        Long minutos = rs.getObject("minutos") == null ? null : rs.getLong("minutos");
        return new EventoGuardia(rs.getObject("momento", LocalDateTime.class), rs.getString("zona"), tipo, detalle,
                rs.getString("nivel"), rs.getString("usuario"), idAlerta, minutos);
    }

    // Leo un promedio que puede ser NULL (sin datos en el período) y lo redondeo para mostrarlo.
    private static Double redondear(ResultSet rs, int columna, int decimales) throws SQLException {
        double valor = rs.getDouble(columna);
        if (rs.wasNull()) {
            return null;
        }
        double factor = Math.pow(10, decimales);
        return Math.round(valor * factor) / factor;
    }
}
