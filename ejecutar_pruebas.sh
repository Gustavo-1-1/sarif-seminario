#!/usr/bin/env bash
# Recrea la base de datos, compila el proyecto y ejecuta las 51 pruebas automatizadas.
# El resultado queda en evidencias/resultado_pruebas.txt.
#
# Uso:  ./ejecutar_pruebas.sh [usuario_administrador_mysql]     (por defecto, root)
#
# Armé este script para que las pruebas se puedan repetir siempre desde el
# mismo punto de partida: las de integración (PI-01 a PI-10) necesitan una base
# recién creada, porque verifican cantidades exactas de la jornada de prueba.
# Si querés correrlas, ejecutá este script con un usuario administrador de
# MySQL; te va a pedir su clave una sola vez.

# Corto la ejecución ante el primer error, variable sin definir o falla dentro
# de una tubería, así no se corren las pruebas sobre una base a medio crear.
set -euo pipefail
# Me paro en la carpeta del script para que las rutas relativas (sql/, datos/,
# evidencias/) funcionen desde donde sea que se lo llame.
cd "$(dirname "$0")"

# El primer argumento es el usuario administrador; si no se pasa, uso root.
USUARIO="${1:-root}"

# Borro y recreo la base con el esquema y los datos de prueba. Los dos scripts
# van en la misma invocación de mysql para pedir la clave una sola vez, y
# fuerzo utf8mb4 para que las tildes y eñes de los datos se carguen bien.
echo "== Recreando la base sarif con sql/01_esquema.sql y sql/02_datos.sql (clave de ${USUARIO}) =="
mysql -u "${USUARIO}" -p --default-character-set=utf8mb4 -e "source sql/01_esquema.sql; source sql/02_datos.sql;"

# Compilo y corro todas las pruebas con Maven. Con tee muestro la salida en
# pantalla y a la vez la guardo como evidencia para el informe.
echo "== Compilando y ejecutando las pruebas =="
mkdir -p evidencias
mvn test 2>&1 | tee evidencias/resultado_pruebas.txt
