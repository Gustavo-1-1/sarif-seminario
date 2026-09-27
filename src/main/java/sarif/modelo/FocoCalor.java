package sarif.modelo;

import java.time.LocalDateTime;

/**
 * Detección de un foco de calor satelital (CU07). Como FIRMS no informa un identificador propio,
 * tomo como identidad natural la combinación (latitud, longitud, fecha y hora UTC, satélite);
 * con eso evito guardar dos veces la misma detección si sincronizo más de una vez.
 * El id es Long y puede ser null porque lo asigna la base recién al insertar.
 * potenciaFrpMw es Double porque algunos registros no traen la potencia radiativa.
 */
public record FocoCalor(Long id, int idZona, double latitud, double longitud, LocalDateTime fechaHoraUtc,
                        String satelite, String instrumento, Double potenciaFrpMw, Confianza confianza) {

    /**
     * Devuelve una copia del foco asignada a la zona indicada. Como el record es inmutable,
     * cuando descubro en qué zona cayó el foco creo uno nuevo en lugar de modificarlo.
     */
    public FocoCalor conZona(int idZona) {
        return new FocoCalor(id, idZona, latitud, longitud, fechaHoraUtc, satelite, instrumento, potenciaFrpMw, confianza);
    }
}
