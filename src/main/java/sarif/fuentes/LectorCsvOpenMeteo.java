package sarif.fuentes;

import sarif.modelo.RegistroMeteo;
import sarif.modelo.Zona;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Lee el CSV diario de Open-Meteo (format=csv) y lo convierte en objetos RegistroMeteo.
 * El archivo tiene un bloque de metadatos con la ubicación, una línea en blanco y el bloque de datos.
 * Si trae varias ubicaciones (columna location_id), me quedo con la que cae dentro de la zona.
 * Igual que LectorCsvFirms, cualquier formato inesperado lo informo como FuenteNoDisponibleException.
 */
public final class LectorCsvOpenMeteo {

    private LectorCsvOpenMeteo() {
    }

    /**
     * Devuelvo los registros de la zona. Las fechas posteriores a la de referencia las marco como
     * pronóstico; las demás, como dato observado.
     */
    public static List<RegistroMeteo> leer(String csv, Zona zona, LocalDate fechaReferencia) throws FuenteNoDisponibleException {
        // 1) Normalizo los saltos de línea a \n. Lo agregué porque los archivos guardados en Windows
        //    (\r\n) rompían la separación en bloques por la línea en blanco.
        String texto = csv == null ? "" : csv.replace("\r\n", "\n").replace('\r', '\n').strip();
        // 2) Separo el bloque de metadatos del bloque de datos usando la línea en blanco.
        String[] bloques = texto.isEmpty() ? new String[0] : texto.split("\n\\s*\n");
        if (bloques.length < 2) {
            throw new FuenteNoDisponibleException("Formato inesperado de la respuesta meteorológica: faltan los bloques de "
                    + "metadatos y de datos.");
        }
        // 3) Del bloque de metadatos saco qué ubicación corresponde a la zona.
        String ubicacion = buscarUbicacion(bloques[0].split("\\R"), zona);
        // 4) Del bloque de datos ubico las columnas que necesito.
        String[] lineas = bloques[1].split("\\R");
        List<String> encabezado = Arrays.asList(lineas[0].trim().split(","));
        int iUbicacion = encabezado.indexOf("location_id");
        int iFecha = encabezado.indexOf("time");
        int iTemperatura = columna(encabezado, "temperature_2m_max");
        int iHumedad = columna(encabezado, "relative_humidity_2m_min");
        int iViento = columna(encabezado, "wind_speed_10m_max");
        int iPrecipitacion = columna(encabezado, "precipitation_sum");
        // La humedad del suelo es opcional (RC06): los archivos de la primera versión no la tienen.
        int iSuelo = columnaOpcional(encabezado, "soil_moisture_0_to_10cm_mean");
        // El vector del viento y el pronóstico extendido también son opcionales, por el mismo motivo.
        int iTemperaturaMin = columnaOpcional(encabezado, "temperature_2m_min");
        int iRafaga = columnaOpcional(encabezado, "wind_gusts_10m_max");
        int iDireccion = columnaOpcional(encabezado, "wind_direction_10m_dominant");
        int iProbLluvia = columnaOpcional(encabezado, "precipitation_probability_max");
        int iEvapotranspiracion = columnaOpcional(encabezado, "et0_fao_evapotranspiration");
        if (iFecha < 0) {
            throw new FuenteNoDisponibleException("Formato inesperado de la respuesta meteorológica: falta la columna time.");
        }

        // Si ninguna ubicación del archivo cae en la zona, no es un error: simplemente no hay datos para ella.
        List<RegistroMeteo> registros = new ArrayList<>();
        if (ubicacion == null) {
            return registros;
        }
        // 5) Recorro las filas de datos.
        for (int n = 1; n < lineas.length; n++) {
            String[] c = lineas[n].split(",", -1);
            // Salteo las filas de otras ubicaciones.
            if (iUbicacion >= 0 && !c[iUbicacion].trim().equals(ubicacion)) {
                continue;
            }
            // Sin temperatura, humedad o viento no puedo calcular la componente M, así que descarto la fila.
            if (c[iTemperatura].isBlank() || c[iHumedad].isBlank() || c[iViento].isBlank()) {
                continue;
            }
            try {
                LocalDate fecha = LocalDate.parse(c[iFecha].trim());
                // La precipitación vacía la tomo como 0 mm, porque no afecta tanto al cálculo.
                double precipitacion = c[iPrecipitacion].isBlank() ? 0 : Double.parseDouble(c[iPrecipitacion].trim());
                // La humedad del suelo vacía la dejo en null: no es lo mismo "sin dato" que "suelo seco".
                Double direccion = opcional(c, iDireccion);
                registros.add(new RegistroMeteo(zona.getId(), fecha, Double.parseDouble(c[iTemperatura].trim()),
                        Double.parseDouble(c[iHumedad].trim()), Double.parseDouble(c[iViento].trim()), precipitacion,
                        fecha.isAfter(fechaReferencia), opcional(c, iSuelo), opcional(c, iTemperaturaMin),
                        opcional(c, iRafaga), direccion == null ? null : (int) Math.round(direccion) % 360,
                        opcional(c, iProbLluvia), opcional(c, iEvapotranspiracion)));
            } catch (RuntimeException e) {
                throw new FuenteNoDisponibleException("Formato inesperado en la respuesta meteorológica: " + lineas[n], e);
            }
        }
        return registros;
    }

    /**
     * Devuelvo el location_id de la ubicación que cae dentro de la zona, "" si el archivo tiene una sola
     * ubicación (no hay columna location_id), o null si ninguna corresponde a la zona.
     */
    private static String buscarUbicacion(String[] metadatos, Zona zona) throws FuenteNoDisponibleException {
        List<String> encabezado = Arrays.asList(metadatos[0].trim().split(","));
        int iUbicacion = encabezado.indexOf("location_id");
        int iLat = encabezado.indexOf("latitude");
        int iLon = encabezado.indexOf("longitude");
        if (iLat < 0 || iLon < 0) {
            throw new FuenteNoDisponibleException("Formato inesperado de la respuesta meteorológica: faltan las coordenadas.");
        }
        if (iUbicacion < 0) {
            return "";
        }
        // Me quedo con la primera ubicación cuyas coordenadas caen dentro del rectángulo de la zona.
        for (int n = 1; n < metadatos.length; n++) {
            String[] c = metadatos[n].split(",", -1);
            try {
                if (zona.dentroDelRectangulo(Double.parseDouble(c[iLat].trim()), Double.parseDouble(c[iLon].trim()))) {
                    return c[iUbicacion].trim();
                }
            } catch (RuntimeException e) {
                throw new FuenteNoDisponibleException("Formato inesperado en las ubicaciones meteorológicas: " + metadatos[n], e);
            }
        }
        return null;
    }

    /** Ubico una columna por el comienzo del nombre, porque Open-Meteo le agrega la unidad: "temperature_2m_max (°C)". */
    private static int columna(List<String> encabezado, String prefijo) throws FuenteNoDisponibleException {
        int i = columnaOpcional(encabezado, prefijo);
        if (i < 0) {
            throw new FuenteNoDisponibleException("Formato inesperado de la respuesta meteorológica: falta la columna " + prefijo + ".");
        }
        return i;
    }

    /**
     * Valor de una columna opcional: null si la columna no está o viene vacía. Algunas APIs de Open-Meteo
     * mandan "NaN" cuando no tienen la variable: lo trato igual que vacío.
     */
    private static Double opcional(String[] c, int i) {
        if (i < 0 || i >= c.length || c[i].isBlank() || c[i].trim().equals("NaN")) {
            return null;
        }
        return Double.valueOf(c[i].trim());
    }

    /** Igual que columna, pero si no está devuelvo -1 en lugar de fallar. */
    private static int columnaOpcional(List<String> encabezado, String prefijo) {
        for (int i = 0; i < encabezado.size(); i++) {
            if (encabezado.get(i).trim().startsWith(prefijo)) {
                return i;
            }
        }
        return -1;
    }
}
