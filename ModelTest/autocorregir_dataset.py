import json
import os
import threading
import tkinter as tk
import unicodedata
from pathlib import Path
from tkinter import messagebox, ttk

import requests


DEFAULT_BASE_URL = "http://localhost:4981/claude"
DEFAULT_MODEL = "gemini-3.6-flash"


def cargar_dataset(ruta: Path) -> list[dict]:
    with ruta.open("r", encoding="utf-8-sig") as archivo:
        contenido = json.load(archivo)
    muestras = contenido.get("root") if isinstance(contenido, dict) else contenido
    if not isinstance(muestras, list):
        raise ValueError("El dataset debe ser una lista o tener una clave 'root'.")
    return [muestra for muestra in muestras if isinstance(muestra, dict)]


def extraer_json(texto: str) -> list[dict]:
    texto = texto.strip()
    if texto.startswith("```"):
        texto = texto.removeprefix("```json").removeprefix("```").removesuffix("```").strip()
    inicio = texto.find("[")
    fin = texto.rfind("]")
    if inicio >= 0 and fin > inicio:
        texto = texto[inicio:fin + 1]
    resultado = json.loads(texto)
    if not isinstance(resultado, list) or not all(isinstance(item, dict) for item in resultado):
        raise ValueError("La respuesta no contiene una lista JSON de objetos.")
    return resultado


def llamar_gemini(prompt: str, max_tokens: int = 4096) -> list[dict]:
    base_url = os.getenv("GEMINI_API_BASE_URL", DEFAULT_BASE_URL)
    modelo = os.getenv("GEMINI_MODEL", DEFAULT_MODEL)
    respuesta = requests.post(
        f"{base_url.rstrip('/')}/v1/messages",
        headers={
            "content-type": "application/json",
            "x-api-key": os.getenv("ANTHROPIC_API_KEY", "not-needed"),
            "anthropic-version": "2023-06-01",
        },
        json={
            "model": modelo,
            "max_tokens": max_tokens,
            "temperature": 0,
            "messages": [{"role": "user", "content": prompt}],
        },
        timeout=300,
    )
    respuesta.raise_for_status()
    contenido = respuesta.json().get("content", [])
    texto = "".join(parte.get("text", "") for parte in contenido if parte.get("type") == "text")
    return extraer_json(texto)


def elegir_dataset() -> tuple[Path, list[dict]]:
    datasets = []
    for archivo in sorted(Path(".").glob("*.json")):
        try:
            muestras = cargar_dataset(archivo)
        except (OSError, json.JSONDecodeError, ValueError):
            continue
        if any(muestra.get("palabras") for muestra in muestras):
            datasets.append((archivo, muestras))
    if not datasets:
        raise FileNotFoundError("No hay datasets JSON en la carpeta actual.")
    print("\nDatasets disponibles:")
    for indice, (archivo, _) in enumerate(datasets, 1):
        print(f"  {indice}. {archivo.name}")
    seleccion = int(input("\n¿Qué dataset quieres usar? Número: ")) - 1
    if seleccion < 0 or seleccion >= len(datasets):
        raise ValueError("Selección de dataset no válida.")
    return datasets[seleccion]


IDIOMAS_DATASET = {
    "Chinese": "chino",
    "English": "inglés",
    "Esperanto": "esperanto",
    "French": "francés",
    "German": "alemán",
    "Italian": "italiano",
    "Japanese": "japonés",
    "Korean": "coreano",
    "Portuguese": "portugués",
    "Spanish": "español",
}


def usa_alfabeto_latino(muestras: list[dict]) -> bool:
    texto = " ".join(str(item.get("oracion", "")) for item in muestras)
    letras = [caracter for caracter in texto if caracter.isalpha()]
    latinas = [caracter for caracter in letras if "LATIN" in unicodedata.name(caracter, "")]
    return bool(letras) and len(latinas) / len(letras) >= 0.7


def idiomas_de_generacion(ruta: Path, muestras: list[dict]) -> tuple[str, str]:
    idioma_dataset = IDIOMAS_DATASET.get(ruta.stem.replace("Sentences", ""), "el idioma del dataset")
    idioma_palabras = idioma_dataset if usa_alfabeto_latino(muestras) else "inglés"
    return idioma_dataset, idioma_palabras


def generar_muestras(palabras: list[str], cantidad: int, idioma_palabras: str) -> list[dict]:
    prompt = """Genera muestras NUEVAS para probar un modelo de comunicación asistida.

Reglas estrictas:
- Devuelve únicamente una lista JSON válida, sin Markdown ni explicaciones.
- Devuelve exactamente la cantidad solicitada.
- Cada objeto debe tener únicamente la clave 'palabras'.
- Todos los valores de 'palabras' deben estar escritos en {idioma_palabras}.
- Usa la lista proporcionada únicamente como referencia de conceptos e intenciones.
- Si una palabra de la lista está en otro idioma, tradúcela a {idioma_palabras} antes de usarla.
- Puedes combinar conceptos de la lista, pero no inventes conceptos ajenos al dataset.
- No copies exactamente las muestras existentes.

Cantidad solicitada: {cantidad}
Muestras existentes de palabras:
{palabras}
""".format(
        cantidad=cantidad,
        idioma_palabras=idioma_palabras,
        palabras=json.dumps(palabras, ensure_ascii=False, indent=2),
    )
    muestras = llamar_gemini(prompt)
    return [{"palabras": str(item["palabras"])} for item in muestras if item.get("palabras")]


def leer_json_lineas() -> list[dict]:
    print("\nPega aquí la salida del modelo, una muestra JSON por línea.")
    print("Escribe FIN en una línea independiente cuando termines.\n")
    lineas = []
    while True:
        linea = input()
        if linea.strip().upper() == "FIN":
            break
        if linea.strip():
            lineas.append(linea)
    try:
        return extraer_json("\n".join(lineas))
    except json.JSONDecodeError:
        muestras = []
        for linea in lineas:
            try:
                muestra = json.loads(linea)
                if isinstance(muestra, dict):
                    muestras.append(muestra)
            except json.JSONDecodeError:
                print(f"Línea ignorada por no ser JSON válido: {linea}")
        return muestras


def revisar_salida(entrada: list[dict], salida_modelo: list[dict], muestras_dataset: list[dict], idioma_dataset: str) -> list[dict]:
    contexto = [{"palabras": item.get("palabras", ""), "oracion": item.get("oracion", "")} for item in muestras_dataset]
    prompt = """Revisa la salida de un modelo que convirtió palabras en oraciones.

Devuelve únicamente una lista JSON válida, sin Markdown.
Cada objeto debe tener exactamente las claves 'palabras' y 'oracion'.

Reglas:
- Conserva exactamente 'palabras' de la entrada generada.
- La oración de cada objeto debe estar en {idioma_dataset}.
- Si la oración es incorrecta, poco natural o no corresponde a las palabras, reemplázala por una oración natural.
- Si la oración es correcta, conserva su contenido.
- Usa el dataset de referencia solo como guía de estilo y significado.

Entrada generada:
{entrada}

Salida del modelo para revisar:
{salida}

Dataset de referencia:
{contexto}
""".format(
        idioma_dataset=idioma_dataset,
        entrada=json.dumps(entrada, ensure_ascii=False, indent=2),
        salida=json.dumps(salida_modelo, ensure_ascii=False, indent=2),
        contexto=json.dumps(contexto, ensure_ascii=False, indent=2),
    )
    return llamar_gemini(prompt)


def guardar(ruta: Path, muestras: list[dict]) -> None:
    with ruta.open("w", encoding="utf-8") as archivo:
        json.dump(muestras, archivo, ensure_ascii=False, indent=2)
        archivo.write("\n")


def listar_datasets() -> list[tuple[Path, list[dict]]]:
    datasets = []
    for archivo in sorted(Path(".").glob("*.json")):
        try:
            muestras = cargar_dataset(archivo)
        except (OSError, json.JSONDecodeError, ValueError):
            continue
        if any(muestra.get("palabras") for muestra in muestras):
            datasets.append((archivo, muestras))
    return datasets


def parsear_salida(texto: str) -> list[dict]:
    try:
        return extraer_json(texto)
    except json.JSONDecodeError:
        muestras = []
        for linea in texto.splitlines():
            try:
                muestra = json.loads(linea.strip())
                if isinstance(muestra, dict):
                    muestras.append(muestra)
            except json.JSONDecodeError:
                continue
        if not muestras:
            raise ValueError("No se encontraron objetos JSON válidos en la salida.")
        return muestras


def iniciar_interfaz() -> None:
    datasets = listar_datasets()
    if not datasets:
        raise FileNotFoundError("No hay datasets JSON con muestras 'palabras'.")

    ventana = tk.Tk()
    ventana.title("Gemini Flash - Generador y revisor")
    ventana.geometry("900x720")
    ventana.minsize(700, 560)

    fuente = ("Segoe UI", 10)
    tk.Label(ventana, text="Generador y revisor de muestras", font=("Segoe UI", 18, "bold")).pack(anchor="w", padx=20, pady=(18, 4))
    tk.Label(ventana, text="Genera entradas nuevas, pruébalas en el notebook y revisa la salida.", font=fuente).pack(anchor="w", padx=20, pady=(0, 14))

    controles = tk.Frame(ventana)
    controles.pack(fill="x", padx=20)
    tk.Label(controles, text="Dataset:", font=fuente).pack(side="left")
    dataset_var = tk.StringVar(value=datasets[0][0].name)
    dataset_menu = ttk.Combobox(controles, textvariable=dataset_var, state="readonly", values=[ruta.name for ruta, _ in datasets], width=32)
    dataset_menu.pack(side="left", padx=(8, 20))
    tk.Label(controles, text="Cantidad:", font=fuente).pack(side="left")
    cantidad_var = tk.IntVar(value=10)
    tk.Spinbox(controles, from_=1, to=100, textvariable=cantidad_var, width=6).pack(side="left", padx=8)
    generar_btn = tk.Button(controles, text="Generar muestras", font=fuente)
    generar_btn.pack(side="left", padx=8)

    estado_var = tk.StringVar(value="Listo. Asegúrate de tener server.bat ejecutándose.")
    tk.Label(ventana, textvariable=estado_var, anchor="w", fg="#555", font=fuente).pack(fill="x", padx=20, pady=10)

    tk.Label(ventana, text="Entrada para el notebook", font=("Segoe UI", 11, "bold")).pack(anchor="w", padx=20)
    entrada_texto = tk.Text(ventana, height=10, wrap="none", font=("Consolas", 10))
    entrada_texto.pack(fill="both", expand=True, padx=20, pady=(4, 12))

    tk.Label(ventana, text="Pega aquí la salida del notebook", font=("Segoe UI", 11, "bold")).pack(anchor="w", padx=20)
    salida_texto = tk.Text(ventana, height=10, wrap="none", font=("Consolas", 10))
    salida_texto.pack(fill="both", expand=True, padx=20, pady=(4, 8))

    botones = tk.Frame(ventana)
    botones.pack(fill="x", padx=20, pady=(0, 16))
    revisar_btn = tk.Button(botones, text="Revisar y corregir salida", font=fuente, state="disabled")
    revisar_btn.pack(side="left")

    def ejecutar_en_segundo_plano(funcion, al_terminar):
        def trabajo():
            try:
                resultado = funcion()
                ventana.after(0, lambda: al_terminar(resultado, None))
            except Exception as error:
                ventana.after(0, lambda: al_terminar(None, error))
        threading.Thread(target=trabajo, daemon=True).start()

    def terminar_generacion(resultado, error):
        generar_btn.config(state="normal")
        if error:
            estado_var.set("Error al generar muestras.")
            messagebox.showerror("Gemini Flash", str(error))
            return
        entrada_texto.delete("1.0", "end")
        entrada_texto.insert("1.0", "\n".join(json.dumps(item, ensure_ascii=False) for item in resultado))
        revisar_btn.config(state="normal")
        estado_var.set("Muestras generadas. Pruébalas en el notebook y pega la salida abajo.")

    def generar():
        indice = dataset_menu.current()
        ruta, dataset = datasets[indice]
        try:
            cantidad = cantidad_var.get()
            if cantidad < 1:
                raise ValueError("La cantidad debe ser mayor que cero.")
        except (tk.TclError, ValueError) as error:
            messagebox.showerror("Cantidad inválida", str(error))
            return
        palabras = sorted({str(item["palabras"]).strip() for item in dataset if item.get("palabras")})
        idioma_dataset, idioma_palabras = idiomas_de_generacion(ruta, dataset)
        generar_btn.config(state="disabled")
        estado_var.set(f"Generando muestras en {idioma_palabras} desde {ruta.name}...")
        ejecutar_en_segundo_plano(lambda: generar_muestras(palabras, cantidad, idioma_palabras), terminar_generacion)

    def terminar_revision(resultado, error):
        revisar_btn.config(state="normal")
        if error:
            estado_var.set("Error al revisar la salida.")
            messagebox.showerror("Gemini Flash", str(error))
            return
        ruta, _ = datasets[dataset_menu.current()]
        ruta_revision = Path(f"revision_{ruta.stem}.json")
        corregidas = [{"palabras": item.get("palabras", ""), "oracion": item.get("oracion", "")} for item in resultado if item.get("palabras") and item.get("oracion")]
        with ruta_revision.open("w", encoding="utf-8") as archivo:
            json.dump({"root": corregidas}, archivo, ensure_ascii=False, indent=2)
            archivo.write("\n")
        ruta_corregidas = Path(f"muestras_corregidas_{ruta.stem}.json")
        with ruta_corregidas.open("w", encoding="utf-8") as archivo:
            json.dump({"root": corregidas}, archivo, ensure_ascii=False, indent=2)
            archivo.write("\n")
        salida_texto.delete("1.0", "end")
        salida_texto.insert("1.0", json.dumps({"root": corregidas}, ensure_ascii=False, indent=2))
        estado_var.set(f"Revisión guardada en {ruta_revision.name}; dataset listo en {ruta_corregidas.name}.")

    def revisar():
        try:
            entrada = parsear_salida(entrada_texto.get("1.0", "end"))
            salida = parsear_salida(salida_texto.get("1.0", "end"))
        except ValueError as error:
            messagebox.showerror("JSON inválido", str(error))
            return
        dataset = datasets[dataset_menu.current()][1]
        ruta, _ = datasets[dataset_menu.current()]
        idioma_dataset, _ = idiomas_de_generacion(ruta, dataset)
        revisar_btn.config(state="disabled")
        estado_var.set("Revisando salida con Gemini Flash...")
        ejecutar_en_segundo_plano(lambda: revisar_salida(entrada, salida, dataset, idioma_dataset), terminar_revision)

    generar_btn.config(command=generar)
    revisar_btn.config(command=revisar)
    ventana.mainloop()


def main() -> None:
    iniciar_interfaz()


if __name__ == "__main__":
    main()