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

1. Crear la base con un usuario administrador de MySQL (por ejemplo, `root`):

   ```
   mysql -u root -p < sql/01_esquema.sql
   mysql -u root -p < sql/02_datos.sql
   ```

   El script 01 crea también el usuario de aplicación `sarif_app`, que solo puede leer y
   escribir datos.

2. Crear `config/sarif.local.properties`. Ese archivo está en `.gitignore` y sus valores
   reemplazan a los de `config/sarif.properties`, así que ahí van las claves que no se suben
   al repositorio: la del usuario `sarif_app` (la del `CREATE USER` del script 01) y la
   MAP KEY de NASA FIRMS (se pide gratis en https://firms.modaps.eosdis.nasa.gov/api/map_key/):

   ```
   db.clave=CLAVE_DE_SARIF_APP
   firms.clave=TU_MAP_KEY
   ```

   Si no está en el archivo, SARIF la busca en el parámetro `firms_clave` de la base.

3. Ejecutar la aplicación:
   - desde la terminal: `mvn javafx:run`
   - desde IntelliJ IDEA: importar el `pom.xml` y ejecutar la clase `sarif.Lanzador`
     (lanza `sarif.App` sin necesidad de configurar el module-path de JavaFX).

Usuarios de prueba: `pnahuel` (operador) y `mruiz` (jefa de guardia), clave `sarif2026`.

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
| Mapa de riesgo | Mapa topográfico con curvas de nivel (Argentina y países limítrofes) con las zonas pintadas según su nivel de riesgo, el NDVI de Sentinel-2, los focos y los activos; alta de zona dibujando un rectángulo o un polígono (CU01) |
| Zonas | CU01, CU04, CU05, CU12 y nuevo relevamiento de combustible (RFS21) |
| Activos y recursos | CU02 (activos protegidos) y CU03 (recursos operativos) |
| Sincronización | CU07, CU08, sincronización programada y carga manual de meteorología (RFS12) |
| Fuentes de datos | Estado y prueba de conexión de FIRMS, Open-Meteo, Sentinel-2 y los archivos de respaldo; configuración de la sincronización |
| Despliegue | CU10 (con ajuste manual de la sugerencia) y CU11 |
| Alertas | CU14, CU15 y alertas de riesgo automáticas; aplicación del nivel sugerido (CU12); las detecciones de confianza baja se marcan en amarillo |
| Reportes | CU13: histórico por zona (RFS18) y desempeño del índice (RFS19) |

### Alertas automáticas

Todas las alertas las genera el sistema, sin que nadie las cargue:

- **Foco en zona** y **Activo en riesgo** (CU14 y CU15): al sincronizar focos.
- **Riesgo elevado**: al calcular el índice, cuando una zona pasa a ALTO o EXTREMO respecto del
  día anterior. Sugiere ATENCION (ALTO) o ALERTA (EXTREMO).
- **Foco con riesgo alto**: cruza los dos datos; hay focos de confianza nominal o alta en una zona
  que ya tiene índice ALTO o EXTREMO. Sugiere ALERTA (ALTO) o EMERGENCIA (EXTREMO).

La sugerencia solo aparece si es mayor que el nivel vigente de la zona. El sistema **no cambia el
nivel solo**: el operador usa "Aplicar nivel sugerido...", revisa el fundamento ya armado y lo
confirma, y el cambio queda registrado a su nombre como en el CU12.

La ventana principal revisa las alertas cada 10 segundos: la pestaña muestra "Alertas (n)" con
las pendientes y, cuando aparecen alertas nuevas (por ejemplo, en la sincronización programada),
se abre un aviso con sonido en la esquina de la ventana, con un botón para ir a verlas.

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
- **Zona nueva (CU01).** "Rectángulo": se arrastra con el botón izquierdo. "Polígono": clic en
  cada vértice y, para cerrar, clic sobre el primero o doble clic (clic derecho borra el último).
  "Crear zona con esta forma" pasa a la pestaña Zonas con la forma cargada. Un polígono se valida
  igual que un rectángulo (nombre, superposición con otras zonas) y además no puede tener bordes
  que se crucen. Los vértices se guardan en la tabla `zona_vertice`; la zona conserva el rectángulo
  que la encierra, que es lo que se usa para consultar FIRMS y Sentinel. Un foco pertenece a una
  zona poligonal si cae dentro del polígono (regla del rayo, en `Zona.contiene`).
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
./ejecutar_pruebas.sh        # recrea la base, compila y ejecuta las 40 pruebas
mvn test                     # solo las pruebas (las de integración requieren la base creada)
```

Si la base no está disponible, las pruebas de integración se omiten y se ejecutan solo las
unitarias.
