package sarif.modelo;

import java.time.LocalDate;

/**
 * Índice meteorológico de peligro de incendios (FWI, Fire Weather Index) de una zona para un día,
 * tal como lo publica el sistema global de información de incendios de Copernicus (GWIS) a partir del
 * modelo indicado (ECMWF en esta versión).
 * <p>
 * Es el índice canadiense de Van Wagner y Pickett (1985), que SARIF no calcula: lo trae ya resuelto del
 * servicio. Se guarda como dato informativo, al lado del NDVI, y no interviene en el índice de riesgo
 * propio del sistema, que sigue combinando las componentes histórica, meteorológica y de combustible.
 * La razón es que el FWI arrastra el estado de los días anteriores (los códigos de humedad del combustible
 * fino, del mantillo y de la capa profunda) y necesita observaciones al mediodía solar, datos que la base
 * no guarda: reproducirlo con los máximos y mínimos diarios daría otro número, no el FWI.
 */
public record RegistroFwi(int idZona, LocalDate fecha, double valor, String modelo) {

    /** Escala de peligro de EFFIS y GWIS, la misma con la que el servicio publica sus mapas. */
    public static String clase(double valor) {
        if (valor < 5.2) {
            return "MUY BAJO";
        }
        if (valor < 11.2) {
            return "BAJO";
        }
        if (valor < 21.3) {
            return "MODERADO";
        }
        if (valor < 38.0) {
            return "ALTO";
        }
        if (valor < 50.0) {
            return "MUY ALTO";
        }
        if (valor <= 70.0) {
            return "EXTREMO";
        }
        return "MUY EXTREMO";
    }

    public String clase() {
        return clase(valor);
    }

    public RegistroFwi conZona(int idZona) {
        return new RegistroFwi(idZona, fecha, valor, modelo);
    }
}
