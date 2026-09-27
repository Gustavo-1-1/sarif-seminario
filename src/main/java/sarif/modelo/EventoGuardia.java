package sarif.modelo;

import java.time.LocalDateTime;

/**
 * Un hito de la cronología de la guardia: qué pasó, cuándo, en qué zona, a nombre de quién y con qué
 * nivel de alerta estaba la zona en ese momento. No es una tabla: lo arma ReporteDAO juntando alertas,
 * cambios de nivel y asignaciones.
 *
 * @param nivelAlerta         nivel de alerta vigente de la zona en ese momento (o el nuevo, si el hito es el cambio)
 * @param usuario             quién lo registró; "Sistema" para las alertas que se generan solas
 * @param idAlerta            alerta a la que pertenece el hito, o null si no es de una alerta
 * @param minutosDesdeAlerta  para la notificación y el cierre o descarte, minutos desde que se generó la alerta
 */
public record EventoGuardia(LocalDateTime fechaHora, String zona, TipoEvento tipo, String detalle, String nivelAlerta,
                            String usuario, Integer idAlerta, Long minutosDesdeAlerta) {
}
