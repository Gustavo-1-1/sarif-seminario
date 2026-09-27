package sarif.datos;

import sarif.modelo.ActivoProtegido;
import sarif.modelo.FilaTablero;
import sarif.modelo.Vertice;
import sarif.modelo.Zona;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Acceso a las zonas de vigilancia, sus activos protegidos y el tablero. Acá está el alta de zona (CU01),
 * la activación y desactivación de la vigilancia (CU04/CU05) y la consulta del tablero (CU06).
 * Uso sentencias preparadas en todas las consultas que llevan datos, para evitar inyección SQL.
 */
public class ZonaDAO {

    // Columnas que leo siempre que armo una Zona; las dejo en una constante para no repetirlas.
    private static final String COLUMNAS = "id_zona, nombre, descripcion, latitud_min, latitud_max, "
            + "longitud_min, longitud_max, vigilancia_activa";

    private final Connection cn;

    // Recibo la conexión por constructor: en el alta (CU01) la zona, su primer relevamiento y su nivel
    // inicial se graban en una sola transacción que maneja el servicio.
    public ZonaDAO(Connection cn) {
        this.cn = cn;
    }

    /**
     * Inserta la zona y devuelve el id generado (lo leo con getGeneratedKeys()), que el servicio necesita
     * para registrar el relevamiento inicial y el nivel de alerta. Las coordenadas van como BigDecimal
     * con 5 decimales, igual que la columna DECIMAL, para no arrastrar errores de redondeo del double.
     */
    public int insertar(Zona z) throws SQLException {
        String sql = "INSERT INTO zona_vigilancia (nombre, descripcion, latitud_min, latitud_max, longitud_min, "
                + "longitud_max, vigilancia_activa) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = cn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, z.getNombre().trim());
            ps.setString(2, z.getDescripcion());
            ps.setBigDecimal(3, ConexionBD.decimal(z.getLatitudMin(), 5));
            ps.setBigDecimal(4, ConexionBD.decimal(z.getLatitudMax(), 5));
            ps.setBigDecimal(5, ConexionBD.decimal(z.getLongitudMin(), 5));
            ps.setBigDecimal(6, ConexionBD.decimal(z.getLongitudMax(), 5));
            ps.setBoolean(7, z.isVigilanciaActiva());
            ps.executeUpdate();
            int id;
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                id = rs.getInt(1);
            }
            insertarVertices(id, z);
            return id;
        }
    }

    /**
     * Si la zona es un polígono, grabo sus vértices en orden (1, 2, 3...). Uso un lote (addBatch) para
     * mandarlos todos juntos; va dentro de la misma transacción que el alta de la zona.
     */
    private void insertarVertices(int idZona, Zona z) throws SQLException {
        if (!z.esPoligono()) {
            return;
        }
        try (PreparedStatement ps = cn.prepareStatement(
                "INSERT INTO zona_vertice (id_zona, orden, latitud, longitud) VALUES (?, ?, ?, ?)")) {
            int orden = 1;
            for (Vertice v : z.getVertices()) {
                ps.setInt(1, idZona);
                ps.setInt(2, orden++);
                ps.setBigDecimal(3, ConexionBD.decimal(v.latitud(), 5));
                ps.setBigDecimal(4, ConexionBD.decimal(v.longitud(), 5));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * Indica si ya hay una zona con ese nombre. La base igual lo impide con un UNIQUE, pero lo consulto
     * antes para mostrar un mensaje claro en lugar de un error de SQL.
     */
    public boolean existeNombre(String nombre) throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement("SELECT COUNT(*) FROM zona_vigilancia WHERE nombre = ?")) {
            ps.setString(1, nombre.trim());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    /**
     * Indica si el área de la zona se superpone con alguna zona registrada (distinta de ella misma).
     * En SQL solo filtro las zonas cuyo rectángulo toca al de la nueva (es barato y descarta casi todas);
     * la cuenta fina, que con polígonos no se puede hacer con un WHERE simple, la hace Zona.seSuperponeCon.
     */
    public boolean seSuperpone(Zona z) throws SQLException {
        String sql = "SELECT " + COLUMNAS + " FROM zona_vigilancia WHERE id_zona <> ? "
                + "AND latitud_min < ? AND ? < latitud_max AND longitud_min < ? AND ? < longitud_max";
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setInt(1, z.getId());
            ps.setBigDecimal(2, ConexionBD.decimal(z.getLatitudMax(), 5));
            ps.setBigDecimal(3, ConexionBD.decimal(z.getLatitudMin(), 5));
            ps.setBigDecimal(4, ConexionBD.decimal(z.getLongitudMax(), 5));
            ps.setBigDecimal(5, ConexionBD.decimal(z.getLongitudMin(), 5));
            List<Zona> candidatas = leer(ps);
            return candidatas.stream().anyMatch(z::seSuperponeCon);
        }
    }

    public List<Zona> listar() throws SQLException {
        return consultar("SELECT " + COLUMNAS + " FROM zona_vigilancia ORDER BY id_zona");
    }

    /** Solo las zonas con vigilancia activa: son las que proceso al sincronizar y al calcular el índice. */
    public List<Zona> listarActivas() throws SQLException {
        return consultar("SELECT " + COLUMNAS + " FROM zona_vigilancia WHERE vigilancia_activa = TRUE ORDER BY id_zona");
    }

    /** Activa o desactiva la vigilancia de la zona (CU04 y CU05). */
    public void cambiarVigilancia(int idZona, boolean activa) throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement(
                "UPDATE zona_vigilancia SET vigilancia_activa = ? WHERE id_zona = ?")) {
            ps.setBoolean(1, activa);
            ps.setInt(2, idZona);
            ps.executeUpdate();
        }
    }

    /**
     * Borra la zona. Hoy lo uso en las pruebas de integración para limpiar lo que crean. Si la zona
     * ya tiene activos o focos asociados, MySQL rechaza el borrado por las claves foráneas con RESTRICT.
     */
    public void eliminar(int idZona) throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement("DELETE FROM zona_vigilancia WHERE id_zona = ?")) {
            ps.setInt(1, idZona);
            ps.executeUpdate();
        }
    }

    /**
     * Filas del tablero (CU06), leídas de la vista v_tablero_zonas, que ya junta el índice, el nivel de riesgo
     * y el nivel de alerta vigentes de cada zona. Las ordeno por índice descendente; las zonas sin índice
     * van al final ("indice IS NULL" da 0 o 1, y los 0 salen primero).
     */
    public List<FilaTablero> tablero() throws SQLException {
        String sql = "SELECT id_zona, nombre, vigilancia_activa, fecha_indice, indice, nivel_riesgo, nivel_alerta "
                + "FROM v_tablero_zonas ORDER BY indice IS NULL, indice DESC, nombre";
        List<FilaTablero> filas = new ArrayList<>();
        try (PreparedStatement ps = cn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Date fecha = rs.getDate("fecha_indice");
                double indice = rs.getDouble("indice");
                // wasNull() se refiere a la última columna leída (indice), por eso lo llamo justo después de getDouble.
                filas.add(new FilaTablero(rs.getInt("id_zona"), rs.getString("nombre"), rs.getBoolean("vigilancia_activa"),
                        fecha == null ? null : fecha.toLocalDate(), rs.wasNull() ? null : indice,
                        rs.getString("nivel_riesgo"), rs.getString("nivel_alerta")));
            }
        }
        return filas;
    }

    /** Activos protegidos de la zona, que uso para detectar focos cercanos a ellos (CU15). */
    public List<ActivoProtegido> activos(int idZona) throws SQLException {
        String sql = "SELECT id_activo, id_zona, nombre, tipo, latitud, longitud, distancia_alerta_km "
                + "FROM activo_protegido WHERE id_zona = ? ORDER BY id_activo";
        List<ActivoProtegido> lista = new ArrayList<>();
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setInt(1, idZona);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(new ActivoProtegido(rs.getInt("id_activo"), rs.getInt("id_zona"), rs.getString("nombre"),
                            rs.getString("tipo"), rs.getDouble("latitud"), rs.getDouble("longitud"),
                            rs.getDouble("distancia_alerta_km")));
                }
            }
        }
        return lista;
    }

    /** Todos los activos protegidos, ordenados por zona y nombre, para la pantalla de activos (CU02). */
    public List<ActivoProtegido> listarActivos() throws SQLException {
        String sql = "SELECT id_activo, id_zona, nombre, tipo, latitud, longitud, distancia_alerta_km "
                + "FROM activo_protegido ORDER BY id_zona, nombre";
        List<ActivoProtegido> lista = new ArrayList<>();
        try (PreparedStatement ps = cn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                lista.add(new ActivoProtegido(rs.getInt("id_activo"), rs.getInt("id_zona"), rs.getString("nombre"),
                        rs.getString("tipo"), rs.getDouble("latitud"), rs.getDouble("longitud"),
                        rs.getDouble("distancia_alerta_km")));
            }
        }
        return lista;
    }

    /**
     * Indica si la zona ya tiene un activo con ese nombre. La base lo impide con el UNIQUE (zona, nombre),
     * pero lo consulto antes para mostrar un mensaje claro.
     */
    public boolean existeActivo(int idZona, String nombre) throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement(
                "SELECT COUNT(*) FROM activo_protegido WHERE id_zona = ? AND nombre = ?")) {
            ps.setInt(1, idZona);
            ps.setString(2, nombre.trim());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    /** Inserta un activo protegido (CU02). Las coordenadas van con 5 decimales, igual que las columnas DECIMAL. */
    public void insertarActivo(ActivoProtegido a) throws SQLException {
        String sql = "INSERT INTO activo_protegido (id_zona, nombre, tipo, latitud, longitud, distancia_alerta_km) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setInt(1, a.idZona());
            ps.setString(2, a.nombre().trim());
            ps.setString(3, a.tipo());
            ps.setBigDecimal(4, ConexionBD.decimal(a.latitud(), 5));
            ps.setBigDecimal(5, ConexionBD.decimal(a.longitud(), 5));
            ps.setBigDecimal(6, ConexionBD.decimal(a.distanciaAlertaKm(), 2));
            ps.executeUpdate();
        }
    }

    // Método común para los listados de zonas. El SQL lo armo solo con texto fijo (no con datos del usuario).
    private List<Zona> consultar(String sql) throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            return leer(ps);
        }
    }

    /**
     * Armo las zonas y les cargo los vértices. En vez de una consulta de vértices por zona, traigo todos los
     * vértices en una sola consulta ordenada y los reparto por id de zona (son pocas zonas y pocos vértices).
     */
    private List<Zona> leer(PreparedStatement ps) throws SQLException {
        List<Zona> zonas = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                zonas.add(new Zona(rs.getInt("id_zona"), rs.getString("nombre"), rs.getString("descripcion"),
                        rs.getDouble("latitud_min"), rs.getDouble("latitud_max"),
                        rs.getDouble("longitud_min"), rs.getDouble("longitud_max"),
                        rs.getBoolean("vigilancia_activa")));
            }
        }
        if (zonas.isEmpty()) {
            return zonas;
        }
        Map<Integer, List<Vertice>> vertices = new HashMap<>();
        try (PreparedStatement pv = cn.prepareStatement(
                "SELECT id_zona, latitud, longitud FROM zona_vertice ORDER BY id_zona, orden");
             ResultSet rs = pv.executeQuery()) {
            while (rs.next()) {
                vertices.computeIfAbsent(rs.getInt("id_zona"), k -> new ArrayList<>())
                        .add(new Vertice(rs.getDouble("latitud"), rs.getDouble("longitud")));
            }
        }
        for (Zona z : zonas) {
            List<Vertice> lista = vertices.get(z.getId());
            if (lista != null) {
                z.setVertices(lista);
            }
        }
        return zonas;
    }
}
