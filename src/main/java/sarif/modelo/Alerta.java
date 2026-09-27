package sarif.modelo;

import java.time.LocalDateTime;

/**
 * Alerta que genera el sistema: por un foco dentro de una zona vigilada (CU14) o cerca de un activo
 * protegido (CU15), o por el índice de riesgo del día (RIESGO_ELEVADO y FOCO_CON_RIESGO_ALTO).
 * Decidí no guardar la zona en la alerta porque ya la tengo en el foco o en el índice: si la repitiera,
 * podrían quedar inconsistentes. Los campos idZona, nombreZona, nombreActivo y confianza no están en la
 * tabla alerta; los completo solo al listar (con los JOIN del DAO) para mostrarlos sin consultas extra.
 * El nivel sugerido solo lo tienen las alertas de riesgo, y puede faltar si el nivel vigente ya alcanza.
 * La notificación es null mientras la alerta está PENDIENTE (o si se descartó sin avisar a nadie).
 */
public record Alerta(Integer id, Long idFoco, Integer idActivo, Integer idIndice, TipoAlerta tipo,
                     LocalDateTime fechaHora, Double distanciaKm, String descripcion, EstadoAlerta estado,
                     NivelAlerta nivelSugerido, Integer idZona, String nombreZona, String nombreActivo,
                     Confianza confianza, Notificacion notificacion) {

    /**
     * Crea una alerta de foco nueva, todavía no registrada: por eso el id y la fecha van en null (los pone
     * la base) y el estado arranca en PENDIENTE, que es el estado inicial del ciclo de vida.
     */
    public static Alerta nueva(long idFoco, Integer idActivo, TipoAlerta tipo, Double distanciaKm, String descripcion) {
        return new Alerta(null, idFoco, idActivo, null, tipo, null, distanciaKm, descripcion, EstadoAlerta.PENDIENTE,
                null, null, null, null, null, null);
    }

    /** Crea una alerta de riesgo nueva, atada al índice del día y, si hay focos, al más intenso. */
    public static Alerta deRiesgo(TipoAlerta tipo, int idIndice, int idZona, Long idFoco, String descripcion,
                                  NivelAlerta nivelSugerido) {
        return new Alerta(null, idFoco, null, idIndice, tipo, null, null, descripcion, EstadoAlerta.PENDIENTE,
                nivelSugerido, idZona, null, null, null, null);
    }
}
