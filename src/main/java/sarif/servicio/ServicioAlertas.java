package sarif.servicio;

import sarif.datos.AlertaDAO;
import sarif.datos.ConexionBD;
import sarif.datos.ConfiguracionDAO;
import sarif.datos.FocoDAO;
import sarif.datos.IndiceDAO;
import sarif.datos.ZonaDAO;
import sarif.modelo.Alerta;
import sarif.modelo.EstadoAlerta;
import sarif.modelo.FocoCalor;
import sarif.modelo.IndiceRiesgo;
import sarif.modelo.MedioNotificacion;
import sarif.modelo.NivelAlerta;
import sarif.modelo.Notificacion;
import sarif.modelo.Permiso;
import sarif.modelo.Zona;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Servicio de alertas: consulto las alertas, registro a quién se avisó de cada una, les cambio el
 * estado, genero las alertas de riesgo que cruzan los datos del día y aplico el nivel que sugieren.
 * Las transiciones permitidas salen del diagrama de estados de la alerta, que modelé dentro
 * del enum EstadoAlerta; acá solo controlo que se respeten antes de ir a la base.
 */
public class ServicioAlertas {

    private final GeneradorAlertas generador = new GeneradorAlertas();

    /** Devuelvo todas las alertas registradas para mostrarlas en la pantalla de alertas. */
    public List<Alerta> listar() throws SQLException {
        try (Connection cn = ConexionBD.obtener()) {
            return new AlertaDAO(cn).listar();
        }
    }

    /**
     * Cambio el estado de la alerta a CERRADA o DESCARTADA a nombre del usuario, pero primero verifico que
     * la transición sea válida según el diagrama de estados. Para pasarla a NOTIFICADA hay que usar
     * notificar(), porque ese cambio exige dejar registrado a quién se avisó.
     */
    public void cambiarEstado(Alerta alerta, EstadoAlerta nuevo, int idUsuario) throws SQLException, ValidacionException {
        if (nuevo == EstadoAlerta.NOTIFICADA) {
            throw new ValidacionException("Para notificar una alerta indique a quién se avisó y por qué medio.");
        }
        if (!alerta.estado().puedePasarA(nuevo)) {
            throw new ValidacionException("Una alerta " + alerta.estado() + " no puede pasar a " + nuevo + ".");
        }
        Permisos.exigir(idUsuario, Permiso.OPERAR);
        try (Connection cn = ConexionBD.obtener()) {
            if (!new AlertaDAO(cn).resolver(alerta.id(), alerta.estado(), nuevo, idUsuario)) {
                throw new ValidacionException("La alerta #" + alerta.id() + " cambió mientras tanto (otro usuario la atendió). Actualice la lista.");
            }
        }
    }

    /**
     * Registro que la guardia avisó de la alerta: a quién, por qué medio y a nombre de quién. SARIF no manda
     * el aviso (lo da una persona por radio, teléfono, etc.); lo que hago es dejar la constancia y pasar la
     * alerta a NOTIFICADA. El medio SISTEMA no se puede elegir: lo pongo yo al aplicar el nivel sugerido.
     */
    public void notificar(Alerta alerta, String destinatario, MedioNotificacion medio, int idUsuario)
            throws SQLException, ValidacionException {
        List<String> errores = validarNotificacion(destinatario, medio);
        if (medio == MedioNotificacion.SISTEMA) {
            errores.add("El medio \"" + medio.texto() + "\" lo registra el sistema al aplicar el nivel sugerido.");
        }
        if (!errores.isEmpty()) {
            throw new ValidacionException(errores);
        }
        registrarNotificacion(alerta, destinatario.trim(), medio, idUsuario);
    }

    /** Reglas de los datos de la notificación; son estáticas para probarlas con JUnit sin base. */
    static List<String> validarNotificacion(String destinatario, MedioNotificacion medio) {
        List<String> errores = new ArrayList<>();
        if (destinatario == null || destinatario.isBlank()) {
            errores.add("Indique a quién se avisó (persona, cargo u organismo).");
        } else if (destinatario.trim().length() > Notificacion.LARGO_MAXIMO_DESTINATARIO) {
            errores.add("A quién se avisó admite hasta " + Notificacion.LARGO_MAXIMO_DESTINATARIO + " caracteres.");
        }
        if (medio == null) {
            errores.add("Elija el medio por el que se avisó.");
        }
        return errores;
    }

    // Código común de notificar() y aplicarSugerencia(): controlo el estado, el usuario y escribo.
    private void registrarNotificacion(Alerta alerta, String destinatario, MedioNotificacion medio, int idUsuario)
            throws SQLException, ValidacionException {
        if (!alerta.estado().puedePasarA(EstadoAlerta.NOTIFICADA)) {
            throw new ValidacionException("Una alerta " + alerta.estado() + " no puede pasar a NOTIFICADA.");
        }
        Permisos.exigir(idUsuario, Permiso.OPERAR);
        try (Connection cn = ConexionBD.obtener()) {
            if (!new AlertaDAO(cn).notificar(alerta.id(), idUsuario, destinatario, medio)) {
                throw new ValidacionException("La alerta #" + alerta.id() + " ya no está pendiente: otro usuario la atendió. Actualice la lista.");
            }
        }
    }

    /**
     * Genero las alertas de riesgo de la fecha para todas las zonas activas que tengan índice calculado.
     * Recibo la conexión del servicio que me llama (cálculo del índice o sincronización de focos) para
     * quedar dentro de su transacción. Es seguro llamarlo varias veces: una alerta que ya existe no se repite.
     *
     * @return cantidad de alertas nuevas
     */
    public int generarAlertasDeRiesgo(Connection cn, LocalDate fecha) throws SQLException {
        IndiceDAO indices = new IndiceDAO(cn);
        AlertaDAO alertas = new AlertaDAO(cn);
        ConfiguracionDAO conf = new ConfiguracionDAO(cn);
        List<NivelAlerta> niveles = conf.nivelesAlerta();
        // Focos recientes: los del día y los del día anterior, como la ventana por defecto de la sincronización.
        List<FocoCalor> recientes = new FocoDAO(cn).listarEntre(fecha.minusDays(1), fecha);
        int nuevas = 0;
        for (Zona zona : new ZonaDAO(cn).listarActivas()) {
            Optional<IndiceRiesgo> hoy = indices.obtener(zona.getId(), fecha);
            if (hoy.isEmpty()) {
                continue;
            }
            IndiceRiesgo ayer = indices.obtener(zona.getId(), fecha.minusDays(1)).orElse(null);
            NivelAlerta vigente = conf.nivelAlertaVigente(zona.getId()).orElse(null);
            for (Alerta a : generador.evaluarRiesgo(zona, hoy.get(), ayer, recientes, vigente, niveles)) {
                if (!alertas.existeDeRiesgo(a)) {
                    alertas.insertar(a);
                    nuevas++;
                }
            }
        }
        return nuevas;
    }

    /** Cantidad de alertas pendientes, para el contador de la pestaña Alertas. */
    public int contarPendientes() throws SQLException {
        try (Connection cn = ConexionBD.obtener()) {
            return new AlertaDAO(cn).contarPendientes();
        }
    }

    /** Id de la última alerta registrada; la ventana principal lo guarda para detectar las nuevas. */
    public int ultimoId() throws SQLException {
        try (Connection cn = ConexionBD.obtener()) {
            return new AlertaDAO(cn).ultimoId();
        }
    }

    /** Alertas registradas después de la indicada, para el aviso emergente. */
    public List<Alerta> listarPosteriores(int idAlerta) throws SQLException {
        try (Connection cn = ConexionBD.obtener()) {
            return new AlertaDAO(cn).listarPosteriores(idAlerta);
        }
    }

    /**
     * Aplico el nivel de alerta que sugiere una alerta de riesgo. El cambio lo hace el servicio de zonas
     * (CU12), que valida el fundamento y que el nivel no sea el mismo que el vigente; así el sistema propone
     * pero la decisión queda registrada a nombre de la persona. Si la alerta estaba PENDIENTE, la paso a
     * NOTIFICADA, porque aplicar el nivel implica que la guardia ya la atendió; como destinatario queda la
     * guardia y como medio SISTEMA, porque el nivel nuevo se publica en SARIF.
     */
    public void aplicarSugerencia(Alerta alerta, String fundamento, int idUsuario) throws SQLException, ValidacionException {
        if (alerta.nivelSugerido() == null) {
            throw new ValidacionException("La alerta seleccionada no sugiere ningún cambio de nivel.");
        }
        if (alerta.estado() == EstadoAlerta.CERRADA || alerta.estado() == EstadoAlerta.DESCARTADA) {
            throw new ValidacionException("La alerta ya está " + alerta.estado() + ".");
        }
        new ServicioZonas().cambiarNivelAlerta(alerta.idZona(), alerta.nivelSugerido(), fundamento, idUsuario, false);
        if (alerta.estado() == EstadoAlerta.PENDIENTE) {
            String destinatario = "Guardia: " + alerta.nombreZona() + " pasó a " + alerta.nivelSugerido().nombre();
            registrarNotificacion(alerta,
                    destinatario.substring(0, Math.min(Notificacion.LARGO_MAXIMO_DESTINATARIO, destinatario.length())),
                    MedioNotificacion.SISTEMA, idUsuario);
        }
    }
}
