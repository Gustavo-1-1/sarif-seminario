package sarif.servicio;

import sarif.modelo.ActivoProtegido;
import sarif.modelo.Alerta;
import sarif.modelo.Confianza;
import sarif.modelo.FocoCalor;
import sarif.modelo.IndiceRiesgo;
import sarif.modelo.NivelAlerta;
import sarif.modelo.TipoAlerta;
import sarif.modelo.Zona;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Implementa el CU14 Alerta de foco en zona vigilada y el CU15 Alerta de activo en riesgo, que son
 * extensiones del CU07: evalúo cada foco nuevo que entra en la sincronización. Además genero las alertas
 * de riesgo, que salen de cruzar el índice del día con los focos y el nivel de alerta vigente, para que
 * el sistema avise solo cuando la situación de una zona empeora.
 * No accedo a la base (guardarlas es tarea de los servicios), así que esta clase se prueba con JUnit sin MySQL.
 */
public class GeneradorAlertas {

    /** Devuelvo las alertas que genera un foco: ninguna si la zona no está vigilada o el foco cae afuera. */
    public List<Alerta> evaluar(FocoCalor foco, Zona zona, List<ActivoProtegido> activos) {
        List<Alerta> alertas = new ArrayList<>();
        if (!zona.isVigilanciaActiva() || !zona.contiene(foco.latitud(), foco.longitud())) {
            return alertas;
        }
        // CU14: el foco está dentro de una zona vigilada, así que siempre genero esta alerta. Si la detección
        // es de confianza baja no la descarto (podría ser un foco real que recién empieza), pero lo dejo
        // escrito para que la guardia lo verifique antes de mover recursos.
        String verificar = foco.confianza() == Confianza.BAJA ? ". Confianza baja: verificar antes de actuar" : "";
        alertas.add(Alerta.nueva(foco.id(), null, TipoAlerta.FOCO_EN_ZONA, null,
                String.format(Locale.ROOT, "Foco de calor en %s (confianza %s, satélite %s)%s",
                        zona.getNombre(), foco.confianza(), foco.satelite(), verificar)));

        // CU15: reviso cada activo protegido de la zona y genero una alerta si el foco quedó dentro de su distancia de alerta.
        for (ActivoProtegido activo : activos) {
            double distancia = Geo.distanciaKm(foco.latitud(), foco.longitud(), activo.latitud(), activo.longitud());
            // Comparo con la distancia ya redondeada a dos decimales, que es la misma que muestro en el mensaje,
            // para que no aparezca "a 5,00 km" y aun así no salte la alerta de un umbral de 5 km.
            double redondeada = Math.round(distancia * 100.0) / 100.0;
            if (redondeada <= activo.distanciaAlertaKm()) {
                alertas.add(Alerta.nueva(foco.id(), activo.id(), TipoAlerta.ACTIVO_EN_RIESGO, redondeada,
                        String.format("Foco a %.2f km de %s%s", redondeada, activo.nombre(), verificar)));
            }
        }
        return alertas;
    }

    /** Orden de los niveles de riesgo, de menor a mayor, para saber si el índice subió. */
    private static final List<String> NIVELES_RIESGO = List.of("BAJO", "MODERADO", "ALTO", "EXTREMO");

    /**
     * Alertas de riesgo: cruzo el índice del día con el del día anterior, los focos recientes y el nivel de
     * alerta vigente de la zona. Genero como mucho dos alertas por zona:
     * <ul>
     *   <li>RIESGO_ELEVADO, si el índice está en ALTO o EXTREMO y subió respecto de ayer (o ayer no había
     *       índice). Sugiero ATENCION para ALTO y ALERTA para EXTREMO.</li>
     *   <li>FOCO_CON_RIESGO_ALTO, si además hay focos de confianza nominal o alta en la zona: es la situación
     *       más peligrosa, porque un foco en condiciones de riesgo alto se propaga rápido. Sugiero ALERTA con
     *       índice ALTO y EMERGENCIA con EXTREMO, y la ato al foco más intenso (mayor FRP).</li>
     * </ul>
     * La sugerencia solo va si es más alta que el nivel vigente; si la guardia ya subió el nivel, la alerta
     * sale igual pero sin sugerencia. El cambio de nivel no lo hago acá: lo decide una persona (CU12).
     *
     * @param ayer     índice del día anterior, o null si no se calculó
     * @param vigente  nivel de alerta vigente de la zona, o null si nunca tuvo
     * @param niveles  catálogo de niveles de alerta, para buscar el sugerido por nombre
     */
    public List<Alerta> evaluarRiesgo(Zona zona, IndiceRiesgo hoy, IndiceRiesgo ayer, List<FocoCalor> focosRecientes,
                                      NivelAlerta vigente, List<NivelAlerta> niveles) {
        List<Alerta> alertas = new ArrayList<>();
        int nivelHoy = NIVELES_RIESGO.indexOf(hoy.nivel());
        if (!zona.isVigilanciaActiva() || nivelHoy < NIVELES_RIESGO.indexOf("ALTO")) {
            return alertas;
        }
        String textoVigente = vigente == null ? "sin nivel" : vigente.nombre();

        boolean subio = ayer == null || NIVELES_RIESGO.indexOf(ayer.nivel()) < nivelHoy;
        if (subio) {
            NivelAlerta sugerido = sugerir(hoy.nivel().equals("EXTREMO") ? "ALERTA" : "ATENCION", vigente, niveles);
            String antes = ayer == null ? "sin índice el día anterior"
                    : String.format(Locale.ROOT, "ayer %s %.2f", ayer.nivel(), ayer.valorFinal());
            alertas.add(Alerta.deRiesgo(TipoAlerta.RIESGO_ELEVADO, hoy.id(), zona.getId(), null,
                    String.format(Locale.ROOT, "Índice de %s en %s (%.2f; %s). Nivel de alerta vigente: %s%s",
                            zona.getNombre(), hoy.nivel(), hoy.valorFinal(), antes, textoVigente, sugerencia(sugerido)),
                    sugerido));
        }

        // Solo cuento focos confiables: uno de confianza baja en riesgo alto ya tiene su alerta de foco en zona
        // para que la guardia lo verifique, pero no alcanza para sugerir EMERGENCIA.
        List<FocoCalor> confiables = focosRecientes.stream()
                .filter(f -> f.idZona() == zona.getId() && f.confianza() != Confianza.BAJA)
                .toList();
        if (!confiables.isEmpty()) {
            FocoCalor masIntenso = confiables.stream()
                    .max((a, b) -> Double.compare(a.potenciaFrpMw() == null ? 0 : a.potenciaFrpMw(),
                            b.potenciaFrpMw() == null ? 0 : b.potenciaFrpMw()))
                    .orElseThrow();
            NivelAlerta sugerido = sugerir(hoy.nivel().equals("EXTREMO") ? "EMERGENCIA" : "ALERTA", vigente, niveles);
            String frp = masIntenso.potenciaFrpMw() == null ? "" : String.format(Locale.ROOT, ", el más intenso de %.1f MW", masIntenso.potenciaFrpMw());
            alertas.add(Alerta.deRiesgo(TipoAlerta.FOCO_CON_RIESGO_ALTO, hoy.id(), zona.getId(), masIntenso.id(),
                    String.format(Locale.ROOT, "%d foco(s) confiable(s) en %s con índice %s (%.2f)%s: propagación rápida probable. "
                                    + "Nivel vigente: %s%s",
                            confiables.size(), zona.getNombre(), hoy.nivel(), hoy.valorFinal(), frp, textoVigente,
                            sugerencia(sugerido)),
                    sugerido));
        }
        return alertas;
    }

    // Busco el nivel sugerido en el catálogo y lo descarto si la zona ya tiene ese nivel o uno mayor.
    private static NivelAlerta sugerir(String nombre, NivelAlerta vigente, List<NivelAlerta> niveles) {
        NivelAlerta sugerido = niveles.stream().filter(n -> n.nombre().equals(nombre)).findFirst().orElse(null);
        if (sugerido == null || (vigente != null && vigente.orden() >= sugerido.orden())) {
            return null;
        }
        return sugerido;
    }

    private static String sugerencia(NivelAlerta sugerido) {
        return sugerido == null ? "." : ". Se sugiere pasar a " + sugerido.nombre() + ".";
    }
}
