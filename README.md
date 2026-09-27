# SARIF — Sistema de Anticipación y Respuesta ante Incendios Forestales

Prototipo operacional de la Actividad Práctica 2 del Seminario de Práctica.
Alumno: Ángel Gustavo Soto — 2026.

SARIF es un sistema de escritorio de soporte a la decisión para el Consorcio Intermunicipal
de Protección contra Incendios (CIPI, organismo ficticio). Calcula un índice de riesgo diario
por zona, sincroniza focos de calor satelitales (NASA FIRMS) y datos meteorológicos
(Open-Meteo), genera alertas y sugiere el despliegue preventivo de recursos.

## Tecnologías

| Elemento | Herramienta |
|---|---|
| Lenguaje | Java 17 o superior (records, `java.net.http`, `java.sql`) |
| Interfaz | JavaFX 21 con vistas FXML (patrón MVC) |
| Base de datos | MySQL 8.0 (InnoDB, utf8mb4) |
| Driver | MySQL Connector/J 8.4 |
| Pruebas | JUnit 5 |

## Estructura

```
config/            sarif.properties (conexión y archivos del modo sin conexión);
                   sarif.local.properties (no versionado: clave FIRMS y ajustes locales)
sql/               01_esquema, 02_datos, 03_insercion, 04_consultas, 05_borrado
datos/             archivos CSV con el formato de FIRMS y Open-Meteo (modo sin conexión)
src/main/java/sarif/
    App.java       punto de entrada JavaFX
    modelo/        clases de entidad
    datos/         ConexionBD y DAO (JDBC)
    fuentes/       FuenteDeDatos, FuenteRemota, FuenteArchivo y lectores CSV
    servicio/      lógica de los casos de uso
    controlador/   controladores JavaFX
src/main/resources/sarif/vista/   vistas FXML y hoja de estilos
src/main/resources/sarif/mapa/    mapa general por mosaicos (Argentina, limítrofes y Neuquén) para usar sin conexión
src/test/java/     pruebas unitarias (PU) y de integración (PI)
docs/diagramas/    fuentes PlantUML de los diagramas
herramientas/      generadores de los archivos de prueba y del mapa general sin conexión
evidencias/        resultado de las pruebas
```

## Puesta en marcha

1. Elegir la clave del usuario de aplicación `sarif_app`. El repositorio no trae ninguna clave
   real: en la última sentencia de `sql/01_esquema.sql` dice `CAMBIAR_ESTA_CLAVE`. Antes de
   correr el script, reemplazá ese texto (en tu copia local, sin subirla) por la clave que elijas:

   ```sql
   CREATE USER IF NOT EXISTS 'sarif_app'@'localhost' IDENTIFIED BY 'tu_clave';
   ```

2. Crear la base con un usuario administrador de MySQL (por ejemplo, `root`):

   ```
   mysql -u root -p < sql/01_esquema.sql
   mysql -u root -p < sql/02_datos.sql
   ```

   El script 01 crea también el usuario `sarif_app`, que solo puede leer y escribir datos.
   Si `sarif_app` ya existía (por ejemplo, de una instalación anterior), el `CREATE USER IF NOT
   EXISTS` no le cambia la clave. Para cambiarla, como administrador:

   ```sql
   ALTER USER 'sarif_app'@'localhost' IDENTIFIED BY 'tu_clave';
   ```

3. Crear `config/sarif.local.properties`. Ese archivo está en `.gitignore` y sus valores
   reemplazan a los de `config/sarif.properties`, así que ahí van las claves que no se suben
   al repositorio: la de `sarif_app` (la misma del paso 1) y la
   MAP KEY de NASA FIRMS (se pide gratis en https://firms.modaps.eosdis.nasa.gov/api/map_key/):

   ```
   db.clave=CLAVE_DE_SARIF_APP
   firms.clave=TU_MAP_KEY
   ```

   Si no está en el archivo, SARIF la busca en el parámetro `firms_clave` de la base.

4. Ejecutar la aplicación:
   - desde la terminal: `mvn javafx:run`
   - desde IntelliJ IDEA: importar el `pom.xml` y ejecutar la clase `sarif.Lanzador`
     (lanza `sarif.App` sin necesidad de configurar el module-path de JavaFX).

Usuarios de prueba, todos con la clave `sarif2026`: `admin` (administrador), `mruiz` (jefa de
guardia) y `pnahuel` (operador). Cada uno puede cambiar su clave con "Cambiar clave" en la barra superior.

### Roles

El AP1 tiene un solo actor humano, el operador de guardia, pero en los procesos del organismo el
jefe de guardia es quien decide los cambios de nivel de alerta y la asignación de brigadas. SARIF
distingue tres roles (enum `Rol`, permisos en `Permiso`):

| Rol | Puede |
|---|---|
| Operador | Operar la guardia: registrar zonas, activos, recursos y relevamientos; sincronizar; calcular el índice; atender alertas (notificar, cerrar, descartar); pedir la sugerencia de despliegue; generar reportes |
| Jefe de guardia | Lo del operador, más cambiar el nivel de alerta (CU12, también "Aplicar nivel sugerido") y confirmar asignaciones de recursos (CU11) |
| Administrador | Todo: lo del jefe de guardia, más la configuración de la sincronización y la pestaña Usuarios (alta, rol, activar o desactivar, restablecer clave) |

El administrador puede todo para que, en una guardia chica donde una misma persona cumple los dos
papeles, no tenga que cambiar de usuario; cada cambio de nivel y cada asignación quedan igual
registrados con el usuario que los hizo. En las pantallas, lo que el rol no permite aparece
deshabilitado con un aviso de quién lo puede hacer; además, los servicios vuelven a controlar el
permiso con el rol vigente en la base (`Permisos.exigir`) antes de escribir. Los usuarios no se
borran, se desactivan, y SARIF no deja desactivar ni quitarle el rol al último administrador.

### Jornada de prueba

Los datos de prueba corresponden al 20/01/2026. En la barra superior elegir esa fecha de
trabajo y, en la pestaña Sincronización, usar la fuente de archivo:

1. Importar el histórico (`datos/focos_historicos_2020_2025.csv`).
2. Sincronizar focos (2 días).
3. Sincronizar meteorología (recalcula el índice).

Luego se pueden ejecutar `sql/03_insercion.sql`, `sql/04_consultas.sql` y `sql/05_borrado.sql`.

### Pestañas

| Pestaña | Casos de uso |
|---|---|
| Tablero de riesgo | CU06, CU09 y avisos de sincronizaciones fallidas (RFS20) |
| Mapa de riesgo | Mapa topográfico con curvas de nivel (Argentina y países limítrofes) con las zonas pintadas según su nivel de riesgo, el NDVI de Sentinel-2, los focos, los activos, los recursos desplegados y las marcas operativas; alta de zona dibujando un rectángulo o un polígono (CU01); clic en un punto para registrar un activo (CU02), ubicar un recurso o poner una marca; flecha de viento del día en cada zona |
| Pronóstico | Pronóstico extendido de 16 días por zona (Open-Meteo): temperatura, humedad, viento, ráfagas y dirección (de dónde viene y hacia dónde empujaría el fuego), lluvia y su probabilidad, humedad del suelo y evapotranspiración; resalta los días de la regla 30-30-30 |
| Zonas | CU01, CU04, CU05, CU12 y nuevo relevamiento de combustible (RFS21): vegetación, fósiles (hidrocarburos, gas), rastrojo, basurales y mallín |
| Activos y recursos | CU02 (activos protegidos) y CU03 (recursos operativos) |
| Sincronización | CU07, CU08, sincronización programada y carga manual de meteorología (RFS12) |
| Fuentes de datos | Estado y prueba de conexión de FIRMS, Open-Meteo, Sentinel-2 y los archivos de respaldo; configuración de la sincronización |
| Despliegue | CU10 (con ajuste manual de la sugerencia, hacia cualquier zona aunque todavía no tenga índice del día) y CU11 |
| Alertas | CU14, CU15 y alertas de riesgo automáticas; registro de a quién se avisó y por qué medio; aplicación del nivel sugerido (CU12); las detecciones de confianza baja se marcan en amarillo |
| Reportes | CU13: cronología de la guardia (alertas, notificaciones, cierres, cambios de nivel, asignaciones y lo marcado en el mapa) exportable a HTML, histórico por zona (RFS18) y desempeño del índice (RFS19) |
| Usuarios | Solo administrador: alta de usuarios, cambio de rol, activar o desactivar y restablecer clave |

### Alertas automáticas

Todas las alertas las genera el sistema, sin que nadie las cargue:

- **Foco en zona** y **Activo en riesgo** (CU14 y CU15): al sincronizar focos.
- **Riesgo elevado**: al calcular el índice, cuando una zona pasa a ALTO o EXTREMO respecto del
  día anterior. Sugiere ATENCION (ALTO) o ALERTA (EXTREMO).
- **Foco con riesgo alto**: cruza los dos datos; hay focos de confianza nominal o alta en una zona
  que ya tiene índice ALTO o EXTREMO. Sugiere ALERTA (ALTO) o EMERGENCIA (EXTREMO).
- **Nivel de alerta elevado**: cuando una persona sube a mano el nivel de alerta de una zona
  (CU12, pestaña Zonas), por ejemplo de NORMAL a EMERGENCIA. El cambio queda en el historial y la
  alerta queda PENDIENTE para registrar a quién se avisó. Bajar el nivel no genera alerta, y
  "Aplicar nivel sugerido" tampoco, porque ahí el aviso es la misma alerta de riesgo.

La sugerencia solo aparece si es mayor que el nivel vigente de la zona. El sistema **no cambia el
nivel solo**: el operador usa "Aplicar nivel sugerido...", revisa el fundamento ya armado y lo
confirma, y el cambio queda registrado a su nombre como en el CU12.

SARIF **no envía avisos** (ni correo ni mensajes): quien avisa es la guardia, por radio, teléfono,
mensaje escrito o en persona. El botón "Notificar..." pide a quién se avisó (persona, cargo u
organismo) y por qué medio, y deja la alerta en NOTIFICADA con quién lo registró y la hora. La
columna "Notificación" de la tabla lo muestra, por ejemplo "Defensa Civil de Aluminé (Radio) ·
Pedro Nahuel, 20/01/2026 19:05". Aplicar el nivel sugerido también cuenta como notificación, con
medio "Cambio de nivel en SARIF". La base no deja pasar una alerta a NOTIFICADA sin esos datos
(CHECK `ck_alerta_notificada`).

La ventana principal revisa las alertas cada 10 segundos: la pestaña muestra "Alertas (n)" con
las pendientes y, cuando aparecen alertas nuevas (por ejemplo, en la sincronización programada),
se abre un aviso con sonido en la esquina de la ventana, con un botón para ir a verlas.

### Reporte de la guardia

La pestaña Reportes arma, para el período y la zona elegidos, la **cronología de la guardia**: cada
hito en orden de fecha y hora, con la zona, el nivel de alerta que tenía la zona en ese momento y
quién lo registró:

- alerta generada por el sistema (con su descripción y el nivel sugerido);
- alerta notificada (a quién y por qué medio) y alerta cerrada o descartada, con el tiempo
  transcurrido desde que se generó;
- cambio de nivel de alerta, con su fundamento;
- asignación de recurso, con el índice y el motivo que la fundamentaron;
- recurso ubicado en el mapa y marca puesta o quitada (los que caen fuera de las zonas figuran
  como "Fuera de las zonas" y no aparecen al filtrar por una zona).

Arriba muestra el resumen: cuántas alertas, notificaciones, cierres, cambios de nivel y
asignaciones hubo, y el tiempo de aviso promedio y máximo. La otra solapa tiene el histórico de
focos e índices por zona y el desempeño del índice. "Exportar HTML..." guarda todo el reporte en
un archivo que se abre en el navegador, desde donde se puede imprimir o guardar como PDF.

Como los hitos se registran con la hora real, el período propuesto termina en la fecha de trabajo
o en el día de hoy, la que sea posterior. La consulta equivalente en SQL es la C-11.

### Mapa de riesgo

El mapa base es [OpenTopoMap](https://opentopomap.org) (datos de OpenStreetMap y relieve SRTM):
sombreado, curvas de nivel, ríos, lagos, rutas, caminos y nombres de lugares, de todo el país y
los países vecinos. Es un mapa por mosaicos (cuadrados de 256 píxeles por nivel de zoom, en
proyección Web Mercator, `ProyeccionMercator`); el visor está programado en JavaFX sobre un
`Canvas`, sin bibliotecas externas.

- **Sin conexión.** Cada mosaico se busca primero en la carpeta `cache/mosaicos` (todo lo que se
  vio alguna vez queda guardado ahí), después en el mapa general que viene dentro del programa
  (`src/main/resources/sarif/mapa/mosaicos`: Argentina y limítrofes hasta el zoom 6 y Neuquén hasta
  el 9, unos 8 MB) y recién entonces se descarga. Sin internet se ve todo lo guardado; donde falta
  detalle se muestra ampliado el mosaico de menos zoom, algo borroso, en vez de un hueco.
  No se descarga el mapa entero porque las reglas de uso de OpenTopoMap lo prohíben.
  El mapa general lo arma `herramientas/GeneradorMapa.java` (se corre una sola vez, con conexión):

  ```
  javac -encoding UTF-8 -d %TEMP%\mapa herramientas/GeneradorMapa.java
  java -cp %TEMP%\mapa GeneradorMapa
  ```

- **Zonas.** Se ven con el color de su nivel de riesgo del día (punteadas si no tienen índice o
  no están vigiladas). Al pasar el mouse se ve el índice con sus componentes, el nivel de alerta,
  el NDVI, los focos y los recursos de la zona.
- **Encuadre.** Los botones "Argentina", "Neuquén" y "Zonas" y el combo "Ir a una provincia..."
  (las 23 provincias y la Ciudad de Buenos Aires, `Provincia`) llevan el mapa a esa región. Fuera
  de Neuquén el detalle se descarga la primera vez; sin conexión se ve el mapa general ampliado.
- **Zona nueva (CU01).** "Rectángulo": se arrastra con el botón izquierdo. "Polígono": clic en
  cada vértice y, para cerrar, clic sobre el primero o doble clic (clic derecho borra el último).
  "Crear zona con esta forma" pasa a la pestaña Zonas con la forma cargada. Un polígono se valida
  igual que un rectángulo (nombre, superposición con otras zonas) y además no puede tener bordes
  que se crucen. Los vértices se guardan en la tabla `zona_vertice`; la zona conserva el rectángulo
  que la encierra, que es lo que se usa para consultar FIRMS y Sentinel. Un foco pertenece a una
  zona poligonal si cae dentro del polígono (regla del rayo, en `Zona.contiene`).
- **Clic en un punto.** Sin la herramienta de rectángulo o polígono activa, un clic (izquierdo o
  derecho) sobre el mapa abre un menú con las coordenadas y la zona del punto:
  - *Registrar activo protegido aquí* (CU02): nombre, tipo y distancia de alerta; la zona se elige
    entre las que contienen el punto, así que fuera de las zonas la opción está deshabilitada.
  - *Ubicar recurso desplegado aquí*: se elige uno de los recursos ASIGNADO (brigadas, autobombas,
    medios aéreos) y una observación optativa. Cada ubicación se agrega a la tabla
    `posicion_recurso` con quién la informó y cuándo; el mapa muestra la última de cada recurso,
    como un rectángulo verde (terrestre) o azul (aéreo) con la dotación adentro. Al liberar el
    recurso deja de verse.
  - *Agregar marca aquí*: puesto de comando, punto de agua, acceso o camino, punto de evacuación,
    peligro u otra, con una descripción (tabla `marca_mapa`). Haciendo clic sobre una marca se la
    puede quitar; no se borra, queda con quién y cuándo la quitó.
  - *Copiar coordenadas*.

  Ubicar recursos y poner marcas se permite también fuera de las zonas (un recurso en camino o un
  punto de agua pueden estar afuera). Todo queda en la cronología del reporte; las consultas SQL
  equivalentes son la C-11 y la C-12.
- **Focos satelitales de la región.** Además de los focos guardados de las zonas, la capa "Focos
  satelitales de toda la región" le pide a FIRMS, en vivo, los focos de Argentina, Chile y los países
  vecinos de los días elegidos (un día de septiembre trae más de mil). Se dibujan más chicos y no se
  guardan en la base, que solo guarda los de las zonas vigiladas, que son los que usa el índice. La
  consulta se reutiliza 10 minutos para no gastar la cuota de la clave.
- **NDVI en el mapa.** La capa "NDVI de Sentinel-2" pide a Copernicus una imagen coloreada de cada
  zona (la pasada menos nublada de los 30 días previos, recortada al polígono; nubes y agua
  transparentes) y la guarda en `cache/ndvi`, así la segunda vez no gasta cuota ni necesita red.
  Necesita las credenciales de Copernicus (ver más abajo).

Las direcciones y carpetas se cambian en `config/sarif.properties` (`mapa.url`, `mapa.cache`,
`ndvi.cache`); `cache/` está en `.gitignore`. Atribución: © OpenTopoMap (CC-BY-SA), © colaboradores
de OpenStreetMap, SRTM; NDVI: Copernicus Sentinel-2 (ESA).

### Fuente remota

- **NASA FIRMS:** la API acepta como máximo 5 días por consulta, así que parto el período en
  tramos. El producto `_NRT` solo cubre los últimos meses; para fechas anteriores SARIF pide
  automáticamente el archivo histórico `_SP` del mismo sensor, y de ese archivo descarta las
  detecciones que no son vegetación (`type` distinto de 0: volcanes como el Copahue y fuentes fijas).
- **Open-Meteo:** no requiere clave. Además de temperatura, humedad, viento y precipitación trae la
  humedad del suelo de 0 a 10 cm (`soil_moisture_0_to_10cm_mean`, en m³/m³), que implementa el
  requerimiento candidato RC06: cuando está disponible pesa el 15 % de la componente meteorológica
  (0,35 m³/m³ o más cuenta como suelo húmedo y 0,10 o menos como seco). Si falta, como en los
  archivos de la jornada de prueba, el índice se calcula igual que antes.
  La API de pronóstico (`api.open-meteo.com`) solo acepta fechas de los últimos meses; cuando la
  fecha de trabajo tiene más de 80 días (por ejemplo, la jornada del 20/01/2026), SARIF consulta la
  API histórica de pronósticos (`historical-forecast-api.open-meteo.com`), que tiene los mismos
  parámetros y el mismo CSV.
  También pide el vector del viento (ráfaga máxima `wind_gusts_10m_max` y dirección dominante
  `wind_direction_10m_dominant`, en grados desde donde sopla), la temperatura mínima, la
  probabilidad de lluvia y la evapotranspiración de referencia FAO. No entran en el índice: quedan
  guardados en `registro_meteo` para analizar hacia dónde puede avanzar un incendio. Cuando la fecha
  de trabajo es hoy, la sincronización trae además el pronóstico de los próximos 15 días (pestaña
  Pronóstico, botón "Actualizar pronóstico"); cada nueva sincronización reemplaza el pronóstico
  anterior de cada día. Estos datos salen de modelos numéricos del tiempo (que usan, entre otras
  fuentes, observaciones satelitales), no de una imagen de satélite directa.
- **Sentinel-2 (NDVI, RC05):** el botón "Sincronizar NDVI (Sentinel-2)" de la pestaña Sincronización
  trae, para cada zona activa, el NDVI medio de la imagen despejada más reciente de los 30 días
  anteriores a la fecha de trabajo (se descartan nubes, sombras, agua y nieve con la clasificación
  SCL, y se exige al menos un 20 % de la zona despejada). Se muestra en la pestaña Zonas y en el
  detalle del mapa (donde además se puede ver la imagen NDVI completa de cada zona) y es
  **informativo**: no modifica la fórmula del índice. La API de Copernicus Data Space exige OAuth2
  con POST y responde en JSON, lo que se aparta de RNF05 (GET con CSV); se resolvió igual con el
  cliente HTTP estándar de Java y sin bibliotecas externas.

  Para usarlo hace falta una cuenta gratuita de Copernicus Data Space:
  1. Registrarse en <https://dataspace.copernicus.eu>.
  2. Entrar a <https://shapps.dataspace.copernicus.eu/dashboard/>, ir a *User settings* →
     *OAuth clients* → *Create* y copiar el *Client ID* y el *Client secret* (el secreto se muestra
     una sola vez).
  3. Agregarlos en `config/sarif.local.properties` (no se sube al repositorio):
     ```
     copernicus.cliente=sh-xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
     copernicus.secreto=...
     ```
  4. Verificar con "Probar conexión" en la pestaña Fuentes de datos.
- Si la fuente remota falla y existe el archivo de `datos/`, la sincronización usa el archivo como
  respaldo y lo avisa. La sincronización programada se activa desde la pestaña Sincronización con el
  intervalo del parámetro `sincronizacion_minutos` (180 minutos por defecto), que se puede cambiar
  desde la pestaña Fuentes de datos.

### Requerimiento candidato RC07: sensores y estaciones de campo

El primer trabajo menciona los sensores distribuidos en zonas agrestes como parte de la operatoria
actual del CIPI. Su integración queda registrada como requerimiento candidato y no forma parte de
esta versión:

> **RC07** — Integración de los sensores y estaciones de campo del organismo (telemetría de
> temperatura, humedad, viento o detección de humo), con alta y vinculación de dispositivos a una
> zona de vigilancia, para incorporar sus lecturas a la componente meteorológica del índice y a la
> detección de focos. Requiere recibir datos desde la red o mediante protocolos de telemetría, lo
> que excede la arquitectura de consulta HTTPS GET en formato CSV (8.3 y RNF05) y la restricción
> de no usar bibliotecas de terceros (RNF01).

La pestaña Fuentes de datos ya reúne las conexiones del sistema, así que es el lugar natural donde
se sumarían los dispositivos si se implementara el RC07.

## Pruebas

```
./ejecutar_pruebas.sh        # recrea la base, compila y ejecuta las 51 pruebas
mvn test                     # solo las pruebas (las de integración requieren la base creada)
```

Si la base no está disponible, las pruebas de integración se omiten y se ejecutan solo las
unitarias.
