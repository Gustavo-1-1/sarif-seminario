package sarif.datos;

import sarif.modelo.ActivoProtegido;
import sarif.modelo.FilaTablero;
import sarif.modelo.Vertice;
import sarif.modelo.Zona;

import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Acceso a las zonas de vigilancia, sus activos protegidos y el tablero. Acá está el alta de zona (CU01),
 * la activación y desactivación de la vigilancia (CU04/CU05) y la consulta del tablero (CU06).
 * Todas las consultas que llevan datos van con sentencias preparadas (lo resuelve DaoBase), para evitar inyección SQL.
 */
public class ZonaDAO extends DaoBase {

    // Columnas que leo siempre que armo una Zona; las dejo en una constante para no repetirlas.
    private static final String COLUMNAS = "id_zona, nombre, descripcion, latitud_min, latitud_max, "
            + "longitud_min, longitud_max, vigilancia_activa";
    private static final String COLUMNAS_ACTIVO = "id_activo, id_zona, nombre, tipo, latitud, longitud, distancia_alerta_km";

    // Recibo la conexión por constructor: en el alta (CU01) la zona, su primer relevamiento y su nivel
    // inicial se graban en una sola transacción que maneja el servicio.
    public ZonaDAO(Connection cn) {
        super(cn);
    }

    /**
     * Inserta la zona y devuelve el id generado, que el servicio necesita para registrar el relevamiento
     * inicial y el nivel de alerta. Las coordenadas van como BigDecimal con 5 decimales, igual que la
     * columna DECIMAL, para no arrastrar errores de redondeo del double.
     */
    public int insertar(Zona z) throws SQLException {
        int id = (int) insertarConClave("INSERT INTO zona_vigilancia (nombre, descripcion, latitud_min, latitud_max, "
                        + "longitud_min, longitud_max, vigilancia_activa) VALUES (?, ?, ?, ?, ?, ?, ?)",
                z.getNombre().trim(), z.getDescripcion(),
                ConexionBD.decimal(z.getLatitudMin(), 5), ConexionBD.decimal(z.getLatitudMax(), 5),
                ConexionBD.decimal(z.getLongitudMin(), 5), ConexionBD.decimal(z.getLongitudMax(), 5),
                z.isVigilanciaActiva());
        insertarVertices(id, z);
        return id;
    }

    /**
     * Si la zona es un polígono, grabo sus vértices en orden (1, 2, 3...) en un solo lote, dentro de la misma
     * transacción que el alta. Cada vértice es una fila de cuatro valores (un arreglo de tamaño fijo) y la
     * lista crece con la cantidad de vértices que dibujó el operador.
     */
    private void insertarVertices(int idZona, Zona z) throws SQLException {
        if (!z.esPoligono()) {
            return;
        }
        List<Object[]> filas = new ArrayList<>();
        int orden = 1;
        for (Vertice v : z.getVertices()) {
            filas.add(new Object[]{idZona, orden++, ConexionBD.decimal(v.latitud(), 5), ConexionBD.decimal(v.longitud(), 5)});
        }
        lote("INSERT INTO zona_vertice (id_zona, orden, latitud, longitud) VALUES (?, ?, ?, ?)", filas);
    }

    /**
     * Indica si ya hay una zona con ese nombre. La base igual lo impide con un UNIQUE, pero lo consulto
     * antes para mostrar un mensaje claro en lugar de un error de SQL.
     */
    public boolean existeNombre(String nombre) throws SQLException {
        return existe("SELECT COUNT(*) FROM zona_vigilancia WHERE nombre = ?", nombre.trim());
    }

    /**
     * Indica si el área de la zona se superpone con alguna zona registrada (distinta de ella misma).
     * En SQL solo filtro las zonas cuyo rectángulo toca al de la nueva (es barato y descarta casi todas);
     * la cuenta fina, que con polígonos no se puede hacer con un WHERE simple, la hace Zona.seSuperponeCon.
     */
    public boolean seSuperpone(Zona z) throws SQLException {
        List<Zona> candidatas = consultarZonas("SELECT " + COLUMNAS + " FROM zona_vigilancia WHERE id_zona <> ? "
                        + "AND latitud_min < ? AND ? < latitud_max AND longitud_min < ? AND ? < longitud_max",
                z.getId(), ConexionBD.decimal(z.getLatitudMax(), 5), ConexionBD.decimal(z.getLatitudMin(), 5),
                ConexionBD.decimal(z.getLongitudMax(), 5), ConexionBD.decimal(z.getLongitudMin(), 5));
        return candidatas.stream().anyMatch(z::seSuperponeCon);
    }

    public List<Zona> listar() throws SQLException {
        return consultarZonas("SELECT " + COLUMNAS + " FROM zona_vigilancia ORDER BY id_zona");
    }

    /** Solo las zonas con vigilancia activa: son las que proceso al sincronizar y al calcular el índice. */
    public List<Zona> listarActivas() throws SQLException {
        return consultarZonas("SELECT " + COLUMNAS + " FROM zona_vigilancia WHERE vigilancia_activa = TRUE ORDER BY id_zona");
    }

    /** Activa o desactiva la vigilancia de la zona (CU04 y CU05). */
    public void cambiarVigilancia(int idZona, boolean activa) throws SQLException {
        actualizar("UPDATE zona_vigilancia SET vigilancia_activa = ? WHERE id_zona = ?", activa, idZona);
    }

    /**
     * Borra la zona. Hoy lo uso en las pruebas de integración para limpiar lo que crean. Si la zona
     * ya tiene activos o focos asociados, MySQL rechaza el borrado por las claves foráneas con RESTRICT
     * (error 1451, que DatosException traduce).
     */
    public void eliminar(int idZona) throws SQLException {
        actualizar("DELETE FROM zona_vigilancia WHERE id_zona = ?", idZona);
    }

    /** Cantidad de zonas registradas. */
    public int contar() throws SQLException {
        return entero("SELECT COUNT(*) FROM zona_vigilancia");
    }

    /**
     * Filas del tablero (CU06), leídas de la vista v_tablero_zonas, que ya junta el índice, el nivel de riesgo
     * y el nivel de alerta vigentes de cada zona. Las ordeno por índice descendente; las zonas sin índice
     * van al final ("indice IS NULL" da 0 o 1, y los 0 salen primero).
     */
    public List<FilaTablero> tablero() throws SQLException {
        return consultar("SELECT id_zona, nombre, vigilancia_activa, fecha_indice, indice, nivel_riesgo, nivel_alerta "
                + "FROM v_tablero_zonas ORDER BY indice IS NULL, indice DESC, nombre", rs -> {
            Date fecha = rs.getDate("fecha_indice");
            double indice = rs.getDouble("indice");
            // wasNull() se refiere a la última columna leída (indice), por eso lo llamo justo después de getDouble.
            return new FilaTablero(rs.getInt("id_zona"), rs.getString("nombre"), rs.getBoolean("vigilancia_activa"),
                    fecha == null ? null : fecha.toLocalDate(), rs.wasNull() ? null : indice,
                    rs.getString("nivel_riesgo"), rs.getString("nivel_alerta"));
        });
    }

    /** Activos protegidos de la zona, que uso para detectar focos cercanos a ellos (CU15). */
    public List<ActivoProtegido> activos(int idZona) throws SQLException {
        return consultar("SELECT " + COLUMNAS_ACTIVO + " FROM activo_protegido WHERE id_zona = ? ORDER BY id_activo",
                ZonaDAO::activo, idZona);
    }

    /** Todos los activos protegidos, ordenados por zona y nombre, para la pantalla de activos (CU02). */
    public List<ActivoProtegido> listarActivos() throws SQLException {
        return consultar("SELECT " + COLUMNAS_ACTIVO + " FROM activo_protegido ORDER BY id_zona, nombre", ZonaDAO::activo);
    }

    /**
     * Indica si la zona ya tiene un activo con ese nombre. La base lo impide con el UNIQUE (zona, nombre),
     * pero lo consulto antes para mostrar un mensaje claro.
     */
    public boolean existeActivo(int idZona, String nombre) throws SQLException {
        return existe("SELECT COUNT(*) FROM activo_protegido WHERE id_zona = ? AND nombre = ?", idZona, nombre.trim());
    }

    /** Inserta un activo protegido (CU02). Las coordenadas van con 5 decimales, igual que las columnas DECIMAL. */
    public void insertarActivo(ActivoProtegido a) throws SQLException {
        actualizar("INSERT INTO activo_protegido (id_zona, nombre, tipo, latitud, longitud, distancia_alerta_km) "
                        + "VALUES (?, ?, ?, ?, ?, ?)", a.idZona(), a.nombre().trim(), a.tipo(),
                ConexionBD.decimal(a.latitud(), 5), ConexionBD.decimal(a.longitud(), 5),
                ConexionBD.decimal(a.distanciaAlertaKm(), 2));
    }

    /**
     * Armo las zonas y les cargo los vértices. En vez de una consulta de vértices por zona, traigo todos los
     * vértices en una sola consulta ordenada y los reparto por id de zona (son pocas zonas y pocos vértices).
     */
    private List<Zona> consultarZonas(String sql, Object... parametros) throws SQLException {
        List<Zona> zonas = consultar(sql, ZonaDAO::zona, parametros);
        if (zonas.isEmpty()) {
            return zonas;
        }
        Map<Integer, List<Vertice>> vertices = new HashMap<>();
        for (VerticeDeZona v : consultar("SELECT id_zona, latitud, longitud FROM zona_vertice ORDER BY id_zona, orden",
                rs -> new VerticeDeZona(rs.getInt(1), new Vertice(rs.getDouble(2), rs.getDouble(3))))) {
            vertices.computeIfAbsent(v.idZona(), k -> new ArrayList<>()).add(v.vertice());
        }
        for (Zona z : zonas) {
            List<Vertice> lista = vertices.get(z.getId());
            if (lista != null) {
                z.setVertices(lista);
            }
        }
        return zonas;
    }

    // Un vértice leído junto con la zona a la que pertenece, para repartirlos después por zona.
    private record VerticeDeZona(int idZona, Vertice vertice) {
    }

    private static Zona zona(ResultSet rs) throws SQLException {
        return new Zona(rs.getInt("id_zona"), rs.getString("nombre"), rs.getString("descripcion"),
                rs.getDouble("latitud_min"), rs.getDouble("latitud_max"),
                rs.getDouble("longitud_min"), rs.getDouble("longitud_max"),
                rs.getBoolean("vigilancia_activa"));
    }

    private static ActivoProtegido activo(ResultSet rs) throws SQLException {
        return new ActivoProtegido(rs.getInt("id_activo"), rs.getInt("id_zona"), rs.getString("nombre"),
                rs.getString("tipo"), rs.getDouble("latitud"), rs.getDouble("longitud"),
                rs.getDouble("distancia_alerta_km"));
    }
}
