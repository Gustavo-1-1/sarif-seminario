package sarif.datos;

import sarif.modelo.NivelAlerta;
import sarif.modelo.NivelRiesgo;
import sarif.modelo.ParametrosIndice;
import sarif.modelo.TipoCombustible;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Acceso a la configuración del sistema: parámetros del índice, catálogos (niveles de riesgo, niveles de
 * alerta, tipos de combustible) e historial de cambios de nivel de alerta.
 * Junté todo esto en un solo DAO porque son tablas chicas y de consulta, y así no llené el paquete de
 * clases de tres líneas. Los usuarios tienen su propio DAO (UsuarioDAO) desde que se administran.
 */
public class ConfiguracionDAO {

    private final Connection cn;

    // Igual que en los demás DAO, recibo la conexión para que el servicio maneje la transacción.
    public ConfiguracionDAO(Connection cn) {
        this.cn = cn;
    }

    /** Devuelve todos los parámetros de la tabla parametro como un mapa clave -> valor (texto). */
    public Map<String, String> parametros() throws SQLException {
        Map<String, String> mapa = new HashMap<>();
        try (PreparedStatement ps = cn.prepareStatement("SELECT clave, valor FROM parametro"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                mapa.put(rs.getString(1), rs.getString(2));
            }
        }
        return mapa;
    }

    public String parametro(String clave, String porDefecto) throws SQLException {
        return parametros().getOrDefault(clave, porDefecto);
    }

    /**
     * Guarda el valor de un parámetro. Uso INSERT ... ON DUPLICATE KEY UPDATE (la clave es UNIQUE) para
     * que funcione tanto si el parámetro ya existe como si alguien lo borró de la tabla.
     */
    public void guardarParametro(String clave, String valor) throws SQLException {
        String sql = "INSERT INTO parametro (clave, valor) VALUES (?, ?) ON DUPLICATE KEY UPDATE valor = ?";
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setString(1, clave);
            ps.setString(2, valor);
            ps.setString(3, valor);
            ps.executeUpdate();
        }
    }

    /**
     * Arma los parámetros del índice a partir de la tabla parametro. Si falta alguno o está vacío,
     * uso el valor del diseño (ParametrosIndice.porDefecto) para que el cálculo no se rompa.
     */
    public ParametrosIndice parametrosIndice() throws SQLException {
        Map<String, String> p = parametros();
        ParametrosIndice d = ParametrosIndice.porDefecto();
        return new ParametrosIndice(
                numero(p, "peso_historico", d.pesoHistorico()),
                numero(p, "peso_meteorologico", d.pesoMeteorologico()),
                numero(p, "peso_combustible", d.pesoCombustible()),
                (int) numero(p, "ventana_dias", d.ventanaDias()),
                (int) numero(p, "temporadas_historicas", d.temporadas()),
                numero(p, "saturacion_focos", d.saturacionFocos()),
                numero(p, "carga_referencia", d.cargaReferencia()));
    }

    // Catálogos: los devuelvo ordenados para mostrarlos en pantalla y para recorrer los niveles de menor a mayor.
    public List<NivelRiesgo> nivelesRiesgo() throws SQLException {
        String sql = "SELECT id_nivel_riesgo, nombre, umbral_min, umbral_max, orden, color FROM nivel_riesgo ORDER BY orden";
        List<NivelRiesgo> lista = new ArrayList<>();
        try (PreparedStatement ps = cn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                lista.add(new NivelRiesgo(rs.getInt(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4), rs.getInt(5),
                        rs.getString(6)));
            }
        }
        return lista;
    }

    public List<NivelAlerta> nivelesAlerta() throws SQLException {
        List<NivelAlerta> lista = new ArrayList<>();
        try (PreparedStatement ps = cn.prepareStatement("SELECT id_nivel_alerta, nombre, orden FROM nivel_alerta ORDER BY orden");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                lista.add(new NivelAlerta(rs.getInt(1), rs.getString(2), rs.getInt(3)));
            }
        }
        return lista;
    }

    public List<TipoCombustible> tiposCombustible() throws SQLException {
        List<TipoCombustible> lista = new ArrayList<>();
        try (PreparedStatement ps = cn.prepareStatement(
                "SELECT id_tipo_combustible, nombre, factor_inflamabilidad FROM tipo_combustible ORDER BY nombre");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                lista.add(new TipoCombustible(rs.getInt(1), rs.getString(2), rs.getDouble(3)));
            }
        }
        return lista;
    }

    /**
     * Nivel de alerta vigente de la zona. No lo guardo en la zona: lo saco de la vista
     * v_nivel_alerta_vigente, que toma el último cambio registrado. Devuelvo Optional porque
     * una zona puede no tener ningún cambio de nivel todavía.
     */
    public Optional<NivelAlerta> nivelAlertaVigente(int idZona) throws SQLException {
        String sql = "SELECT v.id_nivel_alerta, v.nivel_alerta, n.orden FROM v_nivel_alerta_vigente v "
                + "JOIN nivel_alerta n ON n.id_nivel_alerta = v.id_nivel_alerta WHERE v.id_zona = ?";
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setInt(1, idZona);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new NivelAlerta(rs.getInt(1), rs.getString(2), rs.getInt(3))) : Optional.empty();
            }
        }
    }

    /**
     * Registra un cambio de nivel de alerta con su fundamento y el usuario que lo hizo (CU12).
     * Siempre inserto una fila nueva en vez de actualizar, así queda el historial completo (RFS17).
     * Devuelvo el id del cambio para poder asociarle la alerta de nivel elevado.
     */
    public int registrarCambioNivel(int idZona, int idNivelAlerta, String fundamento, int idUsuario) throws SQLException {
        String sql = "INSERT INTO cambio_nivel_alerta (id_zona, id_nivel_alerta, fundamento, id_usuario) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = cn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, idZona);
            ps.setInt(2, idNivelAlerta);
            ps.setString(3, fundamento.trim());
            ps.setInt(4, idUsuario);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
    // En la tabla parametro los valores son texto; acá los paso a número o uso el valor por defecto si faltan.
    private static double numero(Map<String, String> p, String clave, double porDefecto) {
        String valor = p.get(clave);
        return valor == null || valor.isBlank() ? porDefecto : Double.parseDouble(valor.trim());
    }
}
