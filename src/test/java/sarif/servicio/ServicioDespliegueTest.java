package sarif.servicio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sarif.modelo.Asignacion;
import sarif.modelo.EstadoRecurso;
import sarif.modelo.IndiceRiesgo;
import sarif.modelo.NivelAlerta;
import sarif.modelo.Recurso;
import sarif.modelo.TipoRecurso;
import sarif.modelo.ZonaParaAsignar;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prueba unitaria de la sugerencia de despliegue preventivo (PU-15, CU10) y del ajuste manual hacia zonas sin
 * índice del día (PU-34), junto con la regla que decide cuándo un cambio de nivel genera una alerta.
 *
 * <p>Verifico las reglas con las que armo la sugerencia: las zonas se atienden de mayor a menor índice,
 * cada zona recibe primero su brigada de base, los medios aéreos van a las zonas en nivel EXTREMO, los
 * terrestres que sobran refuerzan esas mismas zonas y las zonas en nivel BAJO no reciben nada. Uso la
 * versión de sugerir que recibe las listas de índices y recursos, que no toca la base (la sugerencia es
 * solo una propuesta hasta que el jefe de guardia la confirma), así que la prueba corre sin MySQL.</p>
 */
class ServicioDespliegueTest {

    /** Atajo para armar el índice de una zona con su valor y su nivel; las componentes no importan acá. */
    private static IndiceRiesgo indice(int zona, String nombre, double valor, String nivel) {
        return new IndiceRiesgo(zona * 10, zona, nombre, LocalDate.of(2026, 1, 20), 0, 0, 0, valor, nivel);
    }

    /** Atajo para armar un recurso disponible, con su zona de base o null si no tiene. */
    private static Recurso recurso(int id, String nombre, TipoRecurso tipo, Integer base) {
        return new Recurso(id, nombre, tipo, 5, EstadoRecurso.DISPONIBLE, base);
    }

    @Test
    @DisplayName("PU-15 Sugerencia: orden por índice, base operativa, aéreos a EXTREMO, zona BAJO excluida, sin recursos")
    void sugerencia() {
        // Tres zonas, una por nivel relevante (ALTO, BAJO y EXTREMO), cargadas a propósito
        // desordenadas para comprobar que la sugerencia las ordena por índice.
        List<IndiceRiesgo> indices = List.of(
                indice(1, "Alta", 60, "ALTO"),
                indice(2, "Baja", 10, "BAJO"),
                indice(3, "Extrema", 80, "EXTREMO"));
        // Cuatro recursos: dos brigadas con base (en la zona alta y en la baja), una autobomba sin
        // base y un helicóptero.
        List<Recurso> recursos = List.of(
                recurso(1, "Brigada de la zona alta", TipoRecurso.TERRESTRE, 1),
                recurso(2, "Autobomba sin base", TipoRecurso.TERRESTRE, null),
                recurso(3, "Brigada de la zona baja", TipoRecurso.TERRESTRE, 2),
                recurso(4, "Helicóptero", TipoRecurso.AEREO, null));

        List<Asignacion> s = new ServicioDespliegue().sugerir(indices, recursos);

        // La zona de mayor índice aparece primero y la de nivel BAJO no recibe recursos.
        assertEquals("Extrema", s.get(0).nombreZona());
        assertTrue(s.stream().noneMatch(a -> a.idZona() == 2));
        // La zona alta recibe su brigada de base.
        assertTrue(s.stream().anyMatch(a -> a.idZona() == 1 && a.recurso().id() == 1));
        // La extrema, sin brigada propia, recibe primero el terrestre sin base, y el medio aéreo.
        assertTrue(s.stream().anyMatch(a -> a.idZona() == 3 && a.recurso().id() == 2));
        assertTrue(s.stream().anyMatch(a -> a.idZona() == 3 && a.recurso().id() == 4));
        // El terrestre restante refuerza la zona EXTREMO.
        assertTrue(s.stream().anyMatch(a -> a.idZona() == 3 && a.recurso().id() == 3));
        // Se usan los cuatro recursos, ni uno más ni uno menos.
        assertEquals(4, s.size());

        // Sin recursos disponibles la sugerencia sale vacía, sin errores.
        assertTrue(new ServicioDespliegue().sugerir(indices, List.of()).isEmpty());
    }

    @Test
    @DisplayName("PU-34 Ajuste manual: zonas sin índice del día en el combo y asignación sin índice; subir de nivel genera aviso")
    void ajusteSinIndice() {
        // El combo ofrece todas las zonas: primero las que tienen índice, de mayor a menor, y al final las demás.
        List<ZonaParaAsignar> zonas = ServicioDespliegue.ordenarZonas(List.of(
                new ZonaParaAsignar(7, "prueba", null, "EMERGENCIA"),
                new ZonaParaAsignar(1, "Alta", indice(1, "Alta", 60, "ALTO"), "NORMAL"),
                new ZonaParaAsignar(3, "Extrema", indice(3, "Extrema", 80, "EXTREMO"), "ALERTA")));
        assertEquals(List.of("Extrema", "Alta", "prueba"), zonas.stream().map(ZonaParaAsignar::nombreZona).toList());
        // A la zona sin índice se le puede mandar un recurso: la asignación queda sin índice y el motivo lo aclara.
        Asignacion a = ServicioDespliegue.ajustar(recurso(1, "Brigada", TipoRecurso.TERRESTRE, null), zonas.get(2), "Asignación manual");
        assertEquals(7, a.idZona());
        assertNull(a.idIndice());
        assertNull(a.indice());
        assertEquals("Asignación manual (sin índice del día, alerta EMERGENCIA)", a.motivo());
        // Con índice, la asignación lo lleva como fundamento.
        assertEquals(30, ServicioDespliegue.ajustar(recurso(2, "Autobomba", TipoRecurso.TERRESTRE, null), zonas.get(0), "x").idIndice());

        // Solo subir el nivel de alerta genera la alerta de nivel elevado; bajarlo no.
        NivelAlerta normal = new NivelAlerta(1, "NORMAL", 1);
        NivelAlerta emergencia = new NivelAlerta(4, "EMERGENCIA", 4);
        assertTrue(ServicioZonas.subeDeNivel(normal, emergencia));
        assertFalse(ServicioZonas.subeDeNivel(emergencia, normal));
        assertFalse(ServicioZonas.subeDeNivel(null, normal));
        assertEquals("prueba: el nivel de alerta subió de NORMAL a EMERGENCIA. Fundamento: fuego",
                ServicioZonas.descripcionNivelElevado("prueba", "NORMAL", "EMERGENCIA", "fuego"));
    }
}
