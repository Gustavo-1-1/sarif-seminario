-- =====================================================================
-- SARIF - Script 04: consultas del tablero, del índice y de los reportes
-- (sección 7, CU13). Se ejecuta después de 03_insercion.sql.
-- Reúno acá las consultas que usa el prototipo (o que usaría la guardia
-- para sus reportes) sobre los datos de la jornada del 20/01/2026. Traté
-- de que cada una muestre un recurso distinto de SQL: vistas, JOIN,
-- LEFT JOIN, GROUP BY con HAVING, funciones de ventana y subconsultas
-- con NOT EXISTS. Son todas de solo lectura, así que podés correr el
-- script las veces que quieras sin alterar nada.
-- =====================================================================

USE sarif;

-- C-1. Tablero de zonas (CU06, RFS14).
--      Me apoyo en la vista v_tablero_zonas, que ya resuelve el índice más
--      reciente y el nivel de alerta vigente de cada zona. Ordeno de mayor a
--      menor índice para que las zonas más comprometidas queden arriba.
SELECT nombre AS zona, IF(vigilancia_activa, 'Activa', 'Inactiva') AS vigilancia,
       indice, nivel_riesgo, nivel_alerta
FROM v_tablero_zonas
ORDER BY indice DESC;

-- C-2. Componentes del índice del día (CU09).
--      Muestro H, M y C junto al valor final para explicar de dónde sale
--      cada índice de la jornada.
SELECT z.nombre AS zona, i.comp_historica AS H, i.comp_meteorologica AS M,
       i.comp_combustible AS C, i.valor_final AS indice, n.nombre AS nivel
FROM indice_riesgo i
JOIN zona_vigilancia z USING (id_zona)
JOIN nivel_riesgo n USING (id_nivel_riesgo)
WHERE i.fecha = '2026-01-20'
ORDER BY i.valor_final DESC;

-- C-3. Focos históricos por zona y temporada (RFS18).
--      Temporada de julio a junio; solo las temporadas con al menos diez focos.
--      Armo el nombre de la temporada (por ejemplo, 2023-2024) aprovechando
--      que en MySQL una comparación vale 1 o 0: si el mes es anterior a julio,
--      el foco pertenece a la temporada que empezó el año anterior. Filtro
--      los focos anteriores al 01/07/2025 para quedarme solo con el histórico,
--      y el HAVING descarta las temporadas con pocos focos.
SELECT z.nombre AS zona,
       CONCAT(YEAR(f.fecha_hora_utc) - (MONTH(f.fecha_hora_utc) < 7), '-',
              YEAR(f.fecha_hora_utc) + (MONTH(f.fecha_hora_utc) >= 7)) AS temporada,
       COUNT(*) AS focos,
       ROUND(AVG(f.potencia_frp_mw), 1) AS frp_promedio_mw
FROM foco_calor f
JOIN zona_vigilancia z USING (id_zona)
WHERE f.fecha_hora_utc < '2025-07-01'
GROUP BY z.nombre, temporada
HAVING COUNT(*) >= 10
ORDER BY z.nombre, temporada;

-- C-4. Alertas con activo comprometido, distancia y nivel sugerido (CU14,
--      CU15, alertas de riesgo y de nivel elevado). La zona sale del foco, del
--      índice o del cambio de nivel, según el tipo: por eso los LEFT JOIN y el COALESCE. También uso
--      LEFT JOIN con activo_protegido y nivel_alerta porque la mayoría de las
--      alertas no tienen activo ni sugerencia; en ese caso muestro un guion.
--      Las notificadas muestran a quién se avisó, por qué medio, quién lo
--      registró y cuándo (LEFT JOIN con usuario: las pendientes no tienen).
SELECT a.id_alerta, z.nombre AS zona, a.tipo, COALESCE(ap.nombre, '—') AS activo,
       a.distancia_km, f.confianza, COALESCE(na.nombre, '—') AS nivel_sugerido, a.estado,
       COALESCE(a.notificado_a, '—') AS notificado_a, a.medio_notificacion,
       CONCAT(u.nombre, ' ', u.apellido) AS notifico, a.fecha_hora_notificacion
FROM alerta a
LEFT JOIN foco_calor f ON f.id_foco = a.id_foco
LEFT JOIN indice_riesgo i ON i.id_indice = a.id_indice
LEFT JOIN cambio_nivel_alerta ca ON ca.id_cambio = a.id_cambio
JOIN zona_vigilancia z ON z.id_zona = COALESCE(f.id_zona, i.id_zona, ca.id_zona)
LEFT JOIN activo_protegido ap ON ap.id_activo = a.id_activo
LEFT JOIN nivel_alerta na ON na.id_nivel_alerta = a.id_nivel_alerta_sugerido
LEFT JOIN usuario u ON u.id_usuario = a.id_usuario_notifica
ORDER BY a.id_alerta;

-- C-5. Historial de niveles de alerta de una zona (RFS17).
--      El nivel anterior se deriva con la función de ventana LAG.
--      Por eso no lo guardo en la tabla: LAG toma el nivel de la fila previa
--      en el orden de los cambios. Uso Caviahue - Copahue (zona 4), que es la
--      que tiene más cambios; en el primer registro el nivel anterior sale NULL.
SELECT c.fecha_hora,
       LAG(n.nombre) OVER (ORDER BY c.id_cambio) AS nivel_anterior,
       n.nombre AS nivel_nuevo,
       CONCAT(u.nombre, ' ', u.apellido) AS responsable,
       c.fundamento
FROM cambio_nivel_alerta c
JOIN nivel_alerta n USING (id_nivel_alerta)
JOIN usuario u USING (id_usuario)
WHERE c.id_zona = 4
ORDER BY c.id_cambio;

-- C-6. Reporte de desempeño (RFS19): nivel previsto frente a focos detectados.
--      Comparo lo que anticipó el índice con los focos que efectivamente se
--      detectaron ese día en la zona. El LEFT JOIN hace que aparezcan también
--      los días sin focos (con 0), que son tan importantes como los otros
--      para evaluar si el índice acierta.
SELECT z.nombre AS zona, i.fecha, i.valor_final AS indice, n.nombre AS nivel_previsto,
       COUNT(f.id_foco) AS focos_detectados
FROM indice_riesgo i
JOIN zona_vigilancia z USING (id_zona)
JOIN nivel_riesgo n USING (id_nivel_riesgo)
LEFT JOIN foco_calor f
       ON f.id_zona = i.id_zona
      AND DATE(f.fecha_hora_utc) = i.fecha
GROUP BY z.nombre, i.fecha, i.valor_final, n.nombre, n.orden
ORDER BY n.orden DESC, i.valor_final DESC;

-- C-7. Evolución del combustible relevado (RFS21).
--      Otra vez LAG, pero ahora particionado por zona, para ver al lado de
--      cada relevamiento la carga del relevamiento anterior de la misma zona.
SELECT z.nombre AS zona, t.nombre AS combustible, r.fecha_relevamiento, r.carga_t_ha,
       LAG(r.carga_t_ha) OVER (PARTITION BY r.id_zona ORDER BY r.fecha_relevamiento) AS carga_anterior
FROM relevamiento_combustible r
JOIN zona_vigilancia z USING (id_zona)
JOIN tipo_combustible t USING (id_tipo_combustible)
ORDER BY z.nombre, r.fecha_relevamiento;

-- C-8. Recursos por tipo y estado.
--      Resumen de cuántos recursos y cuánta gente hay disponible, asignada o
--      fuera de servicio, separado en terrestres y aéreos.
SELECT tipo, estado, COUNT(*) AS recursos, SUM(dotacion) AS dotacion_total
FROM recurso
GROUP BY tipo, estado
ORDER BY tipo, estado;

-- C-9. Zonas activas sin datos meteorológicos (RFS20).
--      Con NOT EXISTS busco las zonas vigiladas que no tienen ningún registro
--      meteorológico para la fecha: son las zonas a las que no se les puede
--      calcular el índice ese día.
SELECT z.nombre AS zona_sin_datos
FROM zona_vigilancia z
WHERE z.vigilancia_activa = TRUE
  AND NOT EXISTS (SELECT 1 FROM registro_meteo m
                  WHERE m.id_zona = z.id_zona AND m.fecha = '2026-01-20');

-- C-10. Auditoría de sincronizaciones.
--       Listo todas las sincronizaciones en orden, incluidas las fallidas,
--       para ver de dónde vino cada lote de datos y cuántos registros aportó.
SELECT id_sincronizacion AS id, tipo, origen, resultado, registros_nuevos, detalle
FROM sincronizacion
ORDER BY id_sincronizacion;

-- C-11. Cronología de la guardia (CU13, reporte de la pestaña Reportes).
--       Cada alerta, su notificación y su cierre o descarte, los cambios de
--       nivel y las asignaciones, en orden de fecha y hora, con el nivel de
--       alerta vigente de la zona en ese momento y el tiempo desde la alerta.
--       También lo que la guardia marcó en el mapa: recursos ubicados y
--       marcas puestas o quitadas.
--       Junto ocho consultas con UNION ALL (mismas columnas) y el nivel
--       vigente lo busco con una subconsulta correlacionada: el último cambio
--       de la zona anterior al hito. Es la misma consulta que usa ReporteDAO.
SELECT e.momento, z.nombre AS zona, e.hito, e.detalle, e.responsable, e.minutos_desde_alerta,
       COALESCE(e.nivel_nuevo, (SELECT n.nombre FROM cambio_nivel_alerta c
                                JOIN nivel_alerta n USING (id_nivel_alerta)
                                WHERE c.id_zona = e.id_zona AND c.fecha_hora <= e.momento
                                ORDER BY c.fecha_hora DESC, c.id_cambio DESC LIMIT 1)) AS nivel_alerta
FROM (
    SELECT a.fecha_hora AS momento, COALESCE(f.id_zona, i.id_zona, ca.id_zona) AS id_zona, 'Alerta generada' AS hito,
           CONCAT('#', a.id_alerta, ' ', a.descripcion) AS detalle, 'Sistema' AS responsable,
           NULL AS minutos_desde_alerta, NULL AS nivel_nuevo
    FROM alerta a LEFT JOIN foco_calor f USING (id_foco) LEFT JOIN indice_riesgo i USING (id_indice) LEFT JOIN cambio_nivel_alerta ca ON ca.id_cambio = a.id_cambio
    UNION ALL
    SELECT a.fecha_hora_notificacion, COALESCE(f.id_zona, i.id_zona, ca.id_zona), 'Alerta notificada',
           CONCAT('#', a.id_alerta, ' avisada a ', a.notificado_a, ' (', a.medio_notificacion, ')'),
           CONCAT(u.nombre, ' ', u.apellido), TIMESTAMPDIFF(MINUTE, a.fecha_hora, a.fecha_hora_notificacion), NULL
    FROM alerta a LEFT JOIN foco_calor f USING (id_foco) LEFT JOIN indice_riesgo i USING (id_indice) LEFT JOIN cambio_nivel_alerta ca ON ca.id_cambio = a.id_cambio
    JOIN usuario u ON u.id_usuario = a.id_usuario_notifica
    UNION ALL
    SELECT a.fecha_hora_resolucion, COALESCE(f.id_zona, i.id_zona, ca.id_zona), CONCAT('Alerta ', LOWER(a.estado)),
           CONCAT('#', a.id_alerta), CONCAT(u.nombre, ' ', u.apellido),
           TIMESTAMPDIFF(MINUTE, a.fecha_hora, a.fecha_hora_resolucion), NULL
    FROM alerta a LEFT JOIN foco_calor f USING (id_foco) LEFT JOIN indice_riesgo i USING (id_indice) LEFT JOIN cambio_nivel_alerta ca ON ca.id_cambio = a.id_cambio
    JOIN usuario u ON u.id_usuario = a.id_usuario_resolucion
    UNION ALL
    SELECT c.fecha_hora, c.id_zona, 'Cambio de nivel', c.fundamento, CONCAT(u.nombre, ' ', u.apellido), NULL, n.nombre
    FROM cambio_nivel_alerta c JOIN nivel_alerta n USING (id_nivel_alerta) JOIN usuario u USING (id_usuario)
    UNION ALL
    SELECT s.fecha_hora_registro, s.id_zona, 'Asignación de recurso',
           CONCAT(r.denominacion, ' para el ', DATE_FORMAT(s.fecha, '%d/%m/%Y')),
           CONCAT(u.nombre, ' ', u.apellido), NULL, NULL
    FROM asignacion s JOIN recurso r USING (id_recurso) JOIN usuario u USING (id_usuario)
    UNION ALL
    SELECT p.fecha_hora, p.id_zona, 'Recurso ubicado en el mapa',
           CONCAT(r.denominacion, ' en ', p.latitud, ', ', p.longitud), CONCAT(u.nombre, ' ', u.apellido), NULL, NULL
    FROM posicion_recurso p JOIN recurso r USING (id_recurso) JOIN usuario u USING (id_usuario)
    UNION ALL
    SELECT m.fecha_hora, m.id_zona, 'Marca agregada al mapa', CONCAT(m.tipo, ': ', m.descripcion),
           CONCAT(u.nombre, ' ', u.apellido), NULL, NULL
    FROM marca_mapa m JOIN usuario u USING (id_usuario)
    UNION ALL
    SELECT m.fecha_hora_baja, m.id_zona, 'Marca quitada del mapa', CONCAT(m.tipo, ': ', m.descripcion),
           CONCAT(u.nombre, ' ', u.apellido), NULL, NULL
    FROM marca_mapa m JOIN usuario u ON u.id_usuario = m.id_usuario_baja
) e
-- LEFT JOIN: las ubicaciones y las marcas pueden estar fuera de las zonas.
LEFT JOIN zona_vigilancia z ON z.id_zona = e.id_zona
ORDER BY e.momento;

-- C-12. Recursos desplegados en el mapa: última ubicación de cada recurso
--       ASIGNADO (pestaña Mapa). La ubicación "última" es la de id más alto
--       del recurso, que se busca con una subconsulta correlacionada.
SELECT r.denominacion, r.tipo, r.dotacion, p.latitud, p.longitud,
       COALESCE(z.nombre, 'fuera de las zonas') AS zona, p.fecha_hora, p.observaciones
FROM posicion_recurso p
JOIN recurso r USING (id_recurso)
LEFT JOIN zona_vigilancia z ON z.id_zona = p.id_zona
WHERE r.estado = 'ASIGNADO'
  AND p.id_posicion = (SELECT MAX(p2.id_posicion) FROM posicion_recurso p2 WHERE p2.id_recurso = p.id_recurso)
ORDER BY r.denominacion;

-- C-13. Pronóstico y viento de una zona (pestaña Pronóstico): un registro por
--       día desde el 19/01/2026, prefiriendo el observado al pronóstico. La
--       dirección está en grados desde donde sopla; el fuego avanza hacia la
--       opuesta, que calculo con MOD(dirección + 180, 360). La última columna
--       marca los días de la regla 30-30-30.
SELECT m.fecha, IF(m.es_pronostico, 'Pronóstico', 'Observado') AS dato,
       m.temperatura_max_c, m.temperatura_min_c, m.humedad_min_pct,
       m.viento_max_kmh, m.rafaga_max_kmh, m.direccion_viento_grados AS viento_desde,
       MOD(m.direccion_viento_grados + 180, 360) AS fuego_hacia,
       m.precipitacion_mm, m.prob_precipitacion_pct,
       ROUND(m.humedad_suelo_m3m3 * 100, 1) AS humedad_suelo_pct, m.evapotranspiracion_mm,
       (m.temperatura_max_c > 30 AND m.humedad_min_pct < 30 AND m.viento_max_kmh > 30) AS regla_30_30_30
FROM registro_meteo m
WHERE m.id_zona = 4
  AND m.fecha >= '2026-01-19'
  AND NOT (m.es_pronostico AND EXISTS (SELECT 1 FROM registro_meteo o
                                       WHERE o.id_zona = m.id_zona AND o.fecha = m.fecha AND NOT o.es_pronostico))
ORDER BY m.fecha;
