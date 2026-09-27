-- =====================================================================
-- SARIF - Script 05: borrado de registros y verificación de las reglas
-- de integridad referencial (secciones 4.5 y 6.3).
-- En este script muestro los borrados que tienen sentido en el sistema y
-- compruebo que las reglas ON DELETE que definí en 01_esquema.sql se
-- comportan como esperaba: CASCADE donde el dato hijo no tiene sentido sin
-- el padre, y RESTRICT donde borrar el padre haría perder información
-- importante. Se corre después de 03_insercion.sql y 04_consultas.sql.
-- =====================================================================

USE sarif;

-- B-1. Borrado de alertas descartadas por la guardia (falsos positivos).
--      Lo hago en dos pasos, respetando el ciclo de estados: primero marco como
--      DESCARTADA cada alerta de foco en zona cuyo foco tiene confianza BAJA,
--      y después borro las descartadas. Las alertas de activo en riesgo no
--      las toco, porque esas ya fueron notificadas en U-1. El descarte queda a
--      nombre de la jefa de guardia y con la hora (CHECK ck_alerta_resolucion),
--      y solo desde PENDIENTE o NOTIFICADA, que es lo que permite el ciclo.
UPDATE alerta SET estado = 'DESCARTADA', id_usuario_resolucion = 2, fecha_hora_resolucion = NOW()
WHERE tipo = 'FOCO_EN_ZONA' AND estado IN ('PENDIENTE', 'NOTIFICADA')
  AND id_foco IN (SELECT id_foco FROM foco_calor WHERE confianza = 'BAJA');
DELETE FROM alerta WHERE estado = 'DESCARTADA';

-- B-2. Depuración de pronósticos que ya tienen el dato observado de la misma zona y fecha.
--      Uso un DELETE con JOIN de la tabla consigo misma: p es el pronóstico
--      y o el observado. Solo se borran los pronósticos que tienen su
--      observado correspondiente (en la jornada, los del 20/01 de las zonas
--      1 y 4 que cargué en 02_datos.sql); los que no tienen observado,
--      como los del día siguiente, se conservan.
DELETE p
FROM registro_meteo p
JOIN registro_meteo o
  ON o.id_zona = p.id_zona AND o.fecha = p.fecha AND o.es_pronostico = FALSE
WHERE p.es_pronostico = TRUE;

-- B-3. Borrado con propagación: una zona de prueba sin focos elimina en cascada
--      su relevamiento y su historial de niveles (ON DELETE CASCADE).
--      Creo una zona descartable lejos de las demás, le cargo un relevamiento
--      y un nivel inicial, guardo su id en la variable @z y la borro. La
--      consulta final tiene que devolver 0 y 0: los hijos se borraron solos.
INSERT INTO zona_vigilancia (nombre, descripcion, latitud_min, latitud_max, longitud_min, longitud_max)
VALUES ('Zona de prueba', 'Alta para verificar el borrado', -37.45000, -37.20000, -70.40000, -70.10000);
SET @z := LAST_INSERT_ID();
INSERT INTO relevamiento_combustible (id_zona, id_tipo_combustible, carga_t_ha, fecha_relevamiento, id_usuario)
VALUES (@z, 1, 10.00, '2026-01-15', 1);
INSERT INTO cambio_nivel_alerta (id_zona, id_nivel_alerta, fundamento, id_usuario)
VALUES (@z, 1, 'Alta de la zona', 1);
DELETE FROM zona_vigilancia WHERE id_zona = @z;
SELECT (SELECT COUNT(*) FROM relevamiento_combustible WHERE id_zona = @z) AS relevamientos_restantes,
       (SELECT COUNT(*) FROM cambio_nivel_alerta WHERE id_zona = @z) AS cambios_restantes;

-- B-4. Integridad referencial: una zona con activos protegidos o focos registrados no puede
--      eliminarse (ON DELETE RESTRICT). MySQL responde con el error 1451 y no borra nada.
--      Por eso va al final del script: el cliente mysql se detiene ante el error.
--      Este error es el resultado esperado: demuestra que no se puede perder
--      por accidente la evidencia (focos, activos y sus alertas) de una zona.
DELETE FROM zona_vigilancia WHERE nombre = 'Caviahue - Copahue';
