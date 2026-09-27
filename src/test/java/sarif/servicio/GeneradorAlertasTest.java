package sarif.servicio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sarif.modelo.ActivoProtegido;
import sarif.modelo.Alerta;
import sarif.modelo.Confianza;
import sarif.modelo.FocoCalor;
import sarif.modelo.IndiceRiesgo;
import sarif.modelo.NivelAlerta;
import sarif.modelo.TipoAlerta;
import sarif.modelo.Zona;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pruebas unitarias de la generación de alertas: a partir de un foco (PU-14, CU07) y a partir del índice
 * de riesgo cruzado con los focos y el nivel de alerta vigente (PU-26).
 *
 * <p>Verifico las reglas del generador: un foco dentro de una zona vigilada produce una alerta de foco
 * en zona y, además, una alerta de activo en riesgo por cada activo que quede dentro de su distancia de
 * alerta; los activos más lejanos no generan nada, y una zona con la vigilancia desactivada no genera
 * ninguna alerta. GeneradorAlertas recibe el foco, la zona y los activos ya armados y devuelve las alertas
 * sin guardarlas, así que la prueba no necesita la base de datos.</p>
 */
class GeneradorAlertasTest {

    @Test
    @DisplayName("PU-14 Alertas de foco en zona y de activo en riesgo; el activo a más de 5 km no genera alerta")
    void alertas() {
        // Reproduzco el caso de la jornada: el foco de alta confianza de Caviahue y dos activos con
        // distancia de alerta de 5 km, uno a 2,72 km del foco y otro a unos 6 km.
        Zona caviahue = new Zona(4, "Caviahue - Copahue", null, -37.95, -37.75, -71.20, -70.95, true);
        FocoCalor foco = new FocoCalor(10L, 4, -37.8455, -71.055, LocalDateTime.of(2026, 1, 20, 4, 36), "N", "VIIRS",
                18.7, Confianza.ALTA);
        ActivoProtegido cerca = new ActivoProtegido(7, 4, "Villa Caviahue", "POBLACION", -37.87, -71.055, 5);
        ActivoProtegido lejos = new ActivoProtegido(9, 4, "Puesto lejano", "PRODUCTIVO", -37.90, -71.055, 5); // unos 6 km

        List<Alerta> alertas = new GeneradorAlertas().evaluar(foco, caviahue, List.of(cerca, lejos));

        // Tienen que salir exactamente dos alertas: primero la de foco en zona y después la de activo
        // en riesgo de Villa Caviahue, con su distancia redondeada a 2,72 km. El puesto lejano no aparece.
        assertEquals(2, alertas.size());
        assertEquals(TipoAlerta.FOCO_EN_ZONA, alertas.get(0).tipo());
        assertEquals(TipoAlerta.ACTIVO_EN_RIESGO, alertas.get(1).tipo());
        assertEquals(7, alertas.get(1).idActivo());
        assertEquals(2.72, alertas.get(1).distanciaKm(), 1e-9);

        // Si desactivo la vigilancia de la zona, el mismo foco ya no genera ninguna alerta.
        caviahue.setVigilanciaActiva(false);
        assertEquals(0, new GeneradorAlertas().evaluar(foco, caviahue, List.of(cerca)).size());
    }

    private static final List<NivelAlerta> NIVELES = List.of(new NivelAlerta(1, "NORMAL", 1),
            new NivelAlerta(2, "ATENCION", 2), new NivelAlerta(3, "ALERTA", 3), new NivelAlerta(4, "EMERGENCIA", 4));

    private static IndiceRiesgo indice(int id, double valor, String nivel) {
        return new IndiceRiesgo(id, 4, "Caviahue - Copahue", LocalDate.of(2026, 1, 20), 0.5, 0.8, 0.7, valor, nivel);
    }

    private static FocoCalor foco(long id, int zona, Double frp, Confianza confianza) {
        return new FocoCalor(id, zona, -37.85, -71.05, LocalDateTime.of(2026, 1, 20, 4, 36), "N", "VIIRS", frp, confianza);
    }

    @Test
    @DisplayName("PU-26 Alertas de riesgo: índice que sube, focos confiables con riesgo alto y nivel sugerido")
    void alertasDeRiesgo() {
        GeneradorAlertas generador = new GeneradorAlertas();
        Zona caviahue = new Zona(4, "Caviahue - Copahue", null, -37.95, -37.75, -71.20, -70.95, true);
        NivelAlerta normal = NIVELES.get(0);

        // 1) Pasa de MODERADO a ALTO sin focos: una sola alerta de riesgo elevado que sugiere ATENCION.
        List<Alerta> a = generador.evaluarRiesgo(caviahue, indice(20, 62.0, "ALTO"), indice(19, 40.0, "MODERADO"),
                List.of(), normal, NIVELES);
        assertEquals(1, a.size());
        assertEquals(TipoAlerta.RIESGO_ELEVADO, a.get(0).tipo());
        assertEquals("ATENCION", a.get(0).nivelSugerido().nombre());
        assertEquals(20, a.get(0).idIndice());
        assertNull(a.get(0).idFoco());

        // 2) Pasa de ALTO a EXTREMO con focos: el de confianza baja y el de otra zona no cuentan. La alerta
        //    combinada queda atada al foco más intenso de los confiables (el 12, de 20 MW) y sugiere EMERGENCIA.
        List<FocoCalor> focos = List.of(foco(10, 4, 30.0, Confianza.BAJA), foco(11, 4, 5.0, Confianza.NOMINAL),
                foco(12, 4, 20.0, Confianza.ALTA), foco(13, 1, 50.0, Confianza.ALTA));
        a = generador.evaluarRiesgo(caviahue, indice(21, 79.13, "EXTREMO"), indice(20, 62.0, "ALTO"), focos, normal, NIVELES);
        assertEquals(2, a.size());
        assertEquals("ALERTA", a.get(0).nivelSugerido().nombre());
        assertEquals(TipoAlerta.FOCO_CON_RIESGO_ALTO, a.get(1).tipo());
        assertEquals(12L, a.get(1).idFoco());
        assertEquals("EMERGENCIA", a.get(1).nivelSugerido().nombre());
        assertTrue(a.get(1).descripcion().startsWith("2 foco(s) confiable(s)"), a.get(1).descripcion());

        // 3) Sigue en EXTREMO (no subió) y la guardia ya está en EMERGENCIA: solo sale la alerta de focos,
        //    y sin sugerencia, porque el nivel vigente ya alcanza.
        a = generador.evaluarRiesgo(caviahue, indice(22, 80.0, "EXTREMO"), indice(21, 79.13, "EXTREMO"), focos,
                NIVELES.get(3), NIVELES);
        assertEquals(1, a.size());
        assertEquals(TipoAlerta.FOCO_CON_RIESGO_ALTO, a.get(0).tipo());
        assertNull(a.get(0).nivelSugerido());

        // 4) Con foco confiable e índice ALTO la sugerencia es ALERTA (EMERGENCIA queda para EXTREMO).
        a = generador.evaluarRiesgo(caviahue, indice(23, 60.0, "ALTO"), indice(22, 60.0, "ALTO"),
                List.of(foco(14, 4, 8.0, Confianza.NOMINAL)), normal, NIVELES);
        assertEquals(1, a.size());
        assertEquals("ALERTA", a.get(0).nivelSugerido().nombre());

        // 5) Riesgo MODERADO, o zona sin vigilancia: no hay alertas de riesgo aunque haya focos.
        assertTrue(generador.evaluarRiesgo(caviahue, indice(24, 45.0, "MODERADO"), null, focos, normal, NIVELES).isEmpty());
        caviahue.setVigilanciaActiva(false);
        assertTrue(generador.evaluarRiesgo(caviahue, indice(25, 90.0, "EXTREMO"), null, focos, normal, NIVELES).isEmpty());
    }
}
