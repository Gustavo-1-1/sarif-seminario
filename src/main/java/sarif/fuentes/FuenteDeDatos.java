package sarif.fuentes;

import sarif.modelo.FocoCalor;
import sarif.modelo.OrigenDatos;
import sarif.modelo.RegistroMeteo;
import sarif.modelo.Zona;

import java.time.LocalDate;
import java.util.List;

/**
 * Representa de dónde saco los datos externos (focos satelitales y meteorología).
 * La hice abstracta porque ServicioSincronizacion solo conoce este tipo: le da igual si los datos
 * vienen por HTTPS (FuenteRemota) o de un CSV local (FuenteArchivo). Así, pasar al modo sin conexión
 * del RNF06 es simplemente instanciar la otra especialización, sin tocar el servicio.
 * Acá también dejo el filtrado por fechas, que es común a las dos fuentes.
 */
public abstract class FuenteDeDatos {

    /**
     * Devuelve las detecciones del área de la zona para los últimos {@code dias} días, hasta la fecha
     * {@code hasta} inclusive. Si la fuente no responde o el formato es raro, lanzo FuenteNoDisponibleException.
     */
    public abstract List<FocoCalor> obtenerFocos(Zona zona, int dias, LocalDate hasta) throws FuenteNoDisponibleException;

    /** Devuelve las variables meteorológicas diarias de la zona alrededor de la fecha de referencia (el día anterior, ese día y el siguiente). */
    public abstract List<RegistroMeteo> obtenerMeteo(Zona zona, LocalDate fechaReferencia) throws FuenteNoDisponibleException;

    /** Origen que registro en la sincronización y en cada dato que guardo, para saber después de dónde salió. */
    public abstract OrigenDatos getOrigen();

    /**
     * Método común a las dos fuentes: descarto las detecciones que quedan fuera del rango de fechas pedido.
     * Por ejemplo, con dias = 3 y hasta = 20/01 me quedo con los focos del 18, 19 y 20 de enero.
     */
    protected List<FocoCalor> filtrarPorFechas(List<FocoCalor> focos, int dias, LocalDate hasta) {
        LocalDate desde = hasta.minusDays(dias - 1L);
        return focos.stream()
                .filter(f -> !f.fechaHoraUtc().toLocalDate().isBefore(desde)
                        && !f.fechaHoraUtc().toLocalDate().isAfter(hasta))
                .toList();
    }
}
