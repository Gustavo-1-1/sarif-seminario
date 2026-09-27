package sarif.modelo;

import java.time.LocalDateTime;

/**
 * Registro de cómo se atendió una alerta: a quién se avisó, por qué medio, quién lo hizo y cuándo.
 * Así queda la trazabilidad que pide la guardia ("¿quién avisó a Defensa Civil y a qué hora?").
 *
 * @param destinatario a quién se avisó (persona, cargo u organismo), hasta 120 caracteres
 * @param usuario      nombre completo de quien registró la notificación
 */
public record Notificacion(String destinatario, MedioNotificacion medio, String usuario, LocalDateTime fechaHora) {

    public static final int LARGO_MAXIMO_DESTINATARIO = 120;
}
