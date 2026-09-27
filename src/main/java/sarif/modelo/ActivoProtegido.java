package sarif.modelo;

/**
 * Representa una población, infraestructura o área que quiero proteger dentro de una zona (CU02).
 * Si un foco cae a menos de distanciaAlertaKm del activo, genero una alerta de "activo en riesgo" (CU15).
 * Lo hice record porque solo transporta datos entre la base y los servicios; no tiene comportamiento propio.
 */
public record ActivoProtegido(int id, int idZona, String nombre, String tipo, double latitud, double longitud,
                              double distanciaAlertaKm) {
}
