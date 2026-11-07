"""Genera las capturas de código usadas por el informe de la AP2.

Las imágenes salen siempre de los fuentes locales, incluyen ruta y números de
línea y se pueden volver a crear con:

    python herramientas/generar_capturas_codigo.py
"""

from io import BytesIO
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont
from pygments import highlight
from pygments.formatters import ImageFormatter
from pygments.lexers import JavaLexer


RAIZ = Path(__file__).resolve().parents[1]
DESTINO = RAIZ / "docs" / "capturas" / "codigo"
CAPTURAS = (
    ("src/main/java/sarif/fuentes/FuenteRemota.java", 92, 110, "codigo01_fuente_remota.png"),
    ("src/main/java/sarif/servicio/CalculadorRiesgo.java", 53, 67, "codigo02_calculo_riesgo.png"),
    ("src/main/java/sarif/servicio/ServicioZonas.java", 57, 87, "codigo03_transaccion_cu01.png"),
    ("src/main/java/sarif/servicio/Permisos.java", 29, 41, "codigo04_permisos.png"),
)


def fuente(nombre: str, tamano: int) -> ImageFont.FreeTypeFont:
    ruta = Path("C:/Windows/Fonts") / nombre
    return ImageFont.truetype(str(ruta), tamano)


def generar(ruta_relativa: str, desde: int, hasta: int, nombre: str) -> None:
    ruta = RAIZ / ruta_relativa
    lineas = ruta.read_text(encoding="utf-8").splitlines()
    fragmento = "\n".join(lineas[desde - 1 : hasta]) + "\n"

    formato = ImageFormatter(
        style="friendly",
        font_name="Consolas",
        font_size=19,
        line_numbers=True,
        line_number_start=desde,
        line_number_bg="#EEF3F2",
        line_number_fg="#48645F",
        line_number_separator=True,
        line_number_pad=12,
        image_pad=24,
        line_pad=4,
    )
    codigo = Image.open(BytesIO(highlight(fragmento, JavaLexer(), formato))).convert("RGB")

    alto_cabecera = 76
    lienzo = Image.new("RGB", (codigo.width, codigo.height + alto_cabecera), "#173F3A")
    lienzo.paste(codigo, (0, alto_cabecera))
    dibujo = ImageDraw.Draw(lienzo)
    dibujo.text((24, 13), f"{ruta_relativa}  ·  líneas {desde}–{hasta}",
                font=fuente("segoeuib.ttf", 24), fill="#FFFFFF")
    dibujo.text((24, 44), "Fuente local del prototipo SARIF",
                font=fuente("segoeui.ttf", 16), fill="#CFE7E2")

    DESTINO.mkdir(parents=True, exist_ok=True)
    lienzo.save(DESTINO / nombre, optimize=True, dpi=(180, 180))


if __name__ == "__main__":
    for captura in CAPTURAS:
        generar(*captura)
    print(f"Capturas generadas en {DESTINO}")
