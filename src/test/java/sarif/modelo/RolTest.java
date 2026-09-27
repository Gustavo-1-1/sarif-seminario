package sarif.modelo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sarif.servicio.ServicioUsuarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prueba unitaria de los roles y permisos (PU-30).
 *
 * <p>Verifico la matriz de permisos de cada rol, que un rol desconocido o un usuario desactivado no tengan
 * ningún permiso y las reglas del alta de usuarios que no necesitan la base (nombre de usuario y clave).</p>
 */
class RolTest {

    @Test
    @DisplayName("PU-30 Roles: matriz de permisos, rol desconocido, usuario inactivo y reglas del alta")
    void roles() {
        // Todos operan la guardia; el jefe además decide niveles y asignaciones; el administrador puede todo.
        for (Rol rol : Rol.values()) {
            assertTrue(rol.puede(Permiso.OPERAR), rol.name());
        }
        for (Permiso p : Permiso.values()) {
            assertTrue(Rol.ADMINISTRADOR.puede(p), p.name());
        }
        assertTrue(Rol.JEFE_GUARDIA.puede(Permiso.CAMBIAR_NIVEL_ALERTA));
        assertTrue(Rol.JEFE_GUARDIA.puede(Permiso.ASIGNAR_RECURSOS));
        assertFalse(Rol.OPERADOR.puede(Permiso.CAMBIAR_NIVEL_ALERTA));
        assertFalse(Rol.OPERADOR.puede(Permiso.ASIGNAR_RECURSOS));
        assertFalse(Rol.JEFE_GUARDIA.puede(Permiso.CONFIGURAR));
        assertFalse(Rol.OPERADOR.puede(Permiso.ADMINISTRAR_USUARIOS));

        // El nombre de la base se convierte directo; uno desconocido no da ningún permiso.
        assertEquals(Rol.JEFE_GUARDIA, Rol.desde("JEFE_GUARDIA"));
        assertNull(Rol.desde("SUPERVISOR"));
        assertFalse(Rol.permite("SUPERVISOR", Permiso.OPERAR));

        // Un usuario desactivado pierde los permisos de su rol.
        Usuario activo = new Usuario(2, "mruiz", "Marcela", "Ruiz", "JEFE_GUARDIA", true);
        Usuario inactivo = new Usuario(2, "mruiz", "Marcela", "Ruiz", "JEFE_GUARDIA", false);
        assertTrue(activo.puede(Permiso.CAMBIAR_NIVEL_ALERTA));
        assertFalse(inactivo.puede(Permiso.CAMBIAR_NIVEL_ALERTA));
        assertEquals("Jefe de guardia", activo.rolTexto());

        // Alta: nombre de usuario en minúsculas sin espacios, nombre y apellido obligatorios, rol conocido.
        assertTrue(ServicioUsuarios.validar(new Usuario(0, "jperez", "Juan", "Pérez", "OPERADOR", true)).isEmpty());
        assertEquals(4, ServicioUsuarios.validar(new Usuario(0, "JP", " ", "", "SUPERVISOR", true)).size());
        // Clave: al menos 8 caracteres y sin espacios al principio ni al final.
        assertTrue(ServicioUsuarios.validarClave("brigada2026").isEmpty());
        assertEquals(1, ServicioUsuarios.validarClave("corta").size());
        assertEquals(1, ServicioUsuarios.validarClave(" brigada2026").size());
    }
}
