package sarif.modelo;

/**
 * Recurso de combate (brigada, autobomba o medio aéreo) que puedo sugerir para un despliegue (CU10).
 * idZonaBase es Integer porque el recurso puede no tener una zona de base operativa asignada.
 */
public record Recurso(int id, String denominacion, TipoRecurso tipo, int dotacion, EstadoRecurso estado,
                      Integer idZonaBase) {
}
