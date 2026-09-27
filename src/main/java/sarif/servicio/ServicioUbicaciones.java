package sarif.servicio;

import sarif.datos.ConexionBD;
import sarif.datos.RecursoDAO;
import sarif.datos.UbicacionDAO;
import sarif.datos.ZonaDAO;
import sarif.modelo.EstadoRecurso;
import sarif.modelo.MarcaMapa;
import sarif.modelo.Permiso;
import sarif.modelo.PosicionRecurso;
import sarif.modelo.Recurso;
import sarif.modelo.TipoMarca;
import sarif.modelo.Zona;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Lo que la guardia marca sobre el mapa durante un incidente: dónde está cada recurso desplegado y las marcas
 * operativas (puesto de comando, punto de agua, acceso, evacuación, peligro). Es parte de operar la guardia,
 * así que todo lo que escribe exige el permiso OPERAR. La zona de cada punto la calculo acá con las zonas
 * registradas, no la elige el operador.
 */
public class ServicioUbicaciones {

    /**
     * Ubico un recurso desplegado en el punto. Solo los recursos ASIGNADO: son los que están en el terreno, y
     * cuando se liberan dejan de verse en el mapa.
     */
    public void ubicarRecurso(int idRecurso, double latitud, double longitud, String observaciones, int idUsuario)
            throws SQLException, ValidacionException {
        Permisos.exigir(idUsuario, Permiso.OPERAR);
        List<String> errores = validarUbicacion(latitud, longitud, observaciones);
        if (!errores.isEmpty()) {
            throw new ValidacionException(errores);
        }
        try (Connection cn = ConexionBD.obtener()) {
            Integer idZona = zonaQueContiene(new ZonaDAO(cn).listar(), latitud, longitud);
            if (!new UbicacionDAO(cn).insertarPosicion(idRecurso, idZona, latitud, longitud, limpio(observaciones), idUsuario)) {
                throw new ValidacionException("Solo se ubican en el mapa los recursos asignados (desplegados). "
                        + "Actualice la lista: el recurso pudo haberse liberado.");
            }
        }
    }

    /** Pongo una marca operativa en el punto y devuelvo su id. */
    public int agregarMarca(TipoMarca tipo, String descripcion, double latitud, double longitud, int idUsuario)
            throws SQLException, ValidacionException {
        Permisos.exigir(idUsuario, Permiso.OPERAR);
        List<String> errores = validarMarca(tipo, descripcion, latitud, longitud);
        if (!errores.isEmpty()) {
            throw new ValidacionException(errores);
        }
        try (Connection cn = ConexionBD.obtener()) {
            Integer idZona = zonaQueContiene(new ZonaDAO(cn).listar(), latitud, longitud);
            return new UbicacionDAO(cn).insertarMarca(tipo, descripcion.trim(), latitud, longitud, idZona, idUsuario);
        }
    }

    /** Quito la marca del mapa; queda en la base con quién y cuándo la quitó. */
    public void quitarMarca(int idMarca, int idUsuario) throws SQLException, ValidacionException {
        Permisos.exigir(idUsuario, Permiso.OPERAR);
        try (Connection cn = ConexionBD.obtener()) {
            if (!new UbicacionDAO(cn).quitarMarca(idMarca, idUsuario)) {
                throw new ValidacionException("La marca ya había sido quitada del mapa.");
            }
        }
    }

    public List<MarcaMapa> marcas() throws SQLException {
        try (Connection cn = ConexionBD.obtener()) {
            return new UbicacionDAO(cn).listarMarcas();
        }
    }

    /** Última ubicación de cada recurso que sigue asignado. */
    public List<PosicionRecurso> desplegados() throws SQLException {
        try (Connection cn = ConexionBD.obtener()) {
            return new UbicacionDAO(cn).ultimasDeDesplegados();
        }
    }

    /** Recursos que se pueden ubicar en el mapa (los ASIGNADO), para el combo del diálogo. */
    public List<Recurso> recursosAsignados() throws SQLException {
        try (Connection cn = ConexionBD.obtener()) {
            return new RecursoDAO(cn).listar().stream().filter(r -> r.estado() == EstadoRecurso.ASIGNADO).toList();
        }
    }

    /** Reglas de la ubicación de un recurso, estáticas y sin base para probarlas con JUnit. */
    public static List<String> validarUbicacion(double latitud, double longitud, String observaciones) {
        List<String> errores = new ArrayList<>();
        validarCoordenadas(latitud, longitud, errores);
        if (observaciones != null && observaciones.trim().length() > PosicionRecurso.LARGO_MAXIMO_OBSERVACIONES) {
            errores.add("Las observaciones no pueden superar los " + PosicionRecurso.LARGO_MAXIMO_OBSERVACIONES + " caracteres.");
        }
        return errores;
    }

    /** Reglas de una marca: tipo y descripción obligatorios, descripción de hasta 120 caracteres. */
    public static List<String> validarMarca(TipoMarca tipo, String descripcion, double latitud, double longitud) {
        List<String> errores = new ArrayList<>();
        if (tipo == null) {
            errores.add("Debe elegir el tipo de marca.");
        }
        if (descripcion == null || descripcion.isBlank()) {
            errores.add("La descripción de la marca es obligatoria.");
        } else if (descripcion.trim().length() > MarcaMapa.LARGO_MAXIMO_DESCRIPCION) {
            errores.add("La descripción no puede superar los " + MarcaMapa.LARGO_MAXIMO_DESCRIPCION + " caracteres.");
        }
        validarCoordenadas(latitud, longitud, errores);
        return errores;
    }

    /**
     * Id de la primera zona que contiene el punto, o null si cae fuera de todas. Las zonas no se superponen
     * (lo controla el alta), así que en la práctica hay a lo sumo una.
     */
    public static Integer zonaQueContiene(List<Zona> zonas, double latitud, double longitud) {
        for (Zona z : zonas) {
            if (z.contiene(latitud, longitud)) {
                return z.getId();
            }
        }
        return null;
    }

    private static void validarCoordenadas(double latitud, double longitud, List<String> errores) {
        if (latitud < -90 || latitud > 90 || longitud < -180 || longitud > 180 || Double.isNaN(latitud) || Double.isNaN(longitud)) {
            errores.add("Las coordenadas del punto no son válidas.");
        }
    }

    private static String limpio(String texto) {
        return texto == null || texto.isBlank() ? null : texto.trim();
    }
}
