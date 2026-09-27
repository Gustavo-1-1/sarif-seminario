package sarif.servicio;

import sarif.datos.ConexionBD;
import sarif.datos.IndiceDAO;
import sarif.datos.RecursoDAO;
import sarif.datos.ZonaDAO;
import sarif.modelo.Asignacion;
import sarif.modelo.EstadoRecurso;
import sarif.modelo.FilaTablero;
import sarif.modelo.IndiceRiesgo;
import sarif.modelo.Permiso;
import sarif.modelo.Recurso;
import sarif.modelo.TipoRecurso;
import sarif.modelo.ZonaParaAsignar;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Implementa el CU10 Sugerir despliegue preventivo y el CU11 Registrar asignación.
 * La sugerencia no modifica la base: es solo una propuesta que el operador revisa, y recién cuando
 * la confirma (CU11) registro las asignaciones. Por eso separé sugerir(listas), que no toca la base
 * y se prueba con JUnit sin MySQL, de la versión que lee los datos de la base.
 */
public class ServicioDespliegue {

    /**
     * Armo la sugerencia en tres pasadas sobre las zonas ordenadas por índice, de mayor a menor
     * (las de nivel BAJO quedan excluidas porque no justifican mover recursos):
     * 1) un recurso terrestre por zona, preferentemente el que tiene su base en ella;
     * 2) los medios aéreos a las zonas en nivel EXTREMO y luego ALTO;
     * 3) los terrestres restantes como refuerzo de las zonas en nivel EXTREMO.
     */
    public List<Asignacion> sugerir(List<IndiceRiesgo> indices, List<Recurso> recursos) {
        // Me quedo con las zonas que no están en BAJO y las ordeno de mayor a menor índice,
        // así las más comprometidas son las primeras en recibir recursos.
        List<IndiceRiesgo> zonas = indices.stream()
                .filter(i -> !"BAJO".equals(i.nivel()))
                .sorted(Comparator.comparingDouble(IndiceRiesgo::valorFinal).reversed())
                .toList();
        // Separo los recursos disponibles en terrestres y aéreos; los que no están DISPONIBLE no los considero.
        List<Recurso> terrestres = new ArrayList<>();
        List<Recurso> aereos = new ArrayList<>();
        for (Recurso r : recursos) {
            if (r.estado() == EstadoRecurso.DISPONIBLE) {
                (r.tipo() == TipoRecurso.AEREO ? aereos : terrestres).add(r);
            }
        }

        List<Asignacion> sugerencia = new ArrayList<>();
        Set<Integer> zonasCubiertas = new HashSet<>();

        // Pasada 1a: a cada zona le asigno la brigada que tiene su base en ella, porque ya conoce
        // el terreno y no hay que trasladarla.
        for (IndiceRiesgo zona : zonas) {
            Recurso deBase = terrestres.stream()
                    .filter(r -> r.idZonaBase() != null && r.idZonaBase() == zona.idZona())
                    .findFirst().orElse(null);
            if (deBase != null) {
                terrestres.remove(deBase);
                sugerencia.add(asignar(deBase, zona, "Recurso con base en la zona"));
                zonasCubiertas.add(zona.idZona());
            }
        }
        // Pasada 1b: las zonas sin brigada propia reciben otro terrestre. Ordeno para usar primero los
        // que no tienen base, y así no dejo descubierta la zona de origen de otro recurso.
        terrestres.sort(Comparator.comparing(r -> r.idZonaBase() != null));
        for (IndiceRiesgo zona : zonas) {
            if (!zonasCubiertas.contains(zona.idZona()) && !terrestres.isEmpty()) {
                sugerencia.add(asignar(terrestres.remove(0), zona, "Cobertura terrestre de la zona"));
                zonasCubiertas.add(zona.idZona());
            }
        }
        // Pasada 2: los medios aéreos son pocos y caros, así que van primero a las zonas en EXTREMO
        // y, si sobran, a las de ALTO.
        for (String nivel : List.of("EXTREMO", "ALTO")) {
            for (IndiceRiesgo zona : zonas) {
                if (nivel.equals(zona.nivel()) && !aereos.isEmpty()) {
                    sugerencia.add(asignar(aereos.remove(0), zona, "Medio aéreo por nivel " + nivel));
                }
            }
        }
        // Pasada 3: con los terrestres que quedaron refuerzo las zonas en EXTREMO, de a uno por zona
        // (en ronda) mientras haya recursos, para repartirlos parejo.
        List<IndiceRiesgo> extremas = zonas.stream().filter(z -> "EXTREMO".equals(z.nivel())).toList();
        while (!extremas.isEmpty() && !terrestres.isEmpty()) {
            for (IndiceRiesgo zona : extremas) {
                if (!terrestres.isEmpty()) {
                    sugerencia.add(asignar(terrestres.remove(0), zona, "Refuerzo por nivel EXTREMO"));
                }
            }
        }
        // Por último ordeno la sugerencia por índice para que el operador vea arriba las zonas más críticas.
        sugerencia.sort(Comparator.comparing(Asignacion::indice, Comparator.nullsLast(Comparator.reverseOrder())));
        return sugerencia;
    }

    /** Armo la sugerencia para la fecha de trabajo, leyendo de la base los índices del día y los recursos disponibles. */
    public List<Asignacion> sugerir(LocalDate fecha) throws SQLException {
        try (Connection cn = ConexionBD.obtener()) {
            return sugerir(new IndiceDAO(cn).listarPorFecha(fecha), new RecursoDAO(cn).listarDisponibles());
        }
    }

    /**
     * CU11: registro las asignaciones confirmadas y marco los recursos como ASIGNADO, todo en una única
     * transacción. Si falla alguna, revierto todo para no dejar recursos asignados a medias. Confirmar es una
     * decisión del jefe de guardia; pedir la sugerencia (sugerir) lo puede hacer cualquiera que opere.
     */
    public void confirmar(List<Asignacion> asignaciones, int idUsuario, LocalDate fecha) throws SQLException, ValidacionException {
        Permisos.exigir(idUsuario, Permiso.ASIGNAR_RECURSOS);
        if (asignaciones.isEmpty()) {
            throw new ValidacionException("No hay asignaciones para confirmar.");
        }
        // Como el operador puede ajustar la sugerencia a mano, controlo que no haya quedado un recurso dos veces.
        Set<Integer> vistos = new HashSet<>();
        for (Asignacion a : asignaciones) {
            if (!vistos.add(a.recurso().id())) {
                throw new ValidacionException("El recurso " + a.recurso().denominacion() + " está asignado más de una vez.");
            }
        }
        try (Connection cn = ConexionBD.obtener()) {
            // Apago el autocommit para manejar yo la transacción.
            cn.setAutoCommit(false);
            try {
                RecursoDAO recursos = new RecursoDAO(cn);
                for (Asignacion a : asignaciones) {
                    recursos.registrarAsignacion(a, idUsuario, fecha);
                    recursos.cambiarEstado(a.recurso().id(), EstadoRecurso.ASIGNADO);
                }
                cn.commit();
            } catch (SQLException e) {
                cn.rollback();
                throw e;
            }
        }
    }

    /** Índices de la fecha (todas las zonas calculadas, de mayor a menor). */
    public List<IndiceRiesgo> indicesDelDia(LocalDate fecha) throws SQLException {
        try (Connection cn = ConexionBD.obtener()) {
            return new IndiceDAO(cn).listarPorFecha(fecha);
        }
    }

    /**
     * Zonas para el combo del ajuste manual: todas las registradas, no solo las que tienen índice del día,
     * porque el jefe de guardia puede necesitar mandar un recurso a una zona recién dada de alta o en
     * EMERGENCIA aunque todavía no se haya calculado el índice. Primero van las que tienen índice, de mayor a
     * menor, y después las demás por nombre.
     */
    public List<ZonaParaAsignar> zonasParaAjuste(LocalDate fecha) throws SQLException {
        try (Connection cn = ConexionBD.obtener()) {
            Map<Integer, IndiceRiesgo> indices = new HashMap<>();
            for (IndiceRiesgo i : new IndiceDAO(cn).listarPorFecha(fecha)) {
                indices.put(i.idZona(), i);
            }
            Map<Integer, String> niveles = new HashMap<>();
            for (FilaTablero fila : new ZonaDAO(cn).tablero()) {
                niveles.put(fila.idZona(), fila.nivelAlerta());
            }
            return ordenarZonas(new ZonaDAO(cn).listar().stream()
                    .map(z -> new ZonaParaAsignar(z.getId(), z.getNombre(), indices.get(z.getId()), niveles.get(z.getId())))
                    .toList());
        }
    }

    /** Orden del combo: con índice de mayor a menor, y las que no tienen índice al final, por nombre. */
    static List<ZonaParaAsignar> ordenarZonas(List<ZonaParaAsignar> zonas) {
        return zonas.stream().sorted(Comparator
                        .comparing((ZonaParaAsignar z) -> z.indice() == null ? null : z.indice().valorFinal(),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(ZonaParaAsignar::nombreZona))
                .toList();
    }

    /** Recursos en estado DISPONIBLE, para agregar asignaciones a mano. */
    public List<Recurso> recursosDisponibles() throws SQLException {
        try (Connection cn = ConexionBD.obtener()) {
            return new RecursoDAO(cn).listarDisponibles();
        }
    }

    /**
     * CU10 paso 5: el operador ajusta la sugerencia. Con esto armo una fila nueva, ya sea para mandar el
     * recurso de una fila a otra zona o para agregar una asignación manual (flujo S3), con el índice de la
     * zona elegida como fundamento. Si la zona no tiene índice del día, la asignación queda sin índice.
     */
    public static Asignacion ajustar(Recurso recurso, ZonaParaAsignar zona, String motivo) {
        if (zona.indice() != null) {
            return asignar(recurso, zona.indice(), motivo);
        }
        return new Asignacion(recurso, zona.idZona(), zona.nombreZona(), null, null, null,
                motivo + (zona.nivelAlerta() == null ? " (sin índice del día)" : " (sin índice del día, alerta " + zona.nivelAlerta() + ")"));
    }

    /** Armo la Asignacion copiando los datos de la zona y su índice, más el motivo que se muestra al operador. */
    private static Asignacion asignar(Recurso recurso, IndiceRiesgo zona, String motivo) {
        return new Asignacion(recurso, zona.idZona(), zona.nombreZona(), zona.id(), zona.valorFinal(), zona.nivel(), motivo);
    }
}
